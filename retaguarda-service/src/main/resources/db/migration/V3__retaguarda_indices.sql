-- retaguarda-service: índices da tela de auditoria, das notas fiscais e do financeiro.

CREATE INDEX IF NOT EXISTS ix_auditoria_data ON Entities$Auditoria (data);
CREATE INDEX IF NOT EXISTS ix_auditoria_entidade ON Entities$Auditoria (entidade, entidadeId);
CREATE INDEX IF NOT EXISTS ix_nota_venda ON Entities$Nota (vendaId);
CREATE INDEX IF NOT EXISTS ix_conta_vencimento ON Entities$Conta (vencimento);
CREATE INDEX IF NOT EXISTS ix_cotacao_produto ON Entities$Cotacao (produtoNome);
