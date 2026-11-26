// Headless walk-through of examples/compose-tree in real Scittle + Replicant, with timings.
import puppeteer from 'puppeteer-core';
const base = process.argv[2];
const browser = await puppeteer.launch({ executablePath: process.env.CHROME ?? '/Applications/Google Chrome.app/Contents/MacOS/Google Chrome', args: ['--no-sandbox'] });
const results = []; const problems = [];
const check = (name, ok, d = '') => { results.push(ok); console.log(`${ok ? 'PASS' : 'FAIL'} ${name}${d ? ' — ' + d : ''}`); };
const sel = (ev) => `[data-event="${ev}"]`;

async function open(url) {
  const page = await browser.newPage();
  page.on('console', m => { if (['error', 'warn', 'warning'].includes(m.type())) problems.push(`${m.type()}: ${m.text()}`); });
  page.on('pageerror', e => problems.push(`pageerror: ${e.message}`));
  await page.goto(url);
  await page.waitForSelector(sel(':load'), { timeout: 20000 });
  return page;
}
const statusText = (p) => p.$eval('.status', e => e.innerText);

for (const n of [300, 3000]) {
  const page = await open(`${base}?n=${n}`);
  check(`n=${n}: Refresh and Retry disabled before load`, await page.$eval(sel(':refresh'), e => e.disabled) && await page.$eval(sel(':retry'), e => e.disabled));
  let t0 = Date.now();
  await page.click(sel(':load'));
  await page.waitForFunction((n) => document.querySelectorAll('.row').length === n, { timeout: 20000 }, n);
  check(`n=${n}: join → ready with ${n} rows`, (await statusText(page)).startsWith(':ready'), `${Date.now() - t0} ms incl. 300 ms fake latency`);
  check(`n=${n}: Load disabled after loading`, await page.$eval(sel(':load'), e => e.disabled));
  // toggle latency: click → detail visible
  const times = [];
  for (const i of [5, 50, Math.min(n - 1, 250)]) {
    t0 = performance.now();
    await page.click(`.row:nth-child(${i + 1}) .caret`);
    await page.waitForSelector(`.row:nth-child(${i + 1}) .detail`);
    times.push(Math.round(performance.now() - t0));
  }
  check(`n=${n}: row toggles open (lazy instances)`, true, `click→paint ${times.join(', ')} ms`);
  await page.waitForFunction(() => document.querySelector('.status').innerText.includes('tx'), { timeout: 5000 });
  check(`n=${n}: late activity request arrives after the join`, (await statusText(page)).includes('2 tx'));
  await page.click(sel(':refresh'));
  check(`n=${n}: refresh: prices instance reloading`, (await statusText(page)).includes('prices loading'));
  await page.waitForFunction(() => !document.querySelector('.status').innerText.includes('prices loading'));
  await page.close();
}

const page = await open(`${base}?n=30&fail=prices`);
await page.click(sel(':load'));
await page.waitForSelector('.error', { timeout: 5000 });
check('fail=prices: failure shown, Retry enabled', !(await page.$eval(sel(':retry'), e => e.disabled)));
await page.click(sel(':retry'));
await page.waitForFunction(() => document.querySelectorAll('.row').length === 30, { timeout: 5000 });
check('retry → ready', (await statusText(page)).startsWith(':ready'));

check('no console errors or warnings', problems.length === 0, problems.slice(0, 3).join(' | '));
await browser.close();
process.exitCode = results.every(Boolean) ? 0 : 1;
