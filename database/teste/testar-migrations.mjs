// Testa as migrations do Flyway num PostgreSQL de verdade (PGlite, sem Docker, sem Maven) e as confere com as entidades Java.
//   node testar-migrations.mjs        (ou ../testar-migrations.sh / .bat, que instalam o PGlite na primeira vez)
// O que verifica:
//   1) nome dos arquivos (V<n>__<servico>_descricao.sql, numeração única nos 4 serviços, tudo no retaguarda-service) e SQL portátil nas migrations de schema (H2 + PostgreSQL)
//   2) os 4 serviços aplicados no MESMO banco (como em produção), na ordem de versão e em ordem inversa por serviço, sem colisão de nomes
//   3) tabelas, colunas, tipos, NOT NULL, UNIQUE, FK e sequências (incremento 50) batem com as @Entity — falta algo? imprime o SQL sugerido
//   4) reaplicar tudo não quebra (banco antigo criado pelo Hibernate) e o V2 recoloca colunas que faltarem
//   5) pré-cadastro de frutas: 5 categorias, 69 produtos, idempotente, respeita produto/PLU já existentes
import { readdirSync, readFileSync, existsSync } from 'node:fs';
import { join } from 'node:path';
import { PGlite } from '@electric-sql/pglite';
import { lerEntidades, SERVICOS } from './entidades.mjs';
import { adicionarColuna, criarSequencia, criarTabela } from './ddl.mjs';

const raiz = join(import.meta.dirname, '..', '..');
const falhas = []; const avisos = []; let ok = 0;
const confere = (cond, msg) => { if (cond) ok++; else falhas.push(msg); };

// retaguarda-service é o DONO do Flyway: todas as migrations (dos 4 serviços) ficam na mesma pasta, numeração única (V<n>__<servico>_<descricao>.sql)
const DONO = 'retaguarda-service';
const recursos = () => join(raiz, DONO, 'src/main/resources/db');
function migrations(s, pasta = 'migration') {
  const dir = join(recursos(), pasta);
  if (!existsSync(dir)) return [];
  const curto = s.replace('-service', '');
  return readdirSync(dir).filter((n) => n.endsWith('.sql')).map((n) => {
    const m = /^V(\d+)__([a-z0-9_]+)\.sql$/.exec(n), r = /^R__([a-z0-9_]+)\.sql$/.exec(n);
    confere(m || r, `${DONO}/db/${pasta}/${n}: nome fora do padrão (V<número>__<servico>_descricao.sql ou R__descricao.sql)`);
    return { nome: n, versao: m ? Number(m[1]) : null, desc: m ? m[2] : '', sql: readFileSync(join(dir, n), 'utf8') };
  }).filter((x) => pasta !== 'migration' || x.desc.startsWith(curto + '_')).sort((a, b) => (a.versao ?? 1e9) - (b.versao ?? 1e9));
}

// ---------- 1) arquivos ----------
const PG_SO = [[/::\w+/, 'cast "::" (use CAST(... AS ...))'], [/\bSERIAL\b|\bBIGSERIAL\b/i, 'SERIAL'], [/\bON\s+CONFLICT\b/i, 'ON CONFLICT'],
  [/\bILIKE\b/i, 'ILIKE'], [/\bRETURNING\b/i, 'RETURNING'], [/\bDO\s+\$\$/i, 'bloco DO $$'], [/\bTEMP(ORARY)?\s+TABLE\b/i, 'tabela TEMP'],
  [/\bnextval\s*\(/i, 'nextval()'], [/\bCREATE\s+EXTENSION\b/i, 'CREATE EXTENSION']];
const porServico = {};
{ // numeração única e nenhum arquivo órfão (sem prefixo de serviço conhecido)
  const todos = readdirSync(join(recursos(), 'migration')).filter((n) => n.endsWith('.sql'));
  const nums = todos.map((n) => /^V(\d+)__/.exec(n)?.[1]).filter(Boolean).map(Number);
  confere(new Set(nums).size === nums.length, 'versão de migration repetida (a numeração é única para os 4 serviços)');
  confere(Math.min(...nums) === 1, 'a primeira migration deve ser a V1');
  for (const n of todos) confere(SERVICOS.some((s) => new RegExp(`^V\\d+__${s.replace('-service', '')}_`).test(n)), `${n}: o nome precisa ter o serviço após a versão (V<n>__<servico>_descricao.sql)`);
  for (const s of SERVICOS) { const d = join(raiz, s, 'src/main/resources/db'); confere(s === DONO || !existsSync(d), `${s}: não deve ter db/ próprio, o Flyway roda só no ${DONO}`); }
}
for (const s of SERVICOS) {
  const ms = migrations(s); porServico[s] = ms;
  confere(ms.length > 0, `${s}: nenhuma migration em ${DONO}/db/migration`);
  for (const m of ms) {
    for (const [re, nome] of PG_SO) confere(!re.test(m.sql.replace(/--[^\n]*/g, '')), `${s}/${m.nome}: usa ${nome}, que o H2 (desenvolvimento) pode não aceitar. Deixe o SQL portátil ou ponha em db/pre-cadastro (só produção).`);
    confere(!/\bCOMMIT\b|\bBEGIN\b/i.test(m.sql.replace(/--[^\n]*/g, '')), `${s}/${m.nome}: o Flyway já controla a transação; remova BEGIN/COMMIT`);
  }
}

// ---------- 2) aplicar nos 4 serviços, no mesmo banco ----------
// servicos = null: todos juntos, na ordem de versão (como o Flyway faz em produção). Lista: só esses serviços.
async function aplicar(db, servicos = null, { seed = false } = {}) {
  const lista = (servicos ?? SERVICOS).flatMap((s) => porServico[s].map((m) => ({ s, m })));
  if (!servicos) lista.sort((a, b) => a.m.versao - b.m.versao);
  for (const { s, m } of lista) {
    try { await db.exec(m.sql); } catch (e) { falhas.push(`${s}/${m.nome}: ${e.message}`); return false; }
  }
  if (seed) for (const m of migrations(DONO, 'pre-cadastro')) {
    try { await db.exec(m.sql); } catch (e) { falhas.push(`pre-cadastro/${m.nome}: ${e.message}`); return false; }
  }
  return true;
}
const db = new PGlite();
const aplicou = await aplicar(db);
confere(aplicou, 'aplicação das migrations falhou (veja o erro acima)');
const db2 = new PGlite();
confere(await aplicar(db2, [...SERVICOS].reverse()), 'aplicação em outra ordem de serviços falhou (os arquivos de um serviço dependem de outro?)');
await db2.close();

// ---------- 3) conferência com as entidades ----------
const entidades = lerEntidades(raiz);
const consulta = async (sql) => (await db.query(sql)).rows;
if (aplicou) {
  const cols = await consulta(`select table_name t, column_name c, data_type d, character_maximum_length len, numeric_precision p, numeric_scale sc, is_nullable n, column_default def from information_schema.columns where table_schema='public'`);
  const uniques = await consulta(`select lower(c.relname) t, lower(a.attname) c from pg_index i join pg_class c on c.oid=i.indrelid join pg_attribute a on a.attrelid=c.oid and a.attnum = any(i.indkey) where i.indisunique and not i.indisprimary and array_length(i.indkey,1)=1`);
  const fks = await consulta(`select lower(conrelid::regclass::text) t, pg_get_constraintdef(oid) def from pg_constraint where contype='f'`);
  const seqs = await consulta(`select lower(sequencename) s, increment_by inc from pg_sequences where schemaname='public'`);
  const FAMILIA = { 'character varying': 'varchar', bigint: 'bigint', integer: 'integer', numeric: 'numeric', boolean: 'boolean', 'timestamp without time zone': 'timestamp', date: 'date' };
  const sugestao = {};
  const sugerir = (e, sql) => { (sugestao[e.servico] ||= []).push(sql); };

  for (const e of entidades) {
    const t = e.tabela.toLowerCase();
    const dela = cols.filter((c) => c.t === t);
    if (!dela.length) { confere(false, `${e.servico}: tabela ${e.tabela} (${e.arquivo}) não existe nas migrations`); sugerir(e, criarSequencia(e)); sugerir(e, criarTabela(e)); continue; }
    const seq = seqs.find((x) => x.s === e.sequencia.toLowerCase());
    if (!seq) { confere(false, `${e.servico}: sequência ${e.sequencia} não existe`); sugerir(e, criarSequencia(e)); }
    else confere(Number(seq.inc) === 50, `${e.servico}: sequência ${e.sequencia} deve ter INCREMENT BY 50 (o Hibernate espera 50)`);
    for (const c of e.colunas) {
      const d = dela.find((x) => x.c.toLowerCase() === c.nome.toLowerCase());
      if (!d) { confere(false, `${e.servico}: coluna ${e.tabela}.${c.nome} (${e.arquivo}) não existe nas migrations`); sugerir(e, adicionarColuna(e, c)); continue; }
      confere(FAMILIA[d.d] === c.familia, `${e.servico}: ${e.tabela}.${c.nome} é ${d.d} no banco, mas a entidade espera ${c.familia}`);
      if (c.familia === 'varchar') confere(Number(d.len) === Number(/\((\d+)\)/.exec(c.sql)[1]), `${e.servico}: ${e.tabela}.${c.nome} tem tamanho ${d.len}, a entidade declara ${/\((\d+)\)/.exec(c.sql)[1]}`);
      if (c.familia === 'numeric') confere(Number(d.p) === 38 && Number(d.sc) === 2, `${e.servico}: ${e.tabela}.${c.nome} deve ser numeric(38,2) (precisão ${d.p},${d.sc})`);
      if (c.notNull) confere(d.n === 'NO', `${e.servico}: ${e.tabela}.${c.nome} deve ser NOT NULL (tipo primitivo ou nullable=false)`);
      if (c.unique) confere(uniques.some((u) => u.t === t && u.c === c.nome.toLowerCase()), `${e.servico}: falta UNIQUE em ${e.tabela}.${c.nome}`);
      if (c.fk) confere(fks.some((f) => f.t === t && f.def.includes(`(${c.nome.toLowerCase()})`)), `${e.servico}: falta FOREIGN KEY em ${e.tabela}.${c.nome}`);
      if (c.padrao) confere(/true|false|\d/i.test(d.def || ''), `${e.servico}: ${e.tabela}.${c.nome} deve ter DEFAULT ${c.padrao}`);
    }
    for (const d of dela) if (!e.colunas.some((c) => c.nome.toLowerCase() === d.c.toLowerCase())) avisos.push(`${e.servico}: coluna ${e.tabela}.${d.c} existe no banco mas não na entidade (ok se for legado)`);
  }
  const conhecidas = new Set(entidades.map((e) => e.tabela.toLowerCase()));
  for (const t of new Set(cols.map((c) => c.t))) if (!conhecidas.has(t)) avisos.push(`tabela ${t} existe no banco mas nenhuma @Entity a usa`);
  for (const [s, sqls] of Object.entries(sugestao)) {
    const prox = Math.max(...SERVICOS.flatMap((x) => porServico[x].map((m) => m.versao))) + 1;
    console.log(`\n--- SUGESTÃO: ${DONO}/src/main/resources/db/migration/V${prox}__${s.replace('-service', '')}_descreva_a_mudanca.sql ---\n${sqls.join('\n')}\n`);
  }
}

// ---------- 4) banco antigo: reaplicar e recompletar ----------
if (aplicou) {
  confere(await aplicar(db), 'reaplicar as migrations num banco que já as tem (banco criado pelo Hibernate) falhou');
  await db.exec('ALTER TABLE Produto DROP COLUMN atalho; ALTER TABLE Nota DROP COLUMN qrCode;');
  for (const s of ['catalogo-service', 'retaguarda-service']) await db.exec(porServico[s].find((m) => m.desc.includes('completa_colunas')).sql);
  const volt = await consulta(`select count(*)::int n from information_schema.columns where table_schema='public' and ((table_name='produto' and column_name='atalho') or (table_name='nota' and column_name='qrcode'))`);
  confere(volt[0].n === 2, 'o V2 não recolocou colunas que faltavam num banco antigo');
}

// ---------- 5) pré-cadastro (produção) ----------
const sementes = migrations(DONO, 'pre-cadastro');
confere(sementes.length === 1 && sementes[0].nome.startsWith('R__'), `${DONO}/db/pre-cadastro deve ter o R__pre_cadastro...sql`);
if (aplicou && sementes.length) {
  const contar = async (b = db) => (await b.query(`select (select count(*)::int from categoria) cats, (select count(*)::int from produto) prods, (select count(*)::int from produto where plu is not null) plus, (select count(distinct lower(nome))::int from produto) nomes`)).rows[0];
  const antes = await contar();
  confere(antes.prods === 0, 'o banco de teste devia começar sem produtos');
  await db.exec(sementes[0].sql);
  const a = await contar();
  confere(a.cats === 5 && a.prods === 69 && a.plus === 69 && a.nomes === 69, `pré-cadastro: esperado 5 categorias e 69 produtos com PLU, veio ${JSON.stringify(a)}`);
  await db.exec(sementes[0].sql);
  const b = await contar();
  confere(b.cats === 5 && b.prods === 69, `pré-cadastro não é idempotente: ${JSON.stringify(b)}`);
  const vazios = (await db.query(`select count(*)::int n from produto where nome is null or unidade not in ('KG','UN','MACO','CX') or precoVarejo is null or ativo is not true or imagem is null`)).rows[0].n;
  confere(vazios === 0, `pré-cadastro: ${vazios} produto(s) com unidade/preço/ativo/imagem inválidos`);
  const temp = (await db.query(`select count(*)::int n from pg_tables where tablename in ('pre_produto','pre_categoria')`)).rows[0].n;
  confere(temp === 0, 'pré-cadastro deixou tabelas temporárias para trás');

  // banco com cadastro próprio: produto existente (mesmo nome, outro caixa de letra) e PLU 2 ocupado por outro produto
  const d3 = new PGlite(); await aplicar(d3, ['catalogo-service']);
  await d3.exec(`INSERT INTO categoria (id, nome, ordem, ativo) VALUES (nextval('categoria_seq'), 'frutas', 7, TRUE);
                 INSERT INTO produto (id, nome, unidade, categoria, precoVarejo, plu, ativo) VALUES (nextval('produto_seq'), 'BANANA PRATA', 'KG', 'frutas', 1.00, 1, TRUE), (nextval('produto_seq'), 'Meu Produto', 'UN', 'frutas', 2.00, 2, TRUE);`);
  await d3.exec(sementes[0].sql);
  const c = await contar(d3);
  confere(c.prods === 69 + 1 && c.cats === 5, `pré-cadastro com cadastro existente: esperado 70 produtos e 5 categorias, veio ${JSON.stringify(c)}`);
  const banana = (await d3.query(`select count(*)::int n, min(precoVarejo)::text p from produto where lower(nome)='banana prata'`)).rows[0];
  confere(banana.n === 1 && banana.p === '1.00', 'pré-cadastro alterou ou duplicou um produto que já existia');
  const plu2 = (await d3.query(`select count(*)::int n from produto where plu = 2`)).rows[0].n;
  confere(plu2 === 1, 'pré-cadastro duplicou um PLU já usado');
  await d3.close();
}
await db.close();

// ---------- resultado ----------
for (const a of avisos) console.log('⚠ ' + a);
if (falhas.length) { console.log(`\n❌ ${falhas.length} problema(s), ${ok} verificações ok:`); for (const f of falhas) console.log('  - ' + f); process.exit(1); }
console.log(`✅ migrations ok — ${ok} verificações (${entidades.length} entidades, ${SERVICOS.length} serviços, PostgreSQL via PGlite)`);
