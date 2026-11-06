import { readFile, writeFile } from 'node:fs/promises'
import { resolve } from 'node:path'
import { transform } from 'esbuild'

const sourcePath = resolve('public/display/mobile-replica/official/page.js')
const outputPath = resolve('../server/xupan-server/src/main/resources/static/display/mobile-replica/official/page.js')
const source = await readFile(sourcePath, 'utf8')
const result = await transform(source, {
  format: 'iife',
  legalComments: 'none',
  target: 'es2017',
})

await writeFile(outputPath, result.code, 'utf8')
console.log(`Transpiled mobile display script: ${outputPath}`)
