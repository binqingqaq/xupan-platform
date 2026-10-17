import { createRequire } from 'node:module'

const require = createRequire(import.meta.url)
const { chromium } = require('playwright-core')
const browser = await chromium.launch({ executablePath: 'C:/Program Files/Google/Chrome/Application/chrome.exe', headless: true })
const routes = [
  { name: 'login', source: 'http://127.0.0.1:5175/', demo: 'http://127.0.0.1:5173/hby019-demo/login' },
  { name: 'console', source: 'http://127.0.0.1:5175/console.html', demo: 'http://127.0.0.1:5173/hby019-demo/console' },
  { name: 'portal', source: 'http://127.0.0.1:5175/portal.html', demo: 'http://127.0.0.1:5173/hby019-demo/portal' },
  { name: 'details', source: 'http://127.0.0.1:5175/showdetails.html', demo: 'http://127.0.0.1:5173/hby019-demo/source/showdetails.html' },
  { name: 'player-ui4', source: 'http://127.0.0.1:5175/player.html?uid=local-demo&ui=4', demo: 'http://127.0.0.1:5173/hby019-demo/player.html?uid=local-demo&ui=4' },
  { name: 'player-ui1', source: 'http://127.0.0.1:5175/player-ui1.html?uid=local-demo&ui=1', demo: 'http://127.0.0.1:5173/hby019-demo/player-ui1.html?uid=local-demo&ui=1' },
]
const failures = []
const errors = []
const unexpectedRequests = []
const observeRequests = (page, label) => {
  page.on('request', request => {
    const url = new URL(request.url())
    const remoteHost = ['http:', 'https:', 'ws:', 'wss:'].includes(url.protocol) && !['127.0.0.1', 'localhost'].includes(url.hostname)
    const backendRoute = /^\/api(?:\/|$)/.test(url.pathname) || ['ws:', 'wss:'].includes(url.protocol)
    if (remoteHost || backendRoute) {
      unexpectedRequests.push(`${label}: ${url.origin}${url.pathname}`)
    }
  })
}
const comparePng = async (page, a, b) => page.evaluate(async ([left, right]) => {
  const load = data => new Promise((resolve, reject) => { const image = new Image(); image.onload = () => resolve(image); image.onerror = reject; image.src = `data:image/png;base64,${data}` })
  const [one, two] = await Promise.all([load(left), load(right)])
  const canvas = document.createElement('canvas'); canvas.width = one.width; canvas.height = one.height
  const context = canvas.getContext('2d'); context.drawImage(one, 0, 0); const first = context.getImageData(0, 0, one.width, one.height).data
  context.clearRect(0, 0, one.width, one.height); context.drawImage(two, 0, 0); const second = context.getImageData(0, 0, two.width, two.height).data
  let changed = 0; let minX = one.width; let minY = one.height; let maxX = 0; let maxY = 0
  for (let i = 0; i < first.length; i += 4) if (first[i] !== second[i] || first[i + 1] !== second[i + 1] || first[i + 2] !== second[i + 2] || first[i + 3] !== second[i + 3]) { const pixel = i / 4; const x = pixel % one.width; const y = Math.floor(pixel / one.width); changed++; minX = Math.min(minX, x); minY = Math.min(minY, y); maxX = Math.max(maxX, x); maxY = Math.max(maxY, y) }
  return { percent: Number((changed * 100 / (one.width * one.height)).toFixed(4)), changed, bbox: changed ? [minX, minY, maxX, maxY] : null }
}, [a.toString('base64'), b.toString('base64')])
const reports = []
for (const viewport of [
  { width: 1365, height: 900 },
  { width: 1011, height: 900 },
  { width: 390, height: 844 },
]) {
  for (const route of routes) {
    const context = await browser.newContext({ viewport, deviceScaleFactor: 1 })
    const page = await context.newPage()
    observeRequests(page, `${route.name}:${viewport.width}`)
    page.on('pageerror', error => errors.push(`${route.name}: ${error.message}`))
    page.on('requestfailed', request => {
      if (!/(?:861|886)\.mp3$/.test(request.url())) failures.push(`${route.name}: ${request.url()} ${request.failure()?.errorText || ''}`)
    })
    page.on('response', response => {
      if (response.status() >= 400 && !/(?:861|886)\.mp3$/.test(response.url())) failures.push(`${route.name}: HTTP ${response.status()} ${response.url()}`)
    })
    await page.goto(route.source, { waitUntil: 'domcontentloaded' }); await page.waitForTimeout(500)
    const sourcePng = await page.screenshot()
    await page.goto(route.demo, { waitUntil: 'domcontentloaded' }); await page.waitForTimeout(500)
    const demoPng = await page.screenshot()
    reports.push({ viewport: `${viewport.width}x${viewport.height}`, route: route.name, initialDiffPercent: await comparePng(page, sourcePng, demoPng) })
    await context.close()
  }
}
const interactionCases = [
  { name: 'login-to-console', route: routes[0], selector: '#btnLogin', state: 'body', action: async (root, page) => { await root.locator('#btnLogin').click(); await page.waitForURL(url => url.pathname.endsWith('/console.html') || url.pathname.endsWith('/console')); return { destination: 'console' } } },
  { name: 'login-to-portal', route: routes[0], selector: '#btnLogin2', state: 'body', action: async (root, page) => { await root.locator('#btnLogin2').click(); await page.waitForURL(url => url.pathname.endsWith('/portal.html') || url.pathname.endsWith('/portal')); return { destination: 'portal' } } },
  { name: 'portal-demo-submit', route: routes[2], selector: '#btn3', state: 'body', action: async root => { await root.locator('#btn3').click(); return root.locator('#local-portal-note').innerText() } },
  { name: 'console-logout', route: routes[1], selector: '#ExitLink', state: 'body', action: async (root, page) => { await root.locator('#ExitLink').click(); await page.waitForURL(url => url.pathname === '/' || url.pathname.endsWith('/hby019-demo/login')); return { destination: 'login' } } },
  { name: 'console-limit-dialog', route: routes[1], selector: '#changexe', state: '.peilvKuang', action: async root => { await root.locator('#changexe').click(); return root.locator('.peilvKuang').evaluate(node => ({ display: getComputedStyle(node).display, title: node.querySelector('.el-dialog__title')?.textContent?.trim(), rect: (() => { const r = node.querySelector('.el-dialog').getBoundingClientRect(); return [Math.round(r.x), Math.round(r.y), Math.round(r.width), Math.round(r.height)] })() })) } },
  { name: 'console-score-log', route: routes[1], selector: '#jifen', state: '.jifenKuang', action: async root => { await root.locator('#jifen').click(); await root.locator('.jifenKuang').waitFor({ state: 'visible' }); return root.locator('.jifenKuang').evaluate(node => ({ display: getComputedStyle(node).display, title: node.querySelector('.el-dialog__title')?.textContent?.trim(), rect: (() => { const r = node.querySelector('.el-dialog').getBoundingClientRect(); return [Math.round(r.x), Math.round(r.y), Math.round(r.width), Math.round(r.height)] })() })) } },
  { name: 'console-trust-score-log', route: routes[1], selector: '#jifenTuo', state: '.tuoJifenKuang', action: async root => { await root.locator('#jifenTuo').click(); await root.locator('.tuoJifenKuang').waitFor({ state: 'visible' }); return root.locator('.tuoJifenKuang').evaluate(node => ({ display: getComputedStyle(node).display, title: node.querySelector('.el-dialog__title')?.textContent?.trim(), rect: (() => { const r = node.querySelector('.el-dialog').getBoundingClientRect(); return [Math.round(r.x), Math.round(r.y), Math.round(r.width), Math.round(r.height)] })() })) } },
  { name: 'console-password-dialog', route: routes[1], selector: '.topheadmenu span', state: '.changePwd', action: async root => { await root.locator('.topheadmenu span').filter({ hasText: '修改密码' }).first().click(); return root.locator('.changePwd').evaluate(node => ({ display: getComputedStyle(node).display, title: node.querySelector('.el-dialog__title')?.textContent?.trim() })) } },
  { name: 'console-command-trust-tab', route: routes[1], selector: '#btnShowTuoCmd', state: '#btnShowTuoCmd', action: async root => { await root.locator('#btnShowTuoCmd').click(); return root.locator('#btnShowTuoCmd').evaluate(node => ({ selected: node.classList.contains('selBtn'), text: node.innerText.trim() })) } },
  { name: 'console-trust-tab', route: routes[1], selector: '#btnShowTuo', state: '#btnShowTuo', action: async root => { await root.locator('#btnShowTuo').click(); return root.locator('#btnShowTuo').evaluate(node => ({ selected: node.classList.contains('selBtn'), text: node.innerText.trim() })) } },
  { name: 'player-shortcut-panel', route: routes[4], selector: '.kuaijie', state: '.showKj', action: async root => { await root.locator('.kuaijie').filter({ hasText: '快捷' }).click(); return root.locator('.showKj').evaluate(node => ({ display: getComputedStyle(node).display, panels: [...node.children].map(panel => { const r = panel.getBoundingClientRect(); return [Math.round(r.x), Math.round(r.y), Math.round(r.width), Math.round(r.height)] }), text: node.innerText.trim().slice(0, 40) })) } },
  { name: 'player-ledger-panel', route: routes[4], selector: '.uInfo', state: '.uni-popup.right', action: async root => { await root.locator('.uInfo').first().click(); await root.locator('.uni-popup.right').waitFor({ state: 'visible' }); return root.locator('.uni-popup.right').evaluate(node => ({ display: getComputedStyle(node).display, rect: (() => { const r = node.getBoundingClientRect(); return [Math.round(r.x), Math.round(r.y), Math.round(r.width), Math.round(r.height)] })() })) } },
  { name: 'player-trend-unavailable-view', route: routes[4], selector: '.zoushi', state: '.zoushitu', action: async root => { await root.locator('.zoushi').first().click(); await root.locator('.zoushitu').waitFor({ state: 'visible' }); await root.locator('.zoushitu iframe').waitFor({ state: 'attached' }); await root.locator('.zoushitu iframe').evaluate(iframe => new Promise(resolve => { if (iframe.contentDocument?.readyState === 'complete') resolve(); else iframe.addEventListener('load', resolve, { once: true }) })); return root.locator('.zoushitu').evaluate(node => ({ display: getComputedStyle(node).display, src: node.querySelector('iframe').getAttribute('src'), text: node.querySelector('iframe').contentDocument?.body.innerText.trim() })) } },
  { name: 'player-keyboard-toggle', route: routes[4], selector: '.divBottom .vkb', state: '.divBottom', action: async root => { await root.locator('.divBottom .vkb').click(); return root.locator('.divBottom').evaluate(node => ({ className: node.className, visibleButtons: [...node.querySelectorAll('.columns button')].filter(button => getComputedStyle(button).display !== 'none').length, visibleKeys: [...node.querySelectorAll('.columns button')].filter(button => getComputedStyle(button).display !== 'none').map(button => button.innerText.trim()) })) } },
  { name: 'player-format-switch-ui4-to-ui1', route: routes[4], selector: '#uiSwitchBtn', state: 'body', action: async (root, page) => { await root.locator('#uiSwitchBtn').click(); await root.locator('.ui-switch-item[data-ui="1"]').click(); await page.waitForURL(url => url.pathname.endsWith('player-ui1.html')); await root.locator('#uiSwitchBtn').waitFor({ state: 'visible' }); await page.waitForTimeout(250); return root.locator('body').evaluate(node => ({ url: location.pathname.split('/').pop() + location.search, title: document.title, menuDisplay: getComputedStyle(document.querySelector('#uiSwitchMenu')).display })) } },
  { name: 'player-format-switch-ui1-to-ui4', route: routes[5], selector: '#uiSwitchBtn', state: 'body', action: async (root, page) => { await root.locator('#uiSwitchBtn').click(); await root.locator('.ui-switch-item[data-ui="4"]').click(); await page.waitForURL(url => url.pathname.endsWith('player.html')); await root.locator('#uiSwitchBtn').waitFor({ state: 'visible' }); await page.waitForTimeout(250); return root.locator('body').evaluate(node => ({ url: location.pathname.split('/').pop() + location.search, title: document.title, menuDisplay: getComputedStyle(document.querySelector('#uiSwitchMenu')).display })) } },
]
for (const scenario of interactionCases.filter(item => item.name !== 'player-trend-unavailable-view')) {
  const context = await browser.newContext({ viewport: { width: 1365, height: 900 }, deviceScaleFactor: 1 })
  const page = await context.newPage()
  observeRequests(page, scenario.name)
  page.on('pageerror', error => errors.push(`${scenario.name}: ${error.message}`))
  const run = async (url, isDemo) => {
    await page.goto(url, { waitUntil: 'domcontentloaded' }); await page.waitForTimeout(700)
    const root = isDemo ? page.frameLocator('iframe') : page
    await root.locator(scenario.selector).first().waitFor({ state: 'visible' })
    const state = await scenario.action(root, page)
    await page.waitForTimeout(500)
    const screenshot = await page.screenshot()
    const afterScreenshot = await root.locator(scenario.state).evaluate(node => { const rect = node.getBoundingClientRect(); const msg = document.querySelector('.msgcontent'); return { box: [Math.round(rect.x), Math.round(rect.y), Math.round(rect.width), Math.round(rect.height)], msg: msg ? { height: Math.round(msg.getBoundingClientRect().height), scrollTop: msg.scrollTop } : null, bodyHeight: document.body.scrollHeight, clientHeight: document.documentElement.clientHeight, clocks: [...document.querySelectorAll('body *')].filter(el => !el.children.length && /^\d{2}:\d{2}$/.test(el.textContent.trim())).map(el => ({ text: el.textContent.trim(), id: el.id, className: el.className, rect: (() => { const r = el.getBoundingClientRect(); return [Math.round(r.x), Math.round(r.y)] })() })).slice(0, 5) } })
    return { state, afterScreenshot, screenshot }
  }
  const source = await run(scenario.route.source, false)
  const demo = await run(scenario.route.demo, true)
  reports.push({ route: scenario.name, sourceState: source.state, sourceAfterScreenshot: source.afterScreenshot, demoState: demo.state, demoAfterScreenshot: demo.afterScreenshot, interactionDiffPercent: await comparePng(page, source.screenshot, demo.screenshot) })
  await context.close()
}
await browser.close()
const visualReports = reports.filter(report => report.viewport)
const interactionReports = reports.filter(report => report.sourceState !== undefined)
const normalizedState = state => {
  if (!state || typeof state !== 'object' || Array.isArray(state)) return state
  const { frameRect, ...rest } = state
  return rest
}
const result = {
  visualReports,
  interactionReports: interactionReports.map(report => ({
    name: report.route,
    sourceState: report.sourceState,
    demoState: report.demoState,
    sameState: JSON.stringify(normalizedState(report.sourceState)) === JSON.stringify(normalizedState(report.demoState)),
    interactionDiffPercent: report.interactionDiffPercent,
  })),
  errors: [...new Set(errors)],
  failures: [...new Set(failures)],
  unexpectedRequests: [...new Set(unexpectedRequests)],
}
process.stdout.write(JSON.stringify(result, null, 2))
if (result.errors.length || result.failures.length || result.unexpectedRequests.length) process.exitCode = 1
