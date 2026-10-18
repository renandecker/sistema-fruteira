-- retaguarda-service: caderno de fiado
-- Escreva SQL PORTÁTIL (roda no H2 de desenvolvimento e no PostgreSQL de produção): sem "::", SERIAL, ON CONFLICT, ILIKE, RETURNING, bloco DO.
-- Sem BEGIN/COMMIT (o Flyway já cuida da transação). Prefira IF NOT EXISTS. NUNCA edite uma migration já aplicada: crie outra.
-- Conferir: ./database/testar-migrations.sh (compara com as @Entity e aplica num PostgreSQL de teste)


-- Caderno (fiado): livro-razão por cliente. Saldo = DIVIDA − (PAGAMENTO + PERDAO), ignorando os cancelados.
ALTER TABLE Cliente ADD COLUMN IF NOT EXISTS limiteCaderno numeric(38,2);

CREATE SEQUENCE IF NOT EXISTS CadernoLancamento_SEQ START WITH 1 INCREMENT BY 50;

CREATE TABLE IF NOT EXISTS CadernoLancamento (
    id bigint NOT NULL,
    cpf varchar(255),
    tipo varchar(255),
    valor numeric(38,2),
    vendaId bigint,
    data timestamp,
    usuario varchar(255),
    obs varchar(255),
    cancelado boolean DEFAULT false,
    PRIMARY KEY (id)
);

CREATE INDEX IF NOT EXISTS ix_caderno_cpf ON CadernoLancamento (cpf);
CREATE INDEX IF NOT EXISTS ix_caderno_venda ON CadernoLancamento (vendaId);
