import { cp, mkdir, readFile, readdir, rm, writeFile } from 'node:fs/promises'
import { dirname, join, resolve } from 'node:path'
import { fileURLToPath } from 'node:url'

const webRoot = resolve(dirname(fileURLToPath(import.meta.url)), '..')
const projectRoot = resolve(webRoot, '..')
const sourceRoot = resolve(process.argv[2] || resolve(projectRoot, '..', 'hby019-local-rebuild'))
const publicRoot = resolve(webRoot, 'public')
const destination = resolve(publicRoot, 'hby019-demo')

if (destination !== resolve(publicRoot, 'hby019-demo')) {
  throw new Error(`Unsafe destination: ${destination}`)
}

const requiredFiles = [
  'index.html', 'console.html', 'portal.html', 'showdetails.html',
  'player.html', 'player-ui1.html', 'local-login.js',
  'local-api-bridge.js', 'portal-local.js',
]
const requiredDirectories = ['static/xz', 'static/reference-player']

for (const relative of [...requiredFiles, ...requiredDirectories]) {
  const path = resolve(sourceRoot, relative)
  if (!path.startsWith(`${sourceRoot}${process.platform === 'win32' ? '\\' : '/'}`)) {
    throw new Error(`Source path escaped source root: ${relative}`)
  }
  await readFile(path).catch(async error => {
    if (error.code !== 'EISDIR') throw new Error(`Missing source input: ${relative}`)
    await readdir(path)
  })
}

const stage = resolve(publicRoot, `.hby019-demo-stage-${process.pid}`)
await rm(stage, { recursive: true, force: true })
const stagedSource = resolve(stage, 'source')
await mkdir(stagedSource, { recursive: true })

for (const relative of requiredFiles) {
  await cp(resolve(sourceRoot, relative), resolve(stagedSource, relative))
}
for (const relative of requiredDirectories) {
  await cp(resolve(sourceRoot, relative), resolve(stagedSource, relative), { recursive: true })
}

const replaceRequired = (text, from, to, label) => {
  if (!text.includes(from)) throw new Error(`Expected source marker not found: ${label}`)
  return text.replaceAll(from, to)
}

const readme = [
  '# HBY019 local demo source bundle',
  '',
  'Generated from the user-provided local HBY019 rebuild project.',
  'HTML, CSS, JavaScript, fonts, and images are copied from that workspace.',
  'Only static URL prefixes and demo navigation are rewritten for Xupan.',
  'The page HTML, inline styles, source scripts, and local fixtures are served as documents.',
  'Local API bridges provide demo fixtures; they do not call Xupan or HBY019 services.',
  'The player pages are captured client pages plus local runtime/fixture adaptations,',
  'not a complete server source or an unmodified official backend implementation.',
  '',
  'The HBY019 source workspace does not contain the chart sprite ball_hk6.png.',
  'No substitute artwork is generated for that unavailable asset.',
  'The login source references /gxagent/fontawesome-webfont.woff; the supplied',
  'FontAwesome file from static/xz/fonts is used for that local reference.',
  'The player trend iframe target is not a captured page. Its local reference server',
  'returns the standard Python 404 document; this bundle preserves that response.',
  '',
].join('\n')
await writeFile(resolve(stagedSource, 'SOURCE-BUNDLE.md'), readme, 'utf8')

const rewriteTextFiles = async directory => {
  for (const entry of await readdir(directory, { withFileTypes: true })) {
    const path = resolve(directory, entry.name)
    if (entry.isDirectory()) {
      await rewriteTextFiles(path)
      continue
    }
    if (!/\.(?:html|js|css)$/.test(entry.name)) continue

    let text = await readFile(path, 'utf8')
    text = text.replaceAll('/static/', '/hby019-demo/source/static/')
    text = text.replaceAll('/gxagent/fontawesome-webfont.woff', '/hby019-demo/source/static/xz/fonts/fontawesome-webfont.woff')
    text = text.replaceAll('/local-api-bridge.js', '/hby019-demo/source/local-api-bridge.js')
    text = text.replaceAll("'/chat/index/zoushi?", "'/hby019-demo/source/chat/index/zoushi.html?")
    text = text.replaceAll('"/chat/index/zoushi?', '"/hby019-demo/source/chat/index/zoushi.html?')
    text = text.replaceAll('"/showdetails.html"', '"/hby019-demo/source/showdetails.html"')
    text = text.replaceAll("'/showdetails.html'", "'/hby019-demo/source/showdetails.html'")

    if (/^(?:player(?:-ui1)?\.html|official-player-runtime(?:-ui1)?\.js)$/.test(entry.name)) {
      text = text.replaceAll("'/player.html", "'/hby019-demo/source/player.html")
      text = text.replaceAll('"/player.html', '"/hby019-demo/source/player.html')
      text = text.replaceAll("'/player-ui1.html", "'/hby019-demo/source/player-ui1.html")
      text = text.replaceAll('"/player-ui1.html', '"/hby019-demo/source/player-ui1.html')
    }

    if (path.endsWith(`${process.platform === 'win32' ? '\\' : '/'}local-login.js`)) {
      text = replaceRequired(text,
        "location.replace('./console.html' + (params.get('reset-demo') === '1' ? '?reset-demo=1' : ''));",
        "window.top.location.replace('/hby019-demo/console' + (params.get('reset-demo') === '1' ? '?reset=1' : ''));",
        'login console route')
      text = replaceRequired(text, "location.replace('./portal.html');", "window.top.location.replace('/hby019-demo/portal');", 'login portal route')
      text = replaceRequired(text, "location.href = './console.html';", "window.top.location.href = '/hby019-demo/console';", 'login submit route')
      text = replaceRequired(text, "location.href = './portal.html';", "window.top.location.href = '/hby019-demo/portal';", 'login lookup route')
    }

    if (path.endsWith(`${process.platform === 'win32' ? '\\' : '/'}console.html`)) {
      text = replaceRequired(text,
        'window.location.href = data.loginUrl || ("/login/login2/" + (data.loginPrefix || _loginPrefix) + "/" + _rbUserName);',
        "window.top.location.href = '/hby019-demo/login?view=console';",
        'expired console route')
      text = replaceRequired(text,
        'window.location.href = "/login/login2/" + _loginPrefix + "/" + _rbUserName;',
        "window.top.location.href = '/hby019-demo/login?view=console';",
        'failed console route')
      text = replaceRequired(text, 'location.href = "/";', "window.top.location.href = '/hby019-demo/login';", 'console exit route')
      text = replaceRequired(text,
        '$("#groupLoginUrl").attr(\'href\', data.rob.link).html(data.rob.link);',
        '$("#groupLoginUrl").attr(\'href\', data.rob.link).html(location.protocol + "//" + location.host + "/player.html");',
        'console link label geometry')
    }

    if (path.endsWith(`${process.platform === 'win32' ? '\\' : '/'}portal.html`)) {
      text = replaceRequired(text,
        '<script src="./portal-local.js"></script>',
        '<script src="/hby019-demo/source/portal-local.js"></script>',
        'portal local script path')
    }

    if (path.endsWith(`${process.platform === 'win32' ? '\\' : '/'}index.html`)) {
      text = replaceRequired(text,
        '<script src="./local-login.js"></script>',
        '<script src="/hby019-demo/source/local-login.js"></script>',
        'login local script path')
    }

    if (path.endsWith(`${process.platform === 'win32' ? '\\' : '/'}local-api-bridge.js`)) {
      text = text.replaceAll("'/player.html", "'/hby019-demo/player.html")
      text = text.replaceAll('"/player.html', '"/hby019-demo/player.html')
      text = replaceRequired(text, "location.href = './';", "window.top.location.href = '/hby019-demo/login';", 'bridge exit route')
    }

    await writeFile(path, text, 'utf8')
  }
}

await rewriteTextFiles(stagedSource)

const trendError = [
  '<!DOCTYPE HTML>',
  '<html lang="en">',
  '    <head>',
  '        <meta charset="utf-8">',
  '        <title>Error response</title>',
  '    </head>',
  '    <body>',
  '        <h1>Error response</h1>',
  '        <p>Error code: 404</p>',
  '        <p>Message: File not found.</p>',
  '        <p>Error code explanation: 404 - Nothing matches the given URI.</p>',
  '    </body>',
  '</html>',
  '',
].join('\n')
const trendPage = resolve(stagedSource, 'chat', 'index', 'zoushi.html')
await mkdir(dirname(trendPage), { recursive: true })
await writeFile(trendPage, trendError, 'utf8')

await rm(destination, { recursive: true, force: true })
await mkdir(destination, { recursive: true })
await cp(stagedSource, resolve(destination, 'source'), { recursive: true })
await rm(stage, { recursive: true, force: true })
console.log(`Rebuilt ${destination} from ${sourceRoot}`)
