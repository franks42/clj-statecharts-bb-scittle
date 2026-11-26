// Headless walk-through of examples/compose-form in real Scittle + Replicant.
// Usage: node smoke.mjs http://127.0.0.1:8741/examples/compose-form/
import puppeteer from 'puppeteer-core';
const base = process.argv[2];
const browser = await puppeteer.launch({ executablePath: process.env.CHROME ?? '/Applications/Google Chrome.app/Contents/MacOS/Google Chrome', args: ['--no-sandbox'] });
const results = []; const problems = [];
const check = (name, ok, d = '') => { results.push(ok); console.log(`${ok ? 'PASS' : 'FAIL'} ${name}${d ? ' — ' + d : ''}`); };

async function open(url) {
  const page = await browser.newPage();
  page.on('console', m => { if (['error', 'warn', 'warning'].includes(m.type())) problems.push(`${m.type()}: ${m.text()}`); });
  page.on('pageerror', e => problems.push(`pageerror: ${e.message}`));
  await page.goto(url);
  await page.waitForSelector('.status', { timeout: 20000 });
  return page;
}
const status = (p) => p.$eval('.status', e => e.innerText);
const disabled = (p, s) => p.$eval(s, e => e.disabled);
const size = (p, label) => p.$$eval('.opt', (bs, l) => bs.find(b => b.innerText === l)?.click(), label);
const opts = (p) => p.$$eval('.opt', bs => bs.map(b => b.innerText + (b.disabled ? '-' : '+')).join(' '));
async function typeEmail(p, text) {
  await p.click('input', { clickCount: 3 });
  await p.keyboard.press('Backspace');
  await p.type('input', text);
}

// happy path
let page = await open(base);
await page.waitForSelector('.opt', { timeout: 5000 });
check('sizes load from the request', (await opts(page)) === 'S+ M+ L+ XL+' && (await status(page)).startsWith(':editing'));
check('Order and Clear disabled on an empty form', await disabled(page, '.order') && await disabled(page, '.clear-size') && await disabled(page, '.clear-email'));
await size(page, 'M');
check('choice: M selected, its own button disabled', (await opts(page)) === 'S+ M- L+ XL+' && (await status(page)).includes('size M'));
await typeEmail(page, 'me@ex');
check('form-field: invalid input shows the error, Order stays disabled',
  (await page.$('.field-error')) !== null && await disabled(page, '.order'));
await page.type('input', 'ample.org');
check('form-field: valid input clears the error, Order enabled',
  (await page.$('.field-error')) === null && !(await disabled(page, '.order')) && (await status(page)).includes('me@example.org'));
await page.click('.order');
check('order in flight: button shows Ordering…', (await page.$eval('.order', e => e.innerText)) === 'Ordering…');
await size(page, 'XL');      // edit while in flight: outputs are handled at the root
check('choice still usable while the order is in flight', (await status(page)).includes('size XL'));
await page.waitForSelector('.done', { timeout: 5000 });
check('order placed with the values at submit time', (await page.$eval('.done', e => e.innerText)) === 'Order 42: size M for me@example.org');
await page.close();

// clearing
page = await open(base);
await page.waitForSelector('.opt', { timeout: 5000 });
await size(page, 'S'); await typeEmail(page, 'me@example.org');
await page.click('.clear-size'); await page.click('.clear-email');
check('clear: both components reset, the parent heard it',
  (await status(page)) === ':editing' && (await page.$eval('input', e => e.value)) === '' && await disabled(page, '.order'));
await page.close();

// failures
page = await open(`${base}?fail=sizes`);
await page.waitForSelector('[data-event=":retry"]', { timeout: 5000 });
check('fail=sizes: no size buttons, Retry offered', (await opts(page)) === '');
await page.click('[data-event=":retry"]');
await page.waitForSelector('.opt', { timeout: 5000 });
check('retry → sizes loaded', (await opts(page)) === 'S+ M+ L+ XL+');
await page.close();

page = await open(`${base}?fail=order`);
await page.waitForSelector('.opt', { timeout: 5000 });
await size(page, 'L'); await typeEmail(page, 'me@example.org');
await page.click('.order');
await page.waitForSelector('.order-error', { timeout: 5000 });
check('fail=order: error shown, back to editing, Order enabled',
  (await status(page)).startsWith(':editing') && !(await disabled(page, '.order')));
await page.click('.order');
await page.waitForSelector('.done', { timeout: 5000 });
check('second order succeeds, error gone', (await page.$('.order-error')) === null);
await page.close();

check('no console errors or warnings', problems.length === 0, problems.slice(0, 3).join(' | '));
await browser.close();
process.exitCode = results.every(Boolean) ? 0 : 1;
