-- catalogo-service: schema inicial (espelha as entidades @Entity do serviço).
-- Idempotente (IF NOT EXISTS): num banco que já foi criado pelo Hibernate (antes do Flyway) nada é recriado.
-- SQL portátil: roda igual no H2 (desenvolvimento) e no PostgreSQL (produção). NUNCA edite uma migration já aplicada: crie a próxima (ver database/nova-migration.sh).

CREATE SEQUENCE IF NOT EXISTS Categoria_SEQ START WITH 1 INCREMENT BY 50;
CREATE SEQUENCE IF NOT EXISTS NcmExecucao_SEQ START WITH 1 INCREMENT BY 50;
CREATE SEQUENCE IF NOT EXISTS Produto_SEQ START WITH 1 INCREMENT BY 50;
CREATE SEQUENCE IF NOT EXISTS Promocao_SEQ START WITH 1 INCREMENT BY 50;

CREATE TABLE IF NOT EXISTS Categoria (
    id bigint NOT NULL,
    nome varchar(255),
    ordem integer,
    ativo boolean DEFAULT true,
    PRIMARY KEY (id),
    CONSTRAINT uk_categoria_nome UNIQUE (nome)
);

CREATE TABLE IF NOT EXISTS NcmExecucao (
    id bigint NOT NULL,
    origem varchar(255),
    inicio timestamp(6),
    fim timestamp(6),
    total integer NOT NULL,
    validos integer NOT NULL,
    invalidos integer NOT NULL,
    vencidos integer NOT NULL,
    ausentes integer NOT NULL,
    naoVerificados integer NOT NULL,
    normalizados integer NOT NULL,
    erro varchar(500),
    PRIMARY KEY (id)
);

CREATE TABLE IF NOT EXISTS Produto (
    id bigint NOT NULL,
    nome varchar(255),
    unidade varchar(255),
    precoVarejo numeric(38,2),
    precoAtacado numeric(38,2),
    plu integer,
    codigoBarras varchar(255),
    imagem varchar(60),
    atalho varchar(1),
    categoria varchar(255),
    fotoUrl varchar(255),
    ncm varchar(255),
    ncmStatus varchar(20),
    ncmDescricao varchar(500),
    ncmFonte varchar(20),
    ncmFim date,
    ncmVerificadoEm timestamp(6),
    taxaPerdaPct numeric(38,2),
    custoMedio numeric(38,2),
    ativo boolean NOT NULL,
    PRIMARY KEY (id),
    CONSTRAINT uk_produto_plu UNIQUE (plu)
);

CREATE TABLE IF NOT EXISTS Promocao (
    id bigint NOT NULL,
    produtoId bigint,
    tipo varchar(255),
    valor numeric(38,2),
    inicio date,
    fim date,
    ativo boolean DEFAULT true,
    PRIMARY KEY (id)
);
