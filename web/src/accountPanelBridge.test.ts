import { readFileSync } from 'node:fs'
import { resolve } from 'node:path'
import { afterEach, describe, expect, it, vi } from 'vitest'
// The workspace uses jsdom at runtime without the optional @types/jsdom package.
// @ts-expect-error Runtime dependency has no declaration package installed here.
import { JSDOM } from 'jsdom'

const panelHtml = readFileSync(resolve(process.cwd(), 'public/hby019-demo/source/account-panel.html'), 'utf8')
const bridgeScript = readFileSync(resolve(process.cwd(), 'public/hby019-demo/source/account-panel-bridge.js'), 'utf8')
const sourcePlayerHtml = readFileSync(resolve(process.cwd(), 'public/hby019-demo/source/player-ui1.html'), 'utf8')
const origin = 'http://xupan.test'

function createPanel() {
  const dom = new JSDOM(panelHtml, { url: `${origin}/hby019-demo/source/account-panel.html`, runScripts: 'outside-only' })
  const postMessage = vi.fn()
  dom.window.postMessage = postMessage
  dom.window.eval(bridgeScript)

  function sendState(payload: unknown, eventOrigin = origin) {
    dom.window.dispatchEvent(new dom.window.MessageEvent('message', {
      origin: eventOrigin,
      source: dom.window,
      data: { channel: 'xupan-hby019-account-panel', type: 'state', payload },
    }))
  }

  return { dom, postMessage, sendState }
}

const state = {
  displayName: '示例玩家',
  username: 'wxid-example',
  avatarUrl: null,
  avatarText: '示',
  todayTurnover: 125,
  todayNetProfit: -25,
  tab: 'pending',
  fontSize: 'small',
  fontBold: false,
  loading: false,
  error: '',
  rows: [{
    id: 'bet-1',
    issue: '1301',
    ball: '第8球',
    text: '08特/100',
    result: '未开奖',
    resultColor: false,
    refunded: false,
    colorIndex: 0,
    expanded: false,
  }],
}

describe('HBY019 account panel bridge', () => {
  let current: ReturnType<typeof createPanel> | undefined

  afterEach(() => {
    current?.dom.window.close()
    current = undefined
  })

  it('keeps the extracted panel DOM identical to the HBY019 UI1 source', () => {
    const sourceDocument = new JSDOM(sourcePlayerHtml).window.document
    const panelDocument = new JSDOM(panelHtml).window.document
    const sourcePanel = [...sourceDocument.querySelectorAll('.uni-popup.right')]
      .find(element => element.querySelector('.font-setting-bar') && element.querySelector('#myDiv'))
    const extractedPanel = panelDocument.querySelector('.uni-popup.right')

    expect(sourcePanel).toBeTruthy()
    expect(extractedPanel?.outerHTML).toBe(sourcePanel?.outerHTML)
  })

  it('preserves HBY019 styles and fonts while leaving the iframe canvas transparent', () => {
    const sourceDocument = new JSDOM(sourcePlayerHtml).window.document
    const panelDocument = new JSDOM(panelHtml).window.document
    const sourceStyles = [...sourceDocument.querySelectorAll('head style')].map(style => style.outerHTML)
    const panelStyles = [...panelDocument.querySelectorAll('head style')]
      .filter(style => style.id !== 'xupan-account-panel-font')
      .map(style => style.outerHTML)

    expect(panelStyles).toEqual(sourceStyles)
    expect(panelDocument.querySelector('#xupan-account-panel-font')?.textContent)
      .toBe('.uni-popup.right,.uni-popup.right *{font-family:"Helvetica Neue", Helvetica, "PingFang SC", "微软雅黑", Tahoma, Arial, sans-serif !important;}')
    expect(panelDocument.documentElement.style.getPropertyValue('background')).toBe('transparent')
    expect(panelDocument.documentElement.style.getPropertyPriority('background')).toBe('important')
  })

  it('renders Xupan state and reports source panel actions to its parent', () => {
    current = createPanel()
    expect(current.postMessage).toHaveBeenCalledWith({
      channel: 'xupan-hby019-account-panel',
      type: 'ready',
      payload: {},
    }, origin)

    current.sendState(state)
    const { document } = current.dom.window
    const root = document.querySelector('.uni-popup.right')!
    expect(root.getAttribute('style')).toContain('display: block')
    expect(document.querySelector('.liushui')?.textContent).toBe('125.00')
    expect(document.querySelector('.yingkui')?.textContent).toBe('-25.00')
    expect(document.querySelector('#myDiv .record-row')?.textContent).toBe('1301第8球08特/100未开奖')

    document.querySelectorAll('.btnClass')[1].dispatchEvent(new current.dom.window.MouseEvent('click', { bubbles: true }))
    document.querySelector('.fs-size[data-size="font_big"]')?.dispatchEvent(new current.dom.window.MouseEvent('click', { bubbles: true }))
    document.querySelector('#myDiv .record-row')?.dispatchEvent(new current.dom.window.MouseEvent('click', { bubbles: true }))
    document.querySelector('[name="mask"]')?.dispatchEvent(new current.dom.window.MouseEvent('click', { bubbles: true }))

    expect(current.postMessage.mock.calls.map(([message]) => message.type)).toEqual([
      'ready', 'tab', 'font-size', 'toggle-row', 'close',
    ])
    expect(current.postMessage.mock.calls[1][0]).toMatchObject({ payload: { value: 'settled' } })
    expect(current.postMessage.mock.calls[2][0]).toMatchObject({ payload: { value: 'big' } })

    current.sendState({ ...state, rows: [{ ...state.rows[0], expanded: true }] })
    expect(document.querySelectorAll('#myDiv .record-row .ellipsis-cell')[0].getAttribute('style')).toContain('white-space: normal')
    expect(document.querySelectorAll('#myDiv .record-row .ellipsis-cell')[1].getAttribute('style')).toContain('white-space: normal')
  })

  it('ignores messages from other origins and malformed state payloads', () => {
    current = createPanel()
    current.sendState(state, 'http://other.test')
    current.sendState({ ...state, todayTurnover: '125' })

    expect(current.dom.window.document.querySelector('.liushui')?.textContent).toBe('0.00')
    expect(current.dom.window.document.querySelector('#myDiv .record-row')).toBeNull()
    expect(current.postMessage).toHaveBeenCalledTimes(1)
  })
})
