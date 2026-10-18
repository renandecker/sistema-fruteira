-- retaguarda-service: índices da tela de auditoria, das notas fiscais e do financeiro.

CREATE INDEX IF NOT EXISTS ix_auditoria_data ON Auditoria (data);
CREATE INDEX IF NOT EXISTS ix_auditoria_entidade ON Auditoria (entidade, entidadeId);
CREATE INDEX IF NOT EXISTS ix_nota_venda ON Nota (vendaId);
CREATE INDEX IF NOT EXISTS ix_conta_vencimento ON Conta (vencimento);
CREATE INDEX IF NOT EXISTS ix_cotacao_produto ON Cotacao (produtoNome);
