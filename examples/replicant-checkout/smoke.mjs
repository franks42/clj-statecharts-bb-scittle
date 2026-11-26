// Headless smoke test of examples/replicant-checkout in real Scittle + Replicant.
import puppeteer from 'puppeteer-core';

const url = process.argv[2];
const browser = await puppeteer.launch({ executablePath: process.env.CHROME ?? '/Applications/Google Chrome.app/Contents/MacOS/Google Chrome', args: ['--no-sandbox'] });
const page = await browser.newPage();
const problems = [];
page.on('console', m => { if (['error', 'warn', 'warning'].includes(m.type())) problems.push(`${m.type()}: ${m.text()}`); });
page.on('pageerror', e => problems.push(`pageerror: ${e.message}`));

const btn = (ev) => `[data-event="${ev}"]`;
const disabled = (sel) => page.$eval(sel, el => el.disabled);
const text = () => page.$eval('#app', el => el.innerText);
const results = [];
const check = (name, ok, detail = '') => { results.push({ name, ok }); console.log(`${ok ? 'PASS' : 'FAIL'} ${name}${detail ? ' — ' + detail : ''}`); };

await page.goto(url);
await page.waitForSelector(btn(':checkout/start'), { timeout: 15000 });

check('Checkout disabled with empty basket (guard)', await disabled(btn(':checkout/start')));
await page.click('.seats button:nth-child(2)');              // A18
await page.waitForSelector('.seats button.chosen');
check('seat toggles on', (await page.$$('.seats button.chosen')).length === 1);
check('Checkout enabled once a seat is chosen', !(await disabled(btn(':checkout/start'))));

await page.click(btn(':checkout/start'));
await page.waitForSelector('input[type=email]');
check('Pay disabled without valid email (guard)', await disabled(btn(':pay/submit')));
await page.type('input[type=email]', 'frank@example.org');    // :input events → :event.target/value
check('Pay enabled after typing email (placeholder interpolation)', !(await disabled(btn(':pay/submit'))));

await page.click(btn(':pay/submit'));
await page.waitForFunction(() => document.querySelector('#app').innerText.includes('Charging'));
check('no Cancel while charging (chart has no transition)', (await page.$(btn(':checkout/cancel'))) === null);
await page.waitForFunction(() => document.querySelector('#app').innerText.includes('Booked'), { timeout: 5000 });
check('effect dispatched :pay/ok → done', (await text()).includes('A18'));

// decline path
await page.click(btn(':order/new'));
await page.waitForSelector('.seats');
await page.click('.seats button:nth-child(5)');              // B2 → fake charge declines
await page.click(btn(':checkout/start'));
await page.waitForSelector('input[type=email]');
await page.type('input[type=email]', 'frank@example.org');
await page.click(btn(':pay/submit'));
await page.waitForSelector('.error', { timeout: 5000 });
check('decline → error shown with Try again', (await page.$(btn(':pay/retry'))) !== null, await page.$eval('.error', e => e.innerText));
await page.click(btn(':checkout/cancel'));
await page.waitForSelector('.seats');
check('cancel from declined → browsing', true);

check('no console errors or warnings', problems.length === 0, problems.join(' | '));
await browser.close();
process.exitCode = results.every(r => r.ok) ? 0 : 1;
