// Lê as classes @Entity dos serviços Java e devolve tabelas/colunas como o Hibernate as mapeia
// (estratégia de nomes padrão do Quarkus: nome do campo = nome da coluna; entidade aninhada = "Externa$Interna").
// Usado só pelo teste das migrations: o teste confere se o SQL do Flyway cobre TUDO que as entidades exigem.
import { readdirSync, readFileSync, statSync } from 'node:fs';
import { join } from 'node:path';

export const SERVICOS = ['catalogo-service', 'estoque-service', 'vendas-service', 'retaguarda-service'];

function arquivosJava(dir) {
  const r = [];
  for (const n of readdirSync(dir)) {
    const p = join(dir, n);
    if (statSync(p).isDirectory()) r.push(...arquivosJava(p));
    else if (n.endsWith('.java')) r.push(p);
  }
  return r;
}

const semComentarios = (t) => t.replace(/\/\*[\s\S]*?\*\//g, '').replace(/\/\/[^\n]*/g, '');

function fechamento(t, abre) {          // índice da chave que fecha a de "abre"
  let d = 0;
  for (let i = abre; i < t.length; i++) {
    if (t[i] === '"') { i++; while (t[i] !== '"') i += t[i] === '\\' ? 2 : 1; continue; }
    if (t[i] === '{') d++;
    else if (t[i] === '}' && --d === 0) return i;
  }
  throw new Error('chave não fechada');
}

function parenteses(t, abre) {          // índice do ")" que fecha o "(" de "abre"
  let d = 0;
  for (let i = abre; i < t.length; i++) {
    if (t[i] === '"') { i++; while (t[i] !== '"') i += t[i] === '\\' ? 2 : 1; continue; }
    if (t[i] === '(') d++;
    else if (t[i] === ')' && --d === 0) return i;
  }
  throw new Error('parêntese não fechado');
}

function separarTopo(s, sep) {          // divide em "sep" fora de (), <> e aspas
  const partes = []; let atual = '', p = 0, a = 0;
  for (let i = 0; i < s.length; i++) {
    const c = s[i];
    if (c === '"') { const j = s.indexOf('"', i + 1); atual += s.slice(i, j + 1); i = j; continue; }
    if (c === '(') p++; else if (c === ')') p--; else if (c === '<') a++; else if (c === '>') a--;
    if (c === sep && p === 0 && a === 0) { partes.push(atual); atual = ''; } else atual += c;
  }
  partes.push(atual);
  return partes;
}

function tirarAnotacoes(stmt) {         // -> { anot: {Nome: "args"}, resto }
  const anot = {}; let resto = '';
  for (let i = 0; i < stmt.length; i++) {
    if (stmt[i] === '@') {
      const m = /^@(\w+)/.exec(stmt.slice(i));
      let j = i + m[0].length;
      let args = '';
      if (stmt.slice(j).trimStart().startsWith('(')) {
        const abre = stmt.indexOf('(', j); const fecha = parenteses(stmt, abre);
        args = stmt.slice(abre + 1, fecha); j = fecha + 1;
      }
      anot[m[1]] = args; i = j - 1;
    } else resto += stmt[i];
  }
  return { anot, resto: resto.trim() };
}

function argumento(args, nome) {
  const m = new RegExp('\\b' + nome + '\\s*=\\s*("(?:[^"\\\\]|\\\\.)*"|[\\w.]+)').exec(args || '');
  return m ? m[1].replace(/^"|"$/g, '') : undefined;
}

const TIPOS = {
  String: 'varchar', Long: 'bigint', long: 'bigint', Integer: 'integer', int: 'integer',
  BigDecimal: 'numeric', boolean: 'boolean', Boolean: 'boolean', LocalDateTime: 'timestamp', LocalDate: 'date'
};
const PRIMITIVOS = new Set(['long', 'int', 'boolean']);

function colunaDe(tipo, nome, anot) {
  if ('Transient' in anot || 'OneToMany' in anot) return null;
  const col = anot.Column;
  let familia, sql;
  if ('ManyToOne' in anot) { return { nome: nome + '_id', familia: 'bigint', sql: 'bigint', notNull: false, unique: false, fk: tipo }; }
  if ('Enumerated' in anot) { familia = 'varchar'; sql = 'varchar(255)'; }
  else {
    familia = TIPOS[tipo];
    if (!familia) throw new Error(`tipo Java sem mapeamento: ${tipo} (${nome})`);
    if (familia === 'varchar') sql = `varchar(${argumento(col, 'length') || 255})`;
    else if (familia === 'numeric') sql = 'numeric(38,2)';
    else if (familia === 'timestamp') sql = 'timestamp(6)';
    else sql = familia;
  }
  const definicao = argumento(col, 'columnDefinition');
  let padrao = null;
  if (definicao) { const m = /default\s+(\w+)/i.exec(definicao); if (m) padrao = m[1]; }
  return {
    nome, familia, sql,
    notNull: PRIMITIVOS.has(tipo) || argumento(col, 'nullable') === 'false',
    unique: argumento(col, 'unique') === 'true',
    padrao
  };
}

function campos(corpo) {                // campos persistentes no nível da classe (ignora métodos, enums e classes internas)
  const colunas = []; let stmt = '';
  for (let i = 0; i < corpo.length; i++) {
    const c = corpo[i];
    if (c === '"') { const j = corpo.indexOf('"', i + 1); stmt += corpo.slice(i, j + 1); i = j; continue; }
    if (c === '{') { i = fechamento(corpo, i); stmt = ''; continue; }
    if (c !== ';') { stmt += c; continue; }
    const { anot, resto } = tirarAnotacoes(stmt.trim()); stmt = '';
    if (!resto || /\b(static|final)\b/.test(resto)) continue;
    const [primeiro, ...outros] = separarTopo(resto, ',');
    const m = /^(?:(?:public|private|protected|transient)\s+)*([\w.<>?, ]+?)\s+(\w+)\s*(?:=[\s\S]*)?$/.exec(primeiro.trim());
    if (!m) throw new Error('campo não reconhecido: ' + resto);
    const tipo = m[1].replace(/\s+/g, ' ');
    for (const nome of [m[2], ...outros.map((o) => /^\s*(\w+)/.exec(o)[1])]) {
      const col = colunaDe(tipo, nome, anot);
      if (col) colunas.push(col);
    }
  }
  return colunas;
}

export function lerEntidades(raiz) {
  const entidades = [];
  for (const servico of SERVICOS) {
    for (const arq of arquivosJava(join(raiz, servico, 'src/main/java'))) {
      const t = semComentarios(readFileSync(arq, 'utf8'));
      if (!t.includes('@Entity')) continue;
      // todas as classes do arquivo, com intervalo, para saber quem contém quem
      const classes = [];
      const re = /\bclass\s+(\w+)[^{;]*\{/g; let m;
      while ((m = re.exec(t))) {
        const abre = m.index + m[0].length - 1;
        classes.push({ nome: m[1], abre, fecha: fechamento(t, abre), inicio: m.index });
      }
      for (const c of classes) {
        const antes = t.slice(Math.max(0, t.lastIndexOf('}', c.inicio), t.lastIndexOf(';', c.inicio)) + 1, c.inicio);
        if (!/@Entity\b/.test(antes)) continue;
        const argsEntity = /@Entity\s*(?:\(([^)]*)\))?/.exec(antes);
        const nomeEntidade = argumento(argsEntity && argsEntity[1], 'name');
        const externas = classes.filter((o) => o !== c && o.abre < c.abre && o.fecha > c.fecha).sort((a, b) => a.abre - b.abre);
        const nome = nomeEntidade || [...externas.map((o) => o.nome), c.nome].join('$');
        const corpo = t.slice(c.abre + 1, c.fecha);
        const colunas = [{ nome: 'id', familia: 'bigint', sql: 'bigint', notNull: true, unique: false, pk: true }, ...campos(corpo)];
        entidades.push({ servico, arquivo: arq.replace(raiz + '/', ''), classe: c.nome, tabela: nome, sequencia: nome + '_SEQ', colunas });
      }
    }
  }
  return entidades;
}
