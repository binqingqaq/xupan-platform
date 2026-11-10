import { readFileSync } from 'node:fs'
import { resolve } from 'node:path'
import { describe, expect, it } from 'vitest'

const pageSource = readFileSync(resolve(process.cwd(), 'public/display/mobile-replica/official/page.js'), 'utf8')

describe('Macau four-minute display clock', () => {
  it('converts local time to server time for positive and negative clock offsets', () => {
    const helperSource = pageSource.match(/function macauFourMinuteServerNow\(localNow, offsetMs\) \{[\s\S]*?\n\}/)?.[0]
    expect(helperSource).toBeTruthy()
    const serverNow = new Function(`return (${helperSource})`)() as (localNow: number, offsetMs: number) => number

    expect(serverNow(20_000, 5_000)).toBe(15_000)
    expect(serverNow(10_000, -5_000)).toBe(15_000)
  })

  it('uses the dedicated Macau endpoint and never mirrors the Happy 8 result into its card', () => {
    expect(pageSource).toContain("fetch('/api/display/mobile/macau-four-minute'")
    expect(pageSource).not.toContain('syncMacauFourMinutePlaceholder')
    expect(pageSource).toContain('暂无澳门四分彩历史开奖')
  })

  it('keeps rendered numbers legible after removing the copied Happy 8 ball colors', () => {
    expect(pageSource).toContain("ballArea.style.color = '#333'")
  })
})
