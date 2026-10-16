// Cria o próximo arquivo de migration do Flyway, já com o número certo. O Flyway roda SÓ no retaguarda-service (dono das migrations
// dos 4 serviços, que dividem o banco): o arquivo fica lá, com numeração única e o nome do serviço no arquivo.
//   node database/nova-migration.mjs <servico> "<descricao>"     (ou ./database/nova-migration.sh | .bat)
//   ex.: ./database/nova-migration.sh vendas "adiciona campo observacao na venda"
// Serviços: catalogo, estoque, vendas, retaguarda. Gera retaguarda-service/.../db/migration/V<próximo>__<servico>_descricao_sem_acento.sql.
import { existsSync, mkdirSync, readdirSync, writeFileSync } from 'node:fs';
import { join } from 'node:path';

const raiz = join(import.meta.dirname, '..');
const SERVICOS = ['catalogo', 'estoque', 'vendas', 'retaguarda'];
const [servico, ...resto] = process.argv.slice(2);
const descricao = resto.join(' ').trim();
if (!SERVICOS.includes(servico) || !descricao) {
  console.error(`Uso: nova-migration <${SERVICOS.join('|')}> "<descrição curta>"\n  ex.: nova-migration vendas "adiciona campo observacao na venda"`);
  process.exit(1);
}
const nome = descricao.normalize('NFD').replace(/[̀-ͯ]/g, '').toLowerCase().replace(/[^a-z0-9]+/g, '_').replace(/^_+|_+$/g, '');
if (!nome) { console.error('A descrição precisa ter letras ou números.'); process.exit(1); }

const dir = join(raiz, 'retaguarda-service', 'src/main/resources/db/migration');
mkdirSync(dir, { recursive: true });
const versoes = readdirSync(dir).map((n) => /^V(\d+)__/.exec(n)?.[1]).filter(Boolean).map(Number);
const proxima = (versoes.length ? Math.max(...versoes) : 0) + 1;
const arquivo = join(dir, `V${proxima}__${servico}_${nome}.sql`);
if (existsSync(arquivo)) { console.error(`Já existe: ${arquivo}`); process.exit(1); }

writeFileSync(arquivo, `-- ${servico}-service: ${descricao}
-- Escreva SQL PORTÁTIL (roda no H2 de desenvolvimento e no PostgreSQL de produção): sem "::", SERIAL, ON CONFLICT, ILIKE, RETURNING, bloco DO.
-- Sem BEGIN/COMMIT (o Flyway já cuida da transação). Prefira IF NOT EXISTS. NUNCA edite uma migration já aplicada: crie outra.
-- Conferir: ./database/testar-migrations.sh (compara com as @Entity e aplica num PostgreSQL de teste)

`);
console.log(`✔ Criado: retaguarda-service/src/main/resources/db/migration/V${proxima}__${servico}_${nome}.sql`);
console.log('  Edite o SQL, ajuste a @Entity correspondente e rode ./database/testar-migrations.sh');
