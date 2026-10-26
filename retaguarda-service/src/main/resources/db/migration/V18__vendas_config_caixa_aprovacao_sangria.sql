-- vendas-service: configuração do caixa (aprovação do supervisor na sangria, desligada por padrão) e quem aprovou cada sangria
-- SQL PORTÁTIL (H2 dev + PostgreSQL prod). Sem BEGIN/COMMIT. Idempotente (IF NOT EXISTS). NUNCA edite uma migration já aplicada: crie outra.

CREATE SEQUENCE IF NOT EXISTS ConfigCaixa_SEQ START WITH 1 INCREMENT BY 50;

CREATE TABLE IF NOT EXISTS ConfigCaixa (
    id bigint NOT NULL,
    sangriaExigeAprovacao boolean NOT NULL,
    atualizadoPor varchar(255),
    atualizadoEm timestamp(6),
    PRIMARY KEY (id)
);

ALTER TABLE MovimentoCaixa ADD COLUMN IF NOT EXISTS aprovacao varchar(255);
