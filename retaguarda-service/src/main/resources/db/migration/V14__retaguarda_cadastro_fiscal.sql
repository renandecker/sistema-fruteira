-- retaguarda-service: cadastro fiscal completo de clientes (PF/PJ) e fornecedores (IE, IM, e-mail e endereço)
-- SQL PORTÁTIL (H2 dev + PostgreSQL prod). Sem BEGIN/COMMIT. Idempotente (IF NOT EXISTS). NUNCA edite uma migration já aplicada: crie outra.

ALTER TABLE Fornecedor ADD COLUMN IF NOT EXISTS ie varchar(255);
ALTER TABLE Fornecedor ADD COLUMN IF NOT EXISTS im varchar(255);
ALTER TABLE Fornecedor ADD COLUMN IF NOT EXISTS email varchar(255);
ALTER TABLE Fornecedor ADD COLUMN IF NOT EXISTS logradouro varchar(255);
ALTER TABLE Fornecedor ADD COLUMN IF NOT EXISTS numero varchar(255);
ALTER TABLE Fornecedor ADD COLUMN IF NOT EXISTS bairro varchar(255);
ALTER TABLE Fornecedor ADD COLUMN IF NOT EXISTS municipio varchar(255);
ALTER TABLE Fornecedor ADD COLUMN IF NOT EXISTS cMun varchar(255);
ALTER TABLE Fornecedor ADD COLUMN IF NOT EXISTS uf varchar(255);
ALTER TABLE Fornecedor ADD COLUMN IF NOT EXISTS cep varchar(255);
ALTER TABLE Cliente ADD COLUMN IF NOT EXISTS tipo varchar(255);
ALTER TABLE Cliente ADD COLUMN IF NOT EXISTS ie varchar(255);
ALTER TABLE Cliente ADD COLUMN IF NOT EXISTS email varchar(255);
ALTER TABLE Cliente ADD COLUMN IF NOT EXISTS logradouro varchar(255);
ALTER TABLE Cliente ADD COLUMN IF NOT EXISTS numero varchar(255);
ALTER TABLE Cliente ADD COLUMN IF NOT EXISTS bairro varchar(255);
ALTER TABLE Cliente ADD COLUMN IF NOT EXISTS municipio varchar(255);
ALTER TABLE Cliente ADD COLUMN IF NOT EXISTS cMun varchar(255);
ALTER TABLE Cliente ADD COLUMN IF NOT EXISTS uf varchar(255);
ALTER TABLE Cliente ADD COLUMN IF NOT EXISTS cep varchar(255);
