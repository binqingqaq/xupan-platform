import { copyFile, mkdir, readFile, writeFile } from 'node:fs/promises'
import { createRequire } from 'node:module'
import { dirname, resolve } from 'node:path'
import { fileURLToPath } from 'node:url'

const require = createRequire(import.meta.url)
const { JSDOM, VirtualConsole } = require('jsdom')
const scriptDir = dirname(fileURLToPath(import.meta.url))
const displayDir = resolve(scriptDir, '../public/display/mobile-replica')
const backupDir = resolve(scriptDir, '../backups/display-mobile-original-2026-11-07')
const indexPath = resolve(displayDir, 'index.html')
const index = await readFile(indexPath, 'utf8')
const virtualConsole = new VirtualConsole()
const parsed = new JSDOM(index, { includeNodeLocations: true, virtualConsole })
const selectors = ['#widgetBox40585', '#allsite', '#guilvsite']
const removals = selectors.map((selector) => {
  const element = parsed.window.document.querySelector(selector)
  const location = element && parsed.nodeLocation(element)
  if (!location) throw new Error(`Missing source block: ${selector}`)
  return { selector, start: location.startOffset, end: location.endOffset }
})

await mkdir(dirname(backupDir), { recursive: true })
await mkdir(backupDir, { recursive: false })
await copyFile(indexPath, resolve(backupDir, 'index.html'))
await copyFile(resolve(displayDir, 'middle-content.html'), resolve(backupDir, 'middle-content.html'))
await copyFile(resolve(displayDir, 'official/page.js'), resolve(backupDir, 'page.js'))
await writeFile(resolve(backupDir, 'README.md'), [
  '# 移动开奖页完整内容备份（2026-11-07）',
  '',
  '备份的是精简前首页、按需片段和页面脚本。图片、CSS 等未修改资源仍由 `web/public/display/mobile-replica/official/123论坛_files/` 共用，不重复复制。',
  '',
  '恢复内容时，从 `index.html` 中选择需要的模块复制回 `web/public/display/mobile-replica/index.html`；资源文件仍保留在上述目录。需要发布恢复结果时，重新运行 `npm run build`、提交并部署。',
  '',
  '本次从活动首页移除 `widgetBox40585`、`#allsite` 和 `#guilvsite`，仅保留底部可见导航栏。',
  '',
].join('\n'), 'utf8')

let output = index
for (const { start, end } of removals.sort((left, right) => right.start - left.start)) {
  output = output.slice(0, start) + output.slice(end)
}

const result = new JSDOM(output, { virtualConsole }).window.document
for (const selector of selectors) {
  if (result.querySelector(selector)) throw new Error(`Block was not removed: ${selector}`)
}
if (!result.querySelector('#widgetContainer31231 .bottom-nav-bar')) {
  throw new Error('Visible bottom navigation was unexpectedly removed')
}

await writeFile(indexPath, output, 'utf8')
console.log(`Backed up page to ${backupDir}`)
console.log(`Removed blocks: ${selectors.join(', ')}`)
console.log(`Image tags: ${parsed.window.document.querySelectorAll('img').length} -> ${result.querySelectorAll('img').length}`)
