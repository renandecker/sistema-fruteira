-- retaguarda-service: NF-e (modelo 55) na mesma tabela de notas: modelo, série e contador de cartas de correção
-- SQL PORTÁTIL (H2 dev + PostgreSQL prod). Sem BEGIN/COMMIT. Idempotente (IF NOT EXISTS). NUNCA edite uma migration já aplicada: crie outra.

ALTER TABLE Nota ADD COLUMN IF NOT EXISTS modelo varchar(255);
ALTER TABLE Nota ADD COLUMN IF NOT EXISTS serie integer;
ALTER TABLE Nota ADD COLUMN IF NOT EXISTS seqCce integer;
