-- vendas-service: schema inicial (espelha as entidades @Entity do serviço).
-- Idempotente (IF NOT EXISTS): num banco que já foi criado pelo Hibernate (antes do Flyway) nada é recriado.
-- SQL portátil: roda igual no H2 (desenvolvimento) e no PostgreSQL (produção). NUNCA edite uma migration já aplicada: crie a próxima (ver database/nova-migration.sh).

CREATE SEQUENCE IF NOT EXISTS MovimentoCaixa_SEQ START WITH 1 INCREMENT BY 50;
CREATE SEQUENCE IF NOT EXISTS PixCobranca_SEQ START WITH 1 INCREMENT BY 50;
CREATE SEQUENCE IF NOT EXISTS TefTransacao_SEQ START WITH 1 INCREMENT BY 50;
CREATE SEQUENCE IF NOT EXISTS TurnoCaixa_SEQ START WITH 1 INCREMENT BY 50;
CREATE SEQUENCE IF NOT EXISTS Venda_SEQ START WITH 1 INCREMENT BY 50;
CREATE SEQUENCE IF NOT EXISTS ItemVenda_SEQ START WITH 1 INCREMENT BY 50;
CREATE SEQUENCE IF NOT EXISTS PagamentoVenda_SEQ START WITH 1 INCREMENT BY 50;

CREATE TABLE IF NOT EXISTS MovimentoCaixa (
    id bigint NOT NULL,
    turnoId bigint,
    tipo varchar(12),
    valor numeric(38,2),
    motivo varchar(200),
    operador varchar(255),
    data timestamp(6),
    acimaDoSaldo boolean NOT NULL,
    PRIMARY KEY (id)
);

CREATE TABLE IF NOT EXISTS PixCobranca (
    id bigint NOT NULL,
    txid varchar(255),
    valor numeric(38,2),
    descricao varchar(255),
    status varchar(255),
    provedor varchar(255),
    pixCopiaECola varchar(1000),
    location varchar(255),
    criadaEm timestamp(6),
    expiraEm timestamp(6),
    ultimaConsulta timestamp(6),
    endToEndId varchar(255),
    valorPago numeric(38,2),
    pagoEm timestamp(6),
    vendaId bigint,
    operador varchar(255),
    terminal varchar(255),
    idDevolucao varchar(255),
    devolvidaEm timestamp(6),
    PRIMARY KEY (id),
    CONSTRAINT uk_pixcobranca_txid UNIQUE (txid),
    CONSTRAINT uk_pixcobranca_endtoendid UNIQUE (endToEndId)
);

CREATE TABLE IF NOT EXISTS TefTransacao (
    id bigint NOT NULL,
    requisicao varchar(255),
    valor numeric(38,2),
    tipo varchar(255),
    parcelas integer,
    status varchar(255),
    vendaId bigint,
    nsu varchar(255),
    nsuHost varchar(255),
    autorizacao varchar(255),
    bandeira varchar(255),
    adquirente varchar(255),
    cnpjCredenciadora varchar(255),
    cartao varchar(255),
    terminal varchar(255),
    operador varchar(255),
    comprovanteCliente varchar(4000),
    comprovanteLoja varchar(4000),
    mensagem varchar(500),
    criadoEm timestamp(6),
    atualizadoEm timestamp(6),
    confirmadoEm timestamp(6),
    PRIMARY KEY (id),
    CONSTRAINT uk_teftransacao_requisicao UNIQUE (requisicao)
);

CREATE TABLE IF NOT EXISTS TurnoCaixa (
    id bigint NOT NULL,
    operador varchar(255) NOT NULL,
    abertura timestamp(6),
    fundoTroco numeric(38,2),
    fechamento timestamp(6),
    contadoFechamento numeric(38,2),
    esperadoFechamento numeric(38,2),
    diferenca numeric(38,2),
    status varchar(10),
    PRIMARY KEY (id)
);

CREATE TABLE IF NOT EXISTS Venda (
    id bigint NOT NULL,
    data timestamp(6),
    usuario varchar(255),
    cpfCliente varchar(255),
    total numeric(38,2),
    desconto numeric(38,2),
    status varchar(255),
    PRIMARY KEY (id)
);

CREATE TABLE IF NOT EXISTS ItemVenda (
    id bigint NOT NULL,
    venda_id bigint,
    produtoId bigint,
    nome varchar(255),
    quantidade numeric(38,2),
    precoUnit numeric(38,2),
    subtotal numeric(38,2),
    custoUnit numeric(38,2),
    precoOriginal numeric(38,2),
    descontoTotal numeric(38,2),
    promocao varchar(255),
    PRIMARY KEY (id),
    CONSTRAINT fk_itemvenda_venda FOREIGN KEY (venda_id) REFERENCES Venda (id)
);

CREATE TABLE IF NOT EXISTS PagamentoVenda (
    id bigint NOT NULL,
    venda_id bigint,
    meio varchar(255),
    valor numeric(38,2),
    operadora varchar(255),
    tefId bigint,
    nsu varchar(255),
    autorizacao varchar(255),
    bandeira varchar(255),
    adquirente varchar(255),
    cnpjCredenciadora varchar(255),
    pixTxid varchar(255),
    pixE2e varchar(255),
    PRIMARY KEY (id),
    CONSTRAINT fk_pagamentovenda_venda FOREIGN KEY (venda_id) REFERENCES Venda (id)
);
