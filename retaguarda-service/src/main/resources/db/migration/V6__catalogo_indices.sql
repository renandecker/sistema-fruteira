-- catalogo-service: índices das buscas mais usadas (leitor de código de barras no PDV, aba/categoria, promoções por produto).
-- Exemplo de migration de evolução do schema: IF NOT EXISTS mantém o script seguro de reaplicar.

CREATE INDEX IF NOT EXISTS ix_produto_codigobarras ON Produto (codigoBarras);
CREATE INDEX IF NOT EXISTS ix_produto_categoria ON Produto (categoria);
CREATE INDEX IF NOT EXISTS ix_promocao_produto ON Promocao (produtoId);
