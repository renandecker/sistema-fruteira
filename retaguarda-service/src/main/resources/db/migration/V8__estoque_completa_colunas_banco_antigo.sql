-- estoque-service: completa bancos criados ANTES do Flyway (quando o Hibernate ajustava o schema sozinho).
-- Se alguma coluna ainda não existir lá, ela é adicionada; num banco novo, nada muda (todas já existem pela migration de schema inicial).

ALTER TABLE Saldo ADD COLUMN IF NOT EXISTS produtoId bigint;
ALTER TABLE Saldo ADD COLUMN IF NOT EXISTS quantidade numeric(38,2);
ALTER TABLE Saldo ADD COLUMN IF NOT EXISTS custoMedio numeric(38,2);
ALTER TABLE Movimento ADD COLUMN IF NOT EXISTS produtoId bigint;
ALTER TABLE Movimento ADD COLUMN IF NOT EXISTS tipo varchar(255);
ALTER TABLE Movimento ADD COLUMN IF NOT EXISTS quantidade numeric(38,2);
ALTER TABLE Movimento ADD COLUMN IF NOT EXISTS custoUnitario numeric(38,2);
ALTER TABLE Movimento ADD COLUMN IF NOT EXISTS motivo varchar(255);
ALTER TABLE Movimento ADD COLUMN IF NOT EXISTS fornecedorId bigint;
ALTER TABLE Movimento ADD COLUMN IF NOT EXISTS fornecedor varchar(255);
ALTER TABLE Movimento ADD COLUMN IF NOT EXISTS data timestamp(6);
