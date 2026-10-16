// Uso ÚNICO (já executado): gerou o V1 (schema) e o V2 (colunas de bancos antigos) a partir das entidades.
// Depois do V1, o schema evolui só por migrations novas escritas à mão (ou a partir da sugestão do testar-migrations).
import { mkdirSync, writeFileSync } from 'node:fs';
import { join } from 'node:path';
import { lerEntidades, SERVICOS } from './entidades.mjs';
import { criarSequencia, criarTabela, adicionarColuna } from './ddl.mjs';

const raiz = join(import.meta.dirname, '..', '..');
const todas = lerEntidades(raiz);
// FKs exigem a tabela de destino já criada: pai antes do filho
const ordem = (es) => [...es].sort((a, b) => (a.colunas.some((c) => c.fk) ? 1 : 0) - (b.colunas.some((c) => c.fk) ? 1 : 0));

for (const s of SERVICOS) {
  const es = ordem(todas.filter((e) => e.servico === s));
  const dir = join(raiz, s, 'src/main/resources/db/migration');
  mkdirSync(dir, { recursive: true });
  writeFileSync(join(dir, 'V1__schema_inicial.sql'),
`-- ${s}: schema inicial (espelha as entidades @Entity do serviço).
-- Idempotente (IF NOT EXISTS): num banco que já foi criado pelo Hibernate (antes do Flyway) nada é recriado.
-- SQL portátil: roda igual no H2 (desenvolvimento) e no PostgreSQL (produção). NUNCA edite uma migration já aplicada: crie a próxima (V<n+1>__...).

${es.map((e) => criarSequencia(e)).join('\n')}

${es.map((e) => criarTabela(e)).join('\n\n')}
`);
  const alters = es.flatMap((e) => e.colunas.filter((c) => !c.pk).map((c) => adicionarColuna(e, c)));
  writeFileSync(join(dir, 'V2__completa_colunas_banco_antigo.sql'),
`-- ${s}: completa bancos criados ANTES do Flyway (quando o Hibernate ajustava o schema sozinho).
-- Se alguma coluna ainda não existir lá, ela é adicionada; num banco novo, nada muda (todas já existem pelo V1).

${alters.join('\n')}
`);
  console.log(s, '->', es.length, 'tabelas,', alters.length, 'colunas conferidas');
}
