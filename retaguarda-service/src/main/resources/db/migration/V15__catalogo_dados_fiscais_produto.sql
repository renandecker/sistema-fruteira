-- catalogo-service: classificação fiscal própria do produto (CFOP, CST/CSOSN, origem, PIS/COFINS, IBS/CBS)
-- SQL PORTÁTIL (H2 dev + PostgreSQL prod). Sem BEGIN/COMMIT. Idempotente (IF NOT EXISTS). NUNCA edite uma migration já aplicada: crie outra.

ALTER TABLE Produto ADD COLUMN IF NOT EXISTS cfop varchar(4);
ALTER TABLE Produto ADD COLUMN IF NOT EXISTS cstIcms varchar(3);
ALTER TABLE Produto ADD COLUMN IF NOT EXISTS origemMercadoria varchar(1);
ALTER TABLE Produto ADD COLUMN IF NOT EXISTS cstPis varchar(2);
ALTER TABLE Produto ADD COLUMN IF NOT EXISTS cstCofins varchar(2);
ALTER TABLE Produto ADD COLUMN IF NOT EXISTS aliqIcms numeric(38,2);
ALTER TABLE Produto ADD COLUMN IF NOT EXISTS aliqPis numeric(38,2);
ALTER TABLE Produto ADD COLUMN IF NOT EXISTS aliqCofins numeric(38,2);
ALTER TABLE Produto ADD COLUMN IF NOT EXISTS cstIbsCbs varchar(3);
ALTER TABLE Produto ADD COLUMN IF NOT EXISTS cClassTrib varchar(6);
ALTER TABLE Produto ADD COLUMN IF NOT EXISTS pRedIbsCbs numeric(38,2);
