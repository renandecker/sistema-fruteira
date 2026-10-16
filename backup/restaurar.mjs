// Restaura um backup da Fruteira.  node backup/restaurar.mjs <arquivo> [--senha XXX] [--sim] [--forcar]
//   Produção: recarrega os bancos no PostgreSQL (Docker). Desenvolvimento: devolve as pastas data/ (guarda as atuais).
//   .env, certificados e realm NÃO são sobrescritos: saem em backup/restaurado-<data>/config para você conferir.
import fs from 'node:fs'
import path from 'node:path'
import readline from 'node:readline/promises'
import { AQUI, RAIZ, lerEnv, agora, log, args, rodar, rodarDeArquivo, decifrar, sha256, portaAberta, configuracao } from './lib.mjs'

const a = args(process.argv.slice(2)); const cfg = configuracao(a)
const base = process.env.FRUTEIRA_CWD || process.cwd()
const arq = a._[0] && path.resolve(base, a._[0])
if (!arq || !fs.existsSync(arq)) { log('Uso: restaurar <arquivo .tar.gz | .tar.gz.enc> [--senha XXX] [--sim] [--forcar]'); process.exit(1) }
const sum = arq + '.sha256'
if (fs.existsSync(sum)) { const esp = fs.readFileSync(sum, 'utf8').split(/\s+/)[0]; if (esp !== await sha256(arq)) { log('✘ O arquivo não confere com o .sha256 (cópia incompleta ou corrompida). Abortando.'); process.exit(1) } log('✔ integridade conferida (sha256)') }
const tmp = fs.mkdtempSync(path.join(AQUI, '.restaura-'))
const limpar = () => fs.rmSync(tmp, { recursive: true, force: true })
try {
  let tgz = arq
  if (arq.endsWith('.enc')) { if (!cfg.senha) { log('✘ Backup criptografado: informe --senha ou BACKUP_SENHA'); process.exit(1) } tgz = path.join(tmp, 'b.tar.gz'); await decifrar(arq, tgz, cfg.senha) }
  const x = rodar('tar', ['-xzf', tgz, '-C', tmp]); if (!x.ok) { log('✘ Arquivo inválido:', x.saida); process.exit(1) }
  const dir = fs.readdirSync(tmp).map(d => path.join(tmp, d)).find(d => fs.existsSync(path.join(d, 'MANIFESTO.json')))
  if (!dir) { log('✘ Não é um backup da Fruteira (sem MANIFESTO.json)'); process.exit(1) }
  const man = JSON.parse(fs.readFileSync(path.join(dir, 'MANIFESTO.json'), 'utf8'))
  log(`Backup de ${man.criadoEm} · modo ${man.modo} · ${man.itens.length} itens`)
  if (!a.sim) {
    const rl = readline.createInterface({ input: process.stdin, output: process.stdout })
    const r = (await rl.question('Isto SUBSTITUI os dados atuais pelos do backup. Digite SIM para continuar: ')).trim(); rl.close()
    if (r !== 'SIM') { log('Cancelado.'); process.exit(1) }
  }
  const env = lerEnv(path.join(RAIZ, '.env'))
  if (man.modo === 'prod') {
    for (const [svc, user, db, f] of [['postgres', env.DB_USER || 'postgres', 'fruteira', 'fruteira.sql'], ['keycloak-db', 'keycloak', 'keycloak', 'keycloak.sql']]) {
      if (!fs.existsSync(path.join(dir, f))) continue
      const r = await rodarDeArquivo('docker', ['compose', '-f', 'docker-compose.prod.yml', 'exec', '-T', svc, 'psql', '-U', user, '-d', db, '-v', 'ON_ERROR_STOP=1'], path.join(dir, f), { cwd: RAIZ })
      log(r.ok ? `✔ banco ${db} restaurado` : `✘ banco ${db}: ${r.saida.slice(-400)}`); if (!r.ok) process.exitCode = 2
    }
    log('ℹ Reinicie os serviços (./parar e ./iniciar) para recarregar os dados.')
  } else if (fs.existsSync(path.join(dir, 'dados'))) {
    const abertas = []; for (const p of [8081, 8082, 8083, 8084]) if (await portaAberta(p)) abertas.push(p)
    if (abertas.length && !a.forcar) { log(`✘ Há serviços rodando (portas ${abertas.join(', ')}). Pare-os (./parar.sh) e tente de novo, ou use --forcar.`); process.exit(1) }
    const ts = agora()
    for (const s of fs.readdirSync(path.join(dir, 'dados'))) {
      const dest = s === '_raiz' ? path.join(RAIZ, 'data') : path.join(RAIZ, s, 'data')   // _raiz = H2 compartilhado
      if (fs.existsSync(dest)) fs.renameSync(dest, `${dest}.antes-restauracao-${ts}`)
      fs.cpSync(path.join(dir, 'dados', s), dest, { recursive: true }); log(`✔ ${s === '_raiz' ? 'data' : s + '/data'} restaurado (o anterior ficou em data.antes-restauracao-${ts})`)
    }
  }
  if (fs.existsSync(path.join(dir, 'config'))) { const saida = path.join(AQUI, `restaurado-${agora()}`); fs.cpSync(path.join(dir, 'config'), path.join(saida, 'config'), { recursive: true }); log(`ℹ .env, certificados e realm foram extraídos em ${saida} (não sobrescrevi nada: copie o que precisar).`) }
} catch (e) { log('✘', e.message); process.exitCode = 1 } finally { limpar() }
