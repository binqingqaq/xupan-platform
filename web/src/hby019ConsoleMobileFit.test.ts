import { readFileSync } from 'node:fs'
import { resolve } from 'node:path'
import { describe, expect, it } from 'vitest'
// The workspace uses jsdom at runtime without the optional @types/jsdom package.
// @ts-expect-error Runtime dependency has no declaration package installed here.
import { JSDOM } from 'jsdom'

const consoleHtml = readFileSync(resolve(process.cwd(), 'public/hby019-demo/source/console.html'), 'utf8')
const fitScript = consoleHtml.match(/<script id="console-overview-fit">([\s\S]*?)<\/script>/)?.[1]

function renderFit(viewportWidth: number) {
  const dom = new JSDOM('<!doctype html><html><body></body></html>', { runScripts: 'outside-only' })
  Object.defineProperty(dom.window.document.documentElement, 'clientWidth', { configurable: true, value: viewportWidth })
  dom.window.eval(fitScript ?? '')
  return dom
}

describe('HBY019 console mobile overview fit', () => {
  it('scales the desktop canvas to a narrow viewport and updates after resize', () => {
    const dom = renderFit(390)
    expect(dom.window.document.documentElement.style.zoom).toBe(String(390 / 920))

    Object.defineProperty(dom.window.document.documentElement, 'clientWidth', { configurable: true, value: 430 })
    dom.window.dispatchEvent(new dom.window.Event('resize'))
    expect(dom.window.document.documentElement.style.zoom).toBe(String(430 / 920))
    dom.window.close()
  })

  it('keeps the desktop canvas at its original scale when the viewport is wide enough', () => {
    const dom = renderFit(1280)
    expect(dom.window.document.documentElement.style.zoom).toBe('1')
    dom.window.close()
  })
})
