// Backup da Fruteira → um arquivo .tar.gz (opcionalmente criptografado) pronto para pendrive ou Google Drive.
//   node backup/backup.mjs [--destino "E:\;D:\Google Drive\Fruteira"] [--senha XXX] [--retencao 30] [--rclone gdrive:Fruteira]
import fs from 'node:fs'
import path from 'node:path'
import { AQUI, RAIZ, lerEnv, agora, log, args, rodar, rodarParaArquivo, cifrar, sha256, configuracao } from './lib.mjs'

const a = args(process.argv.slice(2)); const cfg = configuracao(a)
const envArq = ['.env'].map(f => path.join(RAIZ, f)).find(f => fs.existsSync(f))
const env = envArq ? lerEnv(envArq) : {}
const prod = (env.APP_ENV || '').toLowerCase() === 'producao' || a.modo === 'producao'
const modo = a.modo === 'desenvolvimento' ? 'dev' : prod ? 'prod' : 'dev'
const stamp = agora(); const nome = `fruteira-${stamp}-${modo}`
const stage = fs.mkdtempSync(path.join(fs.existsSync(cfg.pasta) ? cfg.pasta : (fs.mkdirSync(cfg.pasta, { recursive: true }), cfg.pasta), '.tmp-'))
const dir = path.join(stage, nome); fs.mkdirSync(dir)
const itens = []; const avisos = []; let erros = 0

const copiar = (de, para, rotulo) => {
  try { if (!fs.existsSync(de)) return false; fs.cpSync(de, path.join(dir, para), { recursive: true }); itens.push(para); log(`  ✔ ${rotulo || para}`); return true }
  catch (e) { avisos.push(`${para}: ${e.message}`); log(`  ⚠ ${rotulo || para}: ${e.message}`); return false }
}

log(`Backup da Fruteira (${modo === 'prod' ? 'PRODUÇÃO — PostgreSQL' : 'DESENVOLVIMENTO — H2'}) · ${stamp}`)
if (modo === 'prod') {
  const compose = ['-f', 'docker-compose.prod.yml']
  const dumps = [['postgres', env.DB_USER || 'postgres', 'fruteira', 'fruteira.sql'], ['keycloak-db', 'keycloak', 'keycloak', 'keycloak.sql']]
  for (const [svc, user, db, arq] of dumps) {
    const r = await rodarParaArquivo('docker', ['compose', ...compose, 'exec', '-T', svc, 'pg_dump', '-U', user, '--clean', '--if-exists', db], path.join(dir, arq), { cwd: RAIZ })
    if (r.ok) { itens.push(arq); log(`  ✔ banco ${db} (${(fs.statSync(path.join(dir, arq)).size / 1024).toFixed(0)} KB)`) }
    else { erros++; log(`  ✘ banco ${db}: ${r.erro || 'falhou'} — o Docker está rodando e os contêineres estão de pé?`) }
  }
} else {
  const servs = fs.readdirSync(RAIZ).filter(d => d.endsWith('-service') && fs.existsSync(path.join(RAIZ, d, 'data')))
  if (!servs.length) { avisos.push('Nenhuma pasta data/ dos serviços encontrada (o banco H2 nasce quando o serviço sobe pela primeira vez).'); log('  ⚠ nenhum banco H2 encontrado') }
  for (const s of servs) copiar(path.join(RAIZ, s, 'data'), path.join('dados', s), `banco H2 de ${s}`)
  if (servs.length) log('  ℹ Em desenvolvimento o ideal é parar os serviços (./parar.sh) antes: arquivo aberto pode sair inconsistente.')
}
// Configuração e segredos (precisam voltar numa restauração em servidor novo)
if (envArq) copiar(envArq, 'config/.env', 'configuração (.env — contém senhas)')
for (const k of ['FISCAL_CERT_PATH', 'FISCAL_TRUST_PATH']) if (env[k]) copiar(path.resolve(RAIZ, env[k]), `config/${k.toLowerCase()}-${path.basename(env[k])}`, `${k} (certificado digital)`) || avisos.push(`${k} não encontrado: ${env[k]}`)
copiar(path.join(RAIZ, 'keycloak'), 'config/keycloak', 'realm do Keycloak')
copiar(path.join(RAIZ, 'nginx', 'certs'), 'config/nginx-certs', 'certificados TLS do nginx')
fs.writeFileSync(path.join(dir, 'MANIFESTO.json'), JSON.stringify({ sistema: 'fruteira', criadoEm: new Date().toISOString(), modo, itens, avisos, criptografado: !!cfg.senha }, null, 2))

// Compacta (tar do sistema: existe no Linux, macOS e Windows 10+)
const tgz = path.join(stage, `${nome}.tar.gz`)
const t = rodar('tar', ['-czf', tgz, '-C', stage, nome])
if (!t.ok) { log('✘ Não consegui compactar (comando tar):', t.saida || t.erro?.message); fs.rmSync(stage, { recursive: true, force: true }); process.exit(1) }
let final = path.join(cfg.pasta, `${nome}.tar.gz`)
if (cfg.senha) { final += '.enc'; await cifrar(tgz, final, cfg.senha); log('  🔒 criptografado (AES-256-GCM). Guarde a senha: sem ela o backup NÃO abre.') }
else { fs.copyFileSync(tgz, final); log('  ⚠ SEM senha: o arquivo contém o .env e os certificados. Defina BACKUP_SENHA antes de levar para a nuvem.') }
fs.rmSync(stage, { recursive: true, force: true })
const hash = await sha256(final); fs.writeFileSync(final + '.sha256', `${hash}  ${path.basename(final)}\n`)
log(`✔ ${final} (${(fs.statSync(final).size / 1048576).toFixed(2)} MB)`)

// Cópias extras: pendrive, pasta do Google Drive (Drive para computador) ou rclone
let copias = 0
for (const d of cfg.destinos) {
  try { if (!fs.existsSync(d)) { if (!fs.existsSync(path.dirname(d))) throw Object.assign(new Error('pasta/pendrive não encontrado'), { code: 'ENOENT' }); fs.mkdirSync(d) } for (const f of [final, final + '.sha256']) fs.copyFileSync(f, path.join(d, path.basename(f))); copias++; log(`✔ cópia em ${d}`) }
  catch (e) { erros++; log(`✘ não consegui copiar para ${d}: ${e.code || e.message} (pendrive conectado?)`) }
}
if (cfg.rclone) {
  const r = rodar('rclone', ['copy', final, cfg.rclone, '--include', path.basename(final)])
  if (r.ok) { copias++; log(`✔ enviado por rclone para ${cfg.rclone}`) } else { erros++; log(`✘ rclone falhou: ${r.saida || r.erro?.message}`) }
}
if (!cfg.destinos.length && !cfg.rclone) log('ℹ Sem destino extra: copie o arquivo acima para o pendrive/Drive, ou configure BACKUP_DESTINOS em backup/backup.env.')

// Retenção: apaga backups antigos (só arquivos fruteira-*) na pasta local e nos destinos
if (cfg.retencao > 0) for (const pasta of [cfg.pasta, ...cfg.destinos]) {
  try { for (const f of fs.readdirSync(pasta)) if (/^fruteira-\d{8}-\d{6}-(dev|prod)\.tar\.gz(\.enc)?(\.sha256)?$/.test(f) && Date.now() - fs.statSync(path.join(pasta, f)).mtimeMs > cfg.retencao * 864e5) { fs.rmSync(path.join(pasta, f)); log(`  🗑 removido (mais de ${cfg.retencao} dias): ${f}`) } } catch {}
}
avisos.forEach(w => log('⚠', w))
process.exit(erros ? 2 : 0)
