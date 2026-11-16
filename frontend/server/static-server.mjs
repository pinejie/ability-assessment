/**
 * 生产前端静态服务（零依赖，node 内置模块）
 *
 * 职责：
 *  1. 托管 `vite build` 产物 dist/（编译后的 JS/CSS，不暴露 src 源码，无 HMR 通道）
 *  2. `/api/*` 反向代理到本机后端 8080，SSE 逐帧透传（不缓冲）
 *  3. SPA 回退：无扩展名的未知路径回 index.html；带扩展名的缺失资源返回真 404
 *
 * 为什么不用 `vite preview`：Vite 官方明确 preview 只用于本地预览、不适合生产；
 * 且它对缺失的 /assets/*.js 也回退 index.html，会导致浏览器拿到 text/html 的脚本
 * 报 MIME 错误、页面白屏。
 *
 * 启动：node server/static-server.mjs（端口/后端地址由环境变量覆盖）
 */
import http from 'node:http'
import { createReadStream } from 'node:fs'
import { stat } from 'node:fs/promises'
import { extname, join, normalize, resolve } from 'node:path'

const PORT = Number(process.env.PORT || 3000)
const HOST = process.env.HOST || '0.0.0.0'
const API_TARGET = process.env.API_TARGET || 'http://127.0.0.1:8080'
const DIST = resolve(process.env.DIST_DIR || join(import.meta.dirname, '..', 'dist'))

const MIME = {
  '.html': 'text/html; charset=utf-8',
  '.js': 'text/javascript; charset=utf-8',
  '.mjs': 'text/javascript; charset=utf-8',
  '.css': 'text/css; charset=utf-8',
  '.json': 'application/json; charset=utf-8',
  '.svg': 'image/svg+xml',
  '.png': 'image/png',
  '.jpg': 'image/jpeg',
  '.jpeg': 'image/jpeg',
  '.gif': 'image/gif',
  '.webp': 'image/webp',
  '.ico': 'image/x-icon',
  '.woff': 'font/woff',
  '.woff2': 'font/woff2',
  '.ttf': 'font/ttf',
  '.map': 'application/json; charset=utf-8',
}

const target = new URL(API_TARGET)

/** 静态资源：assets/ 下带内容哈希，可长期缓存；index.html 每次校验 */
function cacheControl(pathname) {
  return pathname.startsWith('/assets/')
    ? 'public, max-age=31536000, immutable'
    : 'no-cache'
}

function sendFile(res, filePath, pathname) {
  const type = MIME[extname(filePath).toLowerCase()] || 'application/octet-stream'
  res.writeHead(200, { 'Content-Type': type, 'Cache-Control': cacheControl(pathname) })
  createReadStream(filePath).pipe(res)
}

function sendIndex(res) {
  const indexPath = join(DIST, 'index.html')
  res.writeHead(200, { 'Content-Type': MIME['.html'], 'Cache-Control': 'no-cache' })
  createReadStream(indexPath).pipe(res)
}

async function serveStatic(req, res, pathname) {
  // 归一化并锁定在 DIST 内，防目录穿越（../）
  const rel = normalize(decodeURIComponent(pathname)).replace(/^(\.\.[/\\])+/, '')
  const filePath = join(DIST, rel)
  if (!filePath.startsWith(DIST)) {
    res.writeHead(403).end('Forbidden')
    return
  }

  try {
    const info = await stat(filePath)
    if (info.isFile()) {
      sendFile(res, filePath, pathname)
      return
    }
  } catch {
    // 落到下面的回退逻辑
  }

  // 带扩展名 = 明确的资源请求，缺失就该 404（不能回 index.html，否则 MIME 报错白屏）
  // /@vite/* 是开发期虚拟模块（如 /@vite/client），生产不存在，同样直接 404
  if (extname(pathname) || pathname.startsWith('/@')) {
    res.writeHead(404, { 'Content-Type': 'text/plain; charset=utf-8' }).end('Not Found')
    return
  }
  // 无扩展名 = 前端路由深链（/assess-sheets、/sso/callback…），回 index.html
  sendIndex(res)
}

function proxyApi(req, res) {
  const headers = { ...req.headers, host: target.host }
  const upstream = http.request(
    { hostname: target.hostname, port: target.port || 80, path: req.url, method: req.method, headers },
    (upRes) => {
      const resHeaders = { ...upRes.headers }
      // SSE 必须禁用各级缓冲，否则导入进度会憋到结束才一次性到达
      if (String(upRes.headers['content-type'] || '').includes('text/event-stream')) {
        resHeaders['cache-control'] = 'no-cache, no-transform'
        resHeaders['x-accel-buffering'] = 'no'
        delete resHeaders['content-length']
      }
      res.writeHead(upRes.statusCode || 502, resHeaders)
      upRes.pipe(res) // 流式透传，不聚合
    },
  )

  upstream.on('error', (err) => {
    if (res.headersSent) {
      res.end()
      return
    }
    res.writeHead(502, { 'Content-Type': 'application/json; charset=utf-8' })
    res.end(JSON.stringify({ code: 502, message: `后端不可达: ${err.message}` }))
  })
  // 客户端断开时释放上游连接（SSE 长连接尤其重要）
  res.on('close', () => upstream.destroy())
  req.pipe(upstream)
}

const server = http.createServer((req, res) => {
  const pathname = (req.url || '/').split('?')[0]
  if (pathname.startsWith('/api/')) {
    proxyApi(req, res)
    return
  }
  if (req.method !== 'GET' && req.method !== 'HEAD') {
    res.writeHead(405).end('Method Not Allowed')
    return
  }
  serveStatic(req, res, pathname).catch(() => {
    if (!res.headersSent) res.writeHead(500).end('Internal Server Error')
  })
})

server.listen(PORT, HOST, () => {
  console.log(`[static-server] listening on http://${HOST}:${PORT}`)
  console.log(`[static-server] dist=${DIST} api->${API_TARGET}`)
})

for (const sig of ['SIGTERM', 'SIGINT']) {
  process.on(sig, () => {
    console.log(`[static-server] ${sig} received, shutting down`)
    server.close(() => process.exit(0))
  })
}
