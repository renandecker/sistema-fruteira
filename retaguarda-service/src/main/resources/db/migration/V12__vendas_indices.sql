-- vendas-service: índices de relatórios, fechamento de caixa e conciliação de TEF/PIX.

CREATE INDEX IF NOT EXISTS ix_venda_data ON Venda (data);
CREATE INDEX IF NOT EXISTS ix_itemvenda_venda ON ItemVenda (venda_id);
CREATE INDEX IF NOT EXISTS ix_itemvenda_produto ON ItemVenda (produtoId);
CREATE INDEX IF NOT EXISTS ix_pagamentovenda_venda ON PagamentoVenda (venda_id);
CREATE INDEX IF NOT EXISTS ix_pixcobranca_status ON PixCobranca (status);
CREATE INDEX IF NOT EXISTS ix_pixcobranca_venda ON PixCobranca (vendaId);
CREATE INDEX IF NOT EXISTS ix_tefTransacao_status ON TefTransacao (status);
CREATE INDEX IF NOT EXISTS ix_tefTransacao_venda ON TefTransacao (vendaId);
CREATE INDEX IF NOT EXISTS ix_movimentocaixa_turno ON MovimentoCaixa (turnoId);
