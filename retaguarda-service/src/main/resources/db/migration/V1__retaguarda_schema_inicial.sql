-- retaguarda-service: schema inicial (espelha as entidades @Entity do serviço).
-- Idempotente (IF NOT EXISTS): num banco que já foi criado pelo Hibernate (antes do Flyway) nada é recriado.
-- SQL portátil: roda igual no H2 (desenvolvimento) e no PostgreSQL (produção). NUNCA edite uma migration já aplicada: crie a próxima (ver database/nova-migration.sh).

CREATE SEQUENCE IF NOT EXISTS Fornecedor_SEQ START WITH 1 INCREMENT BY 50;
CREATE SEQUENCE IF NOT EXISTS Cliente_SEQ START WITH 1 INCREMENT BY 50;
CREATE SEQUENCE IF NOT EXISTS Cotacao_SEQ START WITH 1 INCREMENT BY 50;
CREATE SEQUENCE IF NOT EXISTS Conta_SEQ START WITH 1 INCREMENT BY 50;
CREATE SEQUENCE IF NOT EXISTS Nota_SEQ START WITH 1 INCREMENT BY 50;
CREATE SEQUENCE IF NOT EXISTS Auditoria_SEQ START WITH 1 INCREMENT BY 50;
CREATE SEQUENCE IF NOT EXISTS Usuario_SEQ START WITH 1 INCREMENT BY 50;

CREATE TABLE IF NOT EXISTS Fornecedor (
    id bigint NOT NULL,
    nome varchar(255),
    documento varchar(255),
    tipo varchar(255),
    telefone varchar(255),
    ativo boolean DEFAULT true,
    PRIMARY KEY (id)
);

CREATE TABLE IF NOT EXISTS Cliente (
    id bigint NOT NULL,
    nome varchar(255),
    cpf varchar(255),
    telefone varchar(255),
    cashback numeric(38,2),
    ativo boolean DEFAULT true,
    PRIMARY KEY (id),
    CONSTRAINT uk_cliente_cpf UNIQUE (cpf)
);

CREATE TABLE IF NOT EXISTS Cotacao (
    id bigint NOT NULL,
    produtoNome varchar(255),
    fornecedor varchar(255),
    preco numeric(38,2),
    data date,
    ativo boolean DEFAULT true,
    PRIMARY KEY (id)
);

CREATE TABLE IF NOT EXISTS Conta (
    id bigint NOT NULL,
    tipo varchar(255),
    descricao varchar(255),
    categoria varchar(255),
    valor numeric(38,2),
    vencimento date,
    dataPagamento date,
    pago boolean NOT NULL,
    ativo boolean DEFAULT true,
    PRIMARY KEY (id)
);

CREATE TABLE IF NOT EXISTS Nota (
    id bigint NOT NULL,
    vendaId bigint,
    numero integer,
    chave varchar(255),
    protocolo varchar(255),
    status varchar(255),
    cpf varchar(255),
    valor numeric(38,2),
    data timestamp(6),
    cartoes varchar(2000),
    nome varchar(255),
    cStat integer,
    tpEmis integer,
    xMotivo varchar(500),
    cNF varchar(255),
    dhAutorizacao varchar(255),
    provider varchar(255),
    erroCpf boolean,
    qrCode varchar(600),
    urlChave varchar(255),
    xml varchar(200000),
    payload varchar(20000),
    PRIMARY KEY (id)
);

CREATE TABLE IF NOT EXISTS Auditoria (
    id bigint NOT NULL,
    data timestamp(6),
    usuario varchar(255),
    perfil varchar(255),
    acao varchar(255),
    entidade varchar(255),
    entidadeId varchar(255),
    origem varchar(255),
    descricao varchar(1000),
    antes varchar(4000),
    depois varchar(4000),
    PRIMARY KEY (id)
);

CREATE TABLE IF NOT EXISTS Usuario (
    id bigint NOT NULL,
    nome varchar(255),
    login varchar(255),
    perfil varchar(255),
    ativo boolean NOT NULL,
    pinHash varchar(255),
    PRIMARY KEY (id),
    CONSTRAINT uk_usuario_login UNIQUE (login)
);
