// Funções comuns do backup/restauração da Fruteira (Node 18+, sem dependências).
import fs from 'node:fs'
import path from 'node:path'
import crypto from 'node:crypto'
import net from 'node:net'
import { spawn, spawnSync } from 'node:child_process'
import { fileURLToPath } from 'node:url'
import { pipeline } from 'node:stream/promises'

export const AQUI = path.dirname(fileURLToPath(import.meta.url))
export const RAIZ = path.resolve(AQUI, '..')            // pasta do projeto (ou da pasta "producao")
export const MAGIC = Buffer.from('FRBK1')

/** KEY=VALOR por linha (aceita CRLF, comentários #, aspas opcionais). */
export function lerEnv(arq) {
  const o = {}
  if (!fs.existsSync(arq)) return o
  for (const l of fs.readFileSync(arq, 'utf8').split(/\r?\n/)) {
    const m = l.match(/^\s*([A-Za-z_][A-Za-z0-9_]*)\s*=\s*(.*?)\s*$/)
    if (m && !l.trim().startsWith('#')) o[m[1]] = m[2].replace(/^(["'])(.*)\1$/, '$2')
  }
  return o
}
export const agora = () => { const d = new Date(), p = n => String(n).padStart(2, '0'); return `${d.getFullYear()}${p(d.getMonth() + 1)}${p(d.getDate())}-${p(d.getHours())}${p(d.getMinutes())}${p(d.getSeconds())}` }
export const log = (...a) => console.log(...a)

export function args(argv) {
  const o = { _: [] }
  for (let i = 0; i < argv.length; i++) {
    const a = argv[i]
    if (a.startsWith('--')) { const [k, v] = a.slice(2).split('='); o[k] = v ?? (argv[i + 1] && !argv[i + 1].startsWith('--') ? argv[++i] : true) } else o._.push(a)
  }
  return o
}

export function rodar(cmd, a, opt = {}) {
  const r = spawnSync(cmd, a, { encoding: 'utf8', shell: false, ...opt })
  return { ok: r.status === 0, saida: (r.stdout || '') + (r.stderr || ''), erro: r.error }
}
/** Roda um comando e grava a saída padrão direto em arquivo (dumps grandes não passam pela memória). */
export function rodarParaArquivo(cmd, a, arq, opt = {}) {
  return new Promise(res => {
    const out = fs.createWriteStream(arq); let err = '', code = null, gravado = false, fim = false
    const tenta = () => { if (code !== null && gravado && !fim) { fim = true; res({ ok: code === 0, erro: err.trim() }) } }
    const p = spawn(cmd, a, { ...opt, stdio: ['ignore', 'pipe', 'pipe'] })
    p.on('error', e => { if (!fim) { fim = true; out.destroy(); res({ ok: false, erro: e.message }) } })
    p.stderr.on('data', d => { err += d })
    p.stdout.pipe(out); out.on('finish', () => { gravado = true; tenta() })
    p.on('close', c => { code = c; tenta() })
  })
}
export function rodarDeArquivo(cmd, a, arq, opt = {}) {
  return new Promise(res => {
    let saida = ''
    const p = spawn(cmd, a, { ...opt, stdio: ['pipe', 'pipe', 'pipe'] })
    p.on('error', e => res({ ok: false, saida: e.message }))
    p.stdout.on('data', d => { saida += d }); p.stderr.on('data', d => { saida += d })
    p.on('close', code => res({ ok: code === 0, saida }))
    fs.createReadStream(arq).on('error', e => res({ ok: false, saida: e.message })).pipe(p.stdin)
  })
}

// ---------------- criptografia (AES-256-GCM, chave por scrypt) ----------------
export async function cifrar(entrada, saida, senha) {
  const salt = crypto.randomBytes(16), iv = crypto.randomBytes(12)
  const c = crypto.createCipheriv('aes-256-gcm', crypto.scryptSync(senha, salt, 32), iv)
  const out = fs.createWriteStream(saida); out.write(Buffer.concat([MAGIC, salt, iv]))
  await pipeline(fs.createReadStream(entrada), c, out, { end: false })
  out.end(c.getAuthTag()); await new Promise(r => out.on('finish', r))
}
export async function decifrar(entrada, saida, senha) {
  const tam = fs.statSync(entrada).size, cab = MAGIC.length + 28
  const fd = fs.openSync(entrada, 'r'); const b = Buffer.alloc(cab + 0); fs.readSync(fd, b, 0, cab, 0)
  const tag = Buffer.alloc(16); fs.readSync(fd, tag, 0, 16, tam - 16); fs.closeSync(fd)
  if (!b.subarray(0, MAGIC.length).equals(MAGIC)) throw new Error('Arquivo não é um backup criptografado da Fruteira')
  const d = crypto.createDecipheriv('aes-256-gcm', crypto.scryptSync(senha, b.subarray(5, 21), 32), b.subarray(21, 33)); d.setAuthTag(tag)
  try { await pipeline(fs.createReadStream(entrada, { start: cab, end: tam - 17 }), d, fs.createWriteStream(saida)) }
  catch { fs.rmSync(saida, { force: true }); throw new Error('Senha incorreta ou arquivo corrompido') }
}
export const sha256 = arq => new Promise((res, rej) => { const h = crypto.createHash('sha256'); fs.createReadStream(arq).on('data', d => h.update(d)).on('end', () => res(h.digest('hex'))).on('error', rej) })

export const portaAberta = (porta) => new Promise(res => { const s = net.connect(porta, '127.0.0.1'); s.on('connect', () => { s.destroy(); res(true) }); s.on('error', () => res(false)); s.setTimeout(700, () => { s.destroy(); res(false) }) })

/** Configuração: backup.env + variáveis de ambiente + opções da linha de comando (esta última vence). */
export function configuracao(a = {}) {
  const f = lerEnv(path.join(AQUI, 'backup.env')); const e = process.env
  const pega = (k, ...alt) => a[alt[0]] ?? e[k] ?? f[k] ?? ''
  return {
    pasta: path.resolve(AQUI, pega('BACKUP_PASTA', 'pasta') || 'saida'),
    destinos: String(pega('BACKUP_DESTINOS', 'destino')).split(/;|\|/).map(s => s.trim()).filter(Boolean).map(d => path.resolve(a.destino ? (process.env.FRUTEIRA_CWD || process.cwd()) : AQUI, d)),
    senha: pega('BACKUP_SENHA', 'senha') === true ? '' : String(pega('BACKUP_SENHA', 'senha')),
    retencao: Number(pega('BACKUP_RETENCAO_DIAS', 'retencao') || 30),
    rclone: String(pega('RCLONE_DESTINO', 'rclone')),
  }
}
