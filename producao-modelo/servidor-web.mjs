// Servidor web mínimo (sem dependências) para produção SEM nginx: serve ./web e encaminha /api/<serviço>/... aos microsserviços.
// Uso: node servidor-web.mjs [porta]   (Para HTTPS real use USE_NGINX=true.)
import http from 'node:http'
import { createReadStream, statSync } from 'node:fs'
import { join, extname, normalize, resolve } from 'node:path'
import { fileURLToPath } from 'node:url'

const raiz = resolve(fileURLToPath(new URL('./web', import.meta.url)))
const porta = +(process.argv[2] || process.env.WEB_PORT || 5173)
const SERVICOS = { catalogo: 8081, estoque: 8082, vendas: 8083, retaguarda: 8084 }
const TIPOS = { '.html': 'text/html; charset=utf-8', '.js': 'text/javascript', '.css': 'text/css', '.json': 'application/json', '.svg': 'image/svg+xml', '.png': 'image/png', '.jpg': 'image/jpeg', '.jpeg': 'image/jpeg', '.webp': 'image/webp', '.ico': 'image/x-icon', '.woff2': 'font/woff2', '.txt': 'text/plain' }

function arquivo(caminho) { try { return statSync(caminho).isFile() ? caminho : null } catch { return null } }

http.createServer((req, res) => {
  const url = new URL(req.url, 'http://x')
  const m = /^\/api\/([^/]+)(\/.*)?$/.exec(url.pathname)
  if (m) {
    const porta2 = SERVICOS[m[1]]; const resto = m[2] || '/'
    if (!porta2 || resto.startsWith('/q/')) { res.writeHead(404).end(); return }            // nunca expõe /q/ (swagger, health…)
    const p = http.request({ host: '127.0.0.1', port: porta2, path: resto + url.search, method: req.method, headers: { ...req.headers, host: `127.0.0.1:${porta2}` } },
      r => { res.writeHead(r.statusCode, r.headers); r.pipe(res) })
    p.on('error', () => { if (!res.headersSent) res.writeHead(502, { 'Content-Type': 'text/plain' }); res.end('Serviço indisponível') })
    req.pipe(p); return
  }
  let alvo = resolve(join(raiz, normalize(decodeURIComponent(url.pathname))))
  if (!alvo.startsWith(raiz)) { res.writeHead(403).end(); return }
  const f = arquivo(alvo) || arquivo(join(raiz, 'index.html'))                                // SPA: rota desconhecida → index.html
  const cache = f.includes('/assets/') || f.includes('\\assets\\') ? 'public, max-age=31536000, immutable' : 'no-cache'
  res.writeHead(200, { 'Content-Type': TIPOS[extname(f)] || 'application/octet-stream', 'Cache-Control': cache, 'X-Frame-Options': 'SAMEORIGIN' })
  createReadStream(f).pipe(res)
}).listen(porta, '0.0.0.0', () => console.log(`Fruteira web em http://localhost:${porta}`))
