-- vendas-service: parcelas do crédito no pagamento (NFC-e a prazo)
-- SQL PORTÁTIL (H2 dev + PostgreSQL prod). Sem BEGIN/COMMIT. Idempotente (IF NOT EXISTS). NUNCA edite uma migration já aplicada: crie outra.

ALTER TABLE PagamentoVenda ADD COLUMN IF NOT EXISTS parcelas integer;
