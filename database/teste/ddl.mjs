// Gera o SQL (portátil: PostgreSQL e H2) que corresponde às entidades. Usado para criar o V1/V2 e para sugerir migrations novas.
const simples = (tabela) => tabela.split('$').pop().toLowerCase();

export function definicaoColuna(c, { paraAlter = false } = {}) {
  let s = `${c.nome} ${c.sql}`;
  if (c.padrao) s += ` DEFAULT ${c.padrao}`;
  else if (paraAlter && c.notNull) s += c.familia === 'boolean' ? ' DEFAULT FALSE' : ' DEFAULT 0';   // coluna NOT NULL em tabela que já tem linhas
  if (c.notNull) s += ' NOT NULL';
  return s;
}

export function criarSequencia(e) {
  return `CREATE SEQUENCE IF NOT EXISTS ${e.sequencia} START WITH 1 INCREMENT BY 50;`;
}

export function criarTabela(e) {
  const linhas = e.colunas.map((c) => '    ' + definicaoColuna(c));
  linhas.push('    PRIMARY KEY (id)');
  for (const c of e.colunas.filter((x) => x.unique)) linhas.push(`    CONSTRAINT uk_${simples(e.tabela)}_${c.nome.toLowerCase()} UNIQUE (${c.nome})`);
  for (const c of e.colunas.filter((x) => x.fk)) linhas.push(`    CONSTRAINT fk_${simples(e.tabela)}_${c.nome.toLowerCase().replace(/_id$/, '')} FOREIGN KEY (${c.nome}) REFERENCES ${c.fk} (id)`);
  return `CREATE TABLE IF NOT EXISTS ${e.tabela} (\n${linhas.join(',\n')}\n);`;
}

export function adicionarColuna(e, c) {
  return `ALTER TABLE ${e.tabela} ADD COLUMN IF NOT EXISTS ${definicaoColuna(c, { paraAlter: true })};`;
}
