import { createRequire } from 'node:module'

const require = createRequire(import.meta.url)
const { chromium } = require('playwright-core')
const browser = await chromium.launch({ executablePath: 'C:/Program Files/Google/Chrome/Application/chrome.exe', headless: true })
const scenarios = [
  {
    name: 'portal-nonempty-submit',
    source: 'http://127.0.0.1:5175/portal.html',
    demo: 'http://127.0.0.1:5173/hby019-demo/portal',
    run: async root => {
      await root.locator('#pwd').fill('demo-pass')
      await root.locator('#btn3').click()
      return { message: await root.locator('#local-portal-note').innerText(), value: await root.locator('#pwd').inputValue() }
    },
  },
  {
    name: 'console-player-blacklist',
    source: 'http://127.0.0.1:5175/console.html',
    demo: 'http://127.0.0.1:5173/hby019-demo/console',
    run: async (root, page) => {
      await root.locator('#btnShowPu').click()
      const firstPlayer = root.locator('.jifenleftlist > div').first()
      await firstPlayer.waitFor({ state: 'visible' })
      await firstPlayer.click()
      const button = root.locator('.setblack.myShow:visible').first()
      await button.waitFor({ state: 'visible' })
      const player = await root.locator('.jifenright #NickName').inputValue()
      await button.click()
      const confirm = root.locator('.layui-layer-dialog:visible').last()
      await confirm.waitFor({ state: 'visible' })
      const prompt = (await confirm.innerText()).replace(/\s+/g, ' ').trim()
      await confirm.locator('.layui-layer-btn0').click()
      await page.waitForTimeout(350)
      await root.locator('.jifenright .setblack.myShow:visible').waitFor({ state: 'visible' })
      return { player, prompt, selected: await firstPlayer.evaluate(node => node.className), button: await root.locator('.setblack.myShow:visible').first().innerText() }
    },
  },
  {
    name: 'console-player-rename-history',
    source: 'http://127.0.0.1:5175/console.html',
    demo: 'http://127.0.0.1:5173/hby019-demo/console',
    run: async root => {
      await root.locator('#btnShowPu').click()
      const firstPlayer = root.locator('.jifenleftlist > div').first()
      await firstPlayer.waitFor({ state: 'visible' })
      await firstPlayer.click()
      await root.locator('.nicklog-btn:visible').click()
      const dialog = root.locator('.layui-layer:visible').last()
      await dialog.waitFor({ state: 'visible' })
      await new Promise(resolve => setTimeout(resolve, 400))
      const title = await dialog.locator('.layui-layer-title').innerText()
      const text = (await dialog.innerText()).replace(/\s+/g, ' ').trim()
      const rect = await dialog.boundingBox()
      const viewport = await dialog.evaluate(node => [node.ownerDocument.defaultView.innerWidth, node.ownerDocument.defaultView.innerHeight])
      const computed = await dialog.evaluate(node => ({ width: getComputedStyle(node).width, height: getComputedStyle(node).height, transform: getComputedStyle(node).transform }))
      return { title, hasEmptyState: text.includes('暂无换名记录'), rect: rect && [rect.x, rect.y, rect.width, rect.height].map(Math.round), viewport, computed }
    },
  },
  {
    name: 'console-score-log-player-tuo-tabs-and-close',
    source: 'http://127.0.0.1:5175/console.html',
    demo: 'http://127.0.0.1:5173/hby019-demo/console',
    run: async root => {
      await root.locator('#scoreLogTabs .scoreLogTab[data-target="tuo"]').click()
      const selectedTab = await root.locator('#scoreLogTabs .activeScoreTab').getAttribute('data-target')
      await root.locator('#scoreLogTabs .scoreLogTab[data-target="player"]').click()
      await root.locator('#jifen').click()
      await root.locator('.jifenKuang').waitFor({ state: 'visible' })
      const dateCount = await root.locator('.jifenKuang .dates span').count()
      const selectedDate = await root.locator('.jifenKuang .dates span.sele').first().innerText()
      await root.locator('.jifenKuang .closeKuang').first().click()
      await root.locator('.jifenKuang').waitFor({ state: 'hidden' })
      return { selectedTab, dateCount, selectedDate, closed: true }
    },
  },
  {
    name: 'player-history-popup-and-mask-close',
    source: 'http://127.0.0.1:5175/player.html?uid=local-demo&ui=4',
    demo: 'http://127.0.0.1:5173/hby019-demo/player.html?uid=local-demo&ui=4',
    run: async (root, page) => {
      await root.locator('#history').click()
      const popup = root.locator('.uni-popup.top')
      await popup.waitFor({ state: 'visible' })
      await page.waitForTimeout(350)
      const state = await popup.evaluate(node => ({
        rect: (() => { const r = node.getBoundingClientRect(); return [r.x, r.y, r.width, r.height].map(Math.round) })(),
        rows: node.querySelectorAll('.historyList > div').length,
        firstRow: node.querySelector('.historyList > div')?.innerText.trim() || '',
      }))
      await root.locator('.uni-popup.top [name="mask"]').evaluate(node => node.click())
      return { ...state, staysOpenAfterMaskClick: await popup.isVisible() }
    },
  },
  {
    name: 'player-font-size-setting',
    source: 'http://127.0.0.1:5175/player.html?uid=local-demo&ui=4',
    demo: 'http://127.0.0.1:5173/hby019-demo/player.html?uid=local-demo&ui=4',
    run: async root => {
      await root.locator('.uInfo').first().click()
      await root.locator('.uni-popup.right').waitFor({ state: 'visible' })
      await root.locator('.fs-size[data-size="font_mini"]').click()
      const state = await root.locator('body').evaluate(node => ({
        className: node.className,
        size: localStorage.getItem('chat_font_size'),
        active: document.querySelector('.fs-size.fs-active')?.dataset.size,
      }))
      await root.locator('.uni-popup.right [name="mask"]').evaluate(node => node.click())
      return { ...state, staysOpenAfterMaskClick: await root.locator('.uni-popup.right').isVisible() }
    },
  },
  {
    name: 'player-settings-dialog',
    source: 'http://127.0.0.1:5175/player.html?uid=local-demo&ui=4',
    demo: 'http://127.0.0.1:5173/hby019-demo/player.html?uid=local-demo&ui=4',
    run: async root => {
      await root.locator('.kuaijie').filter({ hasText: '快捷' }).click()
      await root.locator('.shezhi').click()
      const dialog = root.locator('.layui-layer:visible').last()
      await dialog.waitFor({ state: 'visible' })
      await new Promise(resolve => setTimeout(resolve, 400))
      const state = {
        title: await dialog.locator('.layui-layer-title').innerText(),
        rect: await dialog.boundingBox().then(rect => rect && [rect.x, rect.y, rect.width, rect.height].map(Math.round)),
      }
      await root.locator('.layui-layer-shade:visible').last().evaluate(node => node.click())
      await dialog.waitFor({ state: 'hidden' })
      return { ...state, closedByShade: true }
    },
  },
  {
    name: 'player-keyboard-input-delete',
    source: 'http://127.0.0.1:5175/player.html?uid=local-demo&ui=4',
    demo: 'http://127.0.0.1:5173/hby019-demo/player.html?uid=local-demo&ui=4',
    run: async root => {
      const input = root.locator('#txtMsg')
      await input.fill('')
      await root.locator('.divBottom .vkb').click()
      await root.locator('.columns button').filter({ hasText: /^1$/ }).first().click()
      const afterDigit = await input.inputValue()
      await root.locator('.columns button').filter({ hasText: /^⇦$/ }).first().click()
      const afterDelete = await input.inputValue()
      return { afterDigit, afterDelete }
    },
  },
  {
    name: 'player-quick-bet-toggle-reset',
    source: 'http://127.0.0.1:5175/player.html?uid=local-demo&ui=4',
    demo: 'http://127.0.0.1:5173/hby019-demo/player.html?uid=local-demo&ui=4',
    run: async root => {
      await root.locator('.kuaijie').filter({ hasText: '快捷' }).click()
      const group = root.locator('.showKj .item-container').first()
      const selection = root.locator('.showKj .bet-button').first()
      await group.click()
      await selection.click()
      const groupSelectedBeforeSubmit = await group.evaluate(node => node.className)
      const selectionSelectedBeforeSubmit = await selection.evaluate(node => node.className)
      const amount = root.locator('.showKj .je')
      await amount.fill('5')
      await amount.press('End')
      const generatedTextBeforeSubmit = await root.locator('#txtMsg').inputValue()
      await root.locator('.showKj .kuaisu').click()
      return {
        groupSelectedAfterSubmit: await group.evaluate(node => node.classList.contains('checked')),
        selectionSelectedAfterSubmit: await selection.evaluate(node => node.classList.contains('checked')),
        groupSelectedBeforeSubmit,
        selectionSelectedBeforeSubmit,
        generatedTextBeforeSubmit,
        inputClearedAfterSubmit: (await root.locator('#txtMsg').inputValue()) === '',
        panelClosedAfterSubmit: !(await root.locator('.showKj').isVisible()),
      }
    },
  },
  {
    name: 'player-ui4-local-chat-submit',
    source: 'http://127.0.0.1:5175/player.html?uid=local-demo&ui=4',
    demo: 'http://127.0.0.1:5173/hby019-demo/player.html?uid=local-demo&ui=4',
    run: async root => {
      const input = root.locator('#txtMsg')
      await input.fill('验收消息')
      await root.locator('.divBottom .send').click()
      const bubble = root.locator('.msgcontent .msgright').last()
      await bubble.locator('.local-only-status').waitFor({ state: 'visible' })
      return {
        inputCleared: (await input.inputValue()) === '',
        message: await bubble.locator('.msgpre').innerText(),
        localStatus: await bubble.locator('.local-only-status').innerText(),
      }
    },
  },
  {
    name: 'player-ui1-history-popup',
    source: 'http://127.0.0.1:5175/player-ui1.html?uid=local-demo&ui=1',
    demo: 'http://127.0.0.1:5173/hby019-demo/player-ui1.html?uid=local-demo&ui=1',
    run: async (root, page) => {
      await root.locator('#history').click()
      const popup = root.locator('.uni-popup.top')
      await popup.waitFor({ state: 'visible' })
      await page.waitForTimeout(350)
      return popup.evaluate(node => ({
        rect: (() => { const r = node.getBoundingClientRect(); return [r.x, r.y, r.width, r.height].map(Math.round) })(),
        rows: node.querySelectorAll('.historyList > div').length,
        firstRow: node.querySelector('.historyList > div')?.innerText.trim() || '',
      }))
    },
  },
  {
    name: 'player-ui1-font-size-sidebar',
    source: 'http://127.0.0.1:5175/player-ui1.html?uid=local-demo&ui=1',
    demo: 'http://127.0.0.1:5173/hby019-demo/player-ui1.html?uid=local-demo&ui=1',
    run: async root => {
      await root.locator('.uInfo').first().click()
      await root.locator('.uni-popup.right').waitFor({ state: 'visible' })
      await root.locator('.fs-size[data-size="font_big"]').click()
      return root.locator('body').evaluate(node => ({
        className: node.className,
        size: localStorage.getItem('chat_font_size'),
        active: document.querySelector('.fs-size.fs-active')?.dataset.size,
      }))
    },
  },
  {
    name: 'player-ui1-keyboard-input-delete',
    source: 'http://127.0.0.1:5175/player-ui1.html?uid=local-demo&ui=1',
    demo: 'http://127.0.0.1:5173/hby019-demo/player-ui1.html?uid=local-demo&ui=1',
    run: async root => {
      const input = root.locator('#txtMsg')
      await input.fill('')
      await root.locator('.divBottom .vkb').click()
      await root.locator('.inpvkb li').filter({ hasText: /^1$/ }).first().click()
      const afterDigit = await input.inputValue()
      await root.locator('.inpvkb li.del').first().click()
      return { afterDigit, afterDelete: await input.inputValue() }
    },
  },
  {
    name: 'player-ui1-settings-dialog',
    source: 'http://127.0.0.1:5175/player-ui1.html?uid=local-demo&ui=1',
    demo: 'http://127.0.0.1:5173/hby019-demo/player-ui1.html?uid=local-demo&ui=1',
    run: async root => {
      await root.locator('.kuaijie').filter({ hasText: '快捷' }).click()
      await root.locator('.shezhi').click()
      const dialog = root.locator('.layui-layer:visible').last()
      await dialog.waitFor({ state: 'visible' })
      await new Promise(resolve => setTimeout(resolve, 400))
      return {
        title: await dialog.locator('.layui-layer-title').innerText(),
        rect: await dialog.boundingBox().then(rect => rect && [rect.x, rect.y, rect.width, rect.height].map(Math.round)),
      }
    },
  },
  {
    name: 'player-ui1-local-chat-submit',
    source: 'http://127.0.0.1:5175/player-ui1.html?uid=local-demo&ui=1',
    demo: 'http://127.0.0.1:5173/hby019-demo/player-ui1.html?uid=local-demo&ui=1',
    run: async root => {
      const input = root.locator('#txtMsg')
      await input.fill('验收消息')
      await root.locator('.divBottom .send').click()
      const bubble = root.locator('.msgcontent .msgright').last()
      await bubble.locator('.local-only-status').waitFor({ state: 'visible' })
      return {
        inputCleared: (await input.inputValue()) === '',
        message: await bubble.locator('.msgpre').innerText(),
        localStatus: await bubble.locator('.local-only-status').innerText(),
      }
    },
  },
]

const errors = []
const unexpectedRequests = []
const reports = []
const comparePng = async (page, first, second) => page.evaluate(async ([left, right]) => {
  const load = data => new Promise((resolve, reject) => {
    const image = new Image()
    image.onload = () => resolve(image)
    image.onerror = reject
    image.src = `data:image/png;base64,${data}`
  })
  const [a, b] = await Promise.all([load(left), load(right)])
  if (a.width !== b.width || a.height !== b.height) return { sameDimensions: false, a: [a.width, a.height], b: [b.width, b.height] }
  const canvas = document.createElement('canvas')
  canvas.width = a.width
  canvas.height = a.height
  const context = canvas.getContext('2d')
  context.drawImage(a, 0, 0)
  const one = context.getImageData(0, 0, a.width, a.height).data
  context.clearRect(0, 0, canvas.width, canvas.height)
  context.drawImage(b, 0, 0)
  const two = context.getImageData(0, 0, b.width, b.height).data
  let changed = 0
  for (let i = 0; i < one.length; i += 4) {
    if (one[i] !== two[i] || one[i + 1] !== two[i + 1] || one[i + 2] !== two[i + 2] || one[i + 3] !== two[i + 3]) changed++
  }
  return { sameDimensions: true, changedPixels: changed, percent: Number((changed * 100 / (a.width * a.height)).toFixed(4)) }
}, [first.toString('base64'), second.toString('base64')])
for (const scenario of scenarios.filter(item => !process.argv[2] || item.name.includes(process.argv[2]))) {
  const runPage = async (url, demo, referenceScreenshot) => {
    const context = await browser.newContext({ viewport: { width: 1365, height: 900 }, deviceScaleFactor: 1 })
    const page = await context.newPage()
    page.setDefaultTimeout(7000)
    page.on('pageerror', error => errors.push(`${scenario.name}: ${error.message}`))
    page.on('request', request => {
      const parsed = new URL(request.url())
      if (!['127.0.0.1', 'localhost'].includes(parsed.hostname) || /^\/api(?:\/|$)/.test(parsed.pathname) || ['ws:', 'wss:'].includes(parsed.protocol)) {
        unexpectedRequests.push(`${scenario.name}: ${parsed.origin}${parsed.pathname}`)
      }
    })
    await page.goto(url, { waitUntil: 'domcontentloaded' })
    await page.waitForTimeout(700)
    const root = demo ? page.frameLocator('iframe') : page
    await root.locator('body').waitFor({ state: 'visible' })
    const state = await scenario.run(root, page)
    const screenshot = await page.screenshot()
    const visualDiff = demo && referenceScreenshot ? await comparePng(page, referenceScreenshot, screenshot) : undefined
    await context.close()
    return { state, screenshot, visualDiff }
  }
  try {
    process.stderr.write(`Running ${scenario.name}\n`)
    const source = await runPage(scenario.source, false)
    const demo = await runPage(scenario.demo, true, source.screenshot)
    reports.push({
      name: scenario.name,
      sameState: JSON.stringify(source.state) === JSON.stringify(demo.state),
      source: source.state,
      demo: demo.state,
      visualDiff: demo.visualDiff,
    })
  } catch (error) {
    errors.push(`${scenario.name}: ${error.stack || error.message}`)
  }
}
await browser.close()
const result = { reports, errors, unexpectedRequests: [...new Set(unexpectedRequests)] }
process.stdout.write(JSON.stringify(result, null, 2))
if (result.errors.length || result.unexpectedRequests.length || result.reports.some(report => !report.sameState)) process.exitCode = 1
