-- estoque-service: índices do histórico de movimentos (extrato por produto e por data).

CREATE INDEX IF NOT EXISTS ix_movimento_produto ON Movimento (produtoId);
CREATE INDEX IF NOT EXISTS ix_movimento_data ON Movimento (data);
