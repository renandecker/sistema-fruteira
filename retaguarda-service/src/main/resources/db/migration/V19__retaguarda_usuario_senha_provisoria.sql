-- retaguarda-service: login próprio (sem Keycloak). Senha criada/redefinida pelo gerente fica "provisória" até o usuário trocá-la.
-- SQL PORTÁTIL (H2 dev + PostgreSQL prod). Sem BEGIN/COMMIT. Idempotente (IF NOT EXISTS). NUNCA edite uma migration já aplicada: crie outra.

ALTER TABLE Usuario ADD COLUMN IF NOT EXISTS provisoria boolean DEFAULT false NOT NULL;
