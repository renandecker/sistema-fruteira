-- retaguarda-service: tentativas de impressão do DANFE (NFC-e) e resumo na nota, para reimprimir e auditar o fluxo.
-- SQL PORTÁTIL (H2 dev + PostgreSQL prod). Sem BEGIN/COMMIT. Idempotente (IF NOT EXISTS). NUNCA edite uma migration já aplicada: crie outra.

CREATE SEQUENCE IF NOT EXISTS NotaImpressao_SEQ START WITH 1 INCREMENT BY 50;

CREATE TABLE IF NOT EXISTS NotaImpressao (
    id bigint NOT NULL,
    notaId bigint,
    tentativa integer,
    data timestamp(6),
    usuario varchar(255),
    terminal varchar(255),
    origem varchar(255),
    resultado varchar(255),
    erro varchar(500),
    PRIMARY KEY (id)
);
CREATE INDEX IF NOT EXISTS idx_notaimpressao_nota ON NotaImpressao (notaId);

ALTER TABLE Nota ADD COLUMN IF NOT EXISTS tentativasImpressao integer;
ALTER TABLE Nota ADD COLUMN IF NOT EXISTS impressa boolean;
ALTER TABLE Nota ADD COLUMN IF NOT EXISTS ultimaImpressao varchar(255);
