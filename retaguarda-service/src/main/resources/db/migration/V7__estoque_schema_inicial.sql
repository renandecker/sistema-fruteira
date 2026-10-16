-- estoque-service: schema inicial (espelha as entidades @Entity do serviço).
-- Idempotente (IF NOT EXISTS): num banco que já foi criado pelo Hibernate (antes do Flyway) nada é recriado.
-- SQL portátil: roda igual no H2 (desenvolvimento) e no PostgreSQL (produção). NUNCA edite uma migration já aplicada: crie a próxima (ver database/nova-migration.sh).

CREATE SEQUENCE IF NOT EXISTS Saldo_SEQ START WITH 1 INCREMENT BY 50;
CREATE SEQUENCE IF NOT EXISTS Movimento_SEQ START WITH 1 INCREMENT BY 50;

CREATE TABLE IF NOT EXISTS Saldo (
    id bigint NOT NULL,
    produtoId bigint,
    quantidade numeric(38,2),
    custoMedio numeric(38,2),
    PRIMARY KEY (id),
    CONSTRAINT uk_saldo_produtoid UNIQUE (produtoId)
);

CREATE TABLE IF NOT EXISTS Movimento (
    id bigint NOT NULL,
    produtoId bigint,
    tipo varchar(255),
    quantidade numeric(38,2),
    custoUnitario numeric(38,2),
    motivo varchar(255),
    fornecedorId bigint,
    fornecedor varchar(255),
    data timestamp(6),
    PRIMARY KEY (id)
);
