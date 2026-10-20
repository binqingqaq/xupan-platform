import { readFile, writeFile } from 'node:fs/promises'
import { dirname, resolve } from 'node:path'
import { fileURLToPath } from 'node:url'
import { JSDOM } from 'jsdom'

const scriptDirectory = dirname(fileURLToPath(import.meta.url))
const webDirectory = resolve(scriptDirectory, '..')
const sourcePath = resolve(webDirectory, 'public/hby019-demo/source/player-ui1.html')
const outputPath = resolve(webDirectory, 'public/hby019-demo/source/account-panel.html')
const sourceHtml = await readFile(sourcePath, 'utf8')
const sourceDocument = new JSDOM(sourceHtml).window.document

const panel = [...sourceDocument.querySelectorAll('.uni-popup.right')]
  .find(element => element.querySelector('.font-setting-bar') && element.querySelector('#myDiv'))

if (!panel) throw new Error('HBY019 UI1 source does not contain the account panel DOM')

const csp = sourceDocument.querySelector('meta[http-equiv="Content-Security-Policy"]')?.outerHTML ?? ''
const viewport = sourceDocument.querySelector('meta[name="viewport"]')?.outerHTML ?? ''
const stylesheets = [...sourceDocument.querySelectorAll('head link[rel="stylesheet"]')]
  .map(element => element.outerHTML)
  .join('\n')
const styles = [...sourceDocument.querySelectorAll('head style')]
  .map(element => element.outerHTML)
  .join('\n')
const panelFontStyle = `<style id="xupan-account-panel-font">.uni-popup.right,.uni-popup.right *{font-family:"Helvetica Neue", Helvetica, "PingFang SC", "微软雅黑", Tahoma, Arial, sans-serif !important;}</style>`
const htmlStyle = sourceDocument.documentElement.getAttribute('style') ?? ''
const bodyClass = sourceDocument.body.className

const standaloneHtml = `<!doctype html>
<html style="${htmlStyle}; background: transparent !important;">
<head>
  <meta charset="utf-8">
  ${csp}
  ${viewport}
  <title>HBY019 账户流水面板</title>
  ${stylesheets}
  ${styles}
  ${panelFontStyle}
</head>
<body class="${bodyClass}" style="margin:0;background:transparent;">
  ${panel.outerHTML}
  <script src="/hby019-demo/source/account-panel-bridge.js"></script>
</body>
</html>
`

await writeFile(outputPath, standaloneHtml.replace(/[ \t]+$/gm, ''), 'utf8')
console.log(`Generated ${outputPath}`)
