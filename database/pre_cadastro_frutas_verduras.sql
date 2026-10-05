-- =====================================================================
--  Pré-cadastro de frutas, legumes, verduras e temperos mais comuns
--  PostgreSQL — banco da aplicação (catalogo-service) — 69 produtos, 5 categorias
-- =====================================================================
--  COMO USAR
--    1) Suba o catalogo-service em produção ao menos uma vez (ele cria as tabelas).
--    2) Execute:
--         psql -h localhost -U postgres -d fruteira -f database/pre_cadastro_frutas_verduras.sql
--       ou, com o Docker de produção:
--         docker compose -f docker-compose.prod.yml exec -T postgres psql -U postgres -d fruteira < database/pre_cadastro_frutas_verduras.sql
--
--  O QUE FAZ
--    - Cria as categorias (aba do PDV) e os produtos com unidade, perda média, NCM sugerido e imagem do catálogo.
--    - É IDEMPOTENTE: pode rodar de novo; ignora categoria/produto cujo nome já existe (sem diferenciar maiúsculas).
--    - O PLU recebe a ordem de popularidade (1 = Banana...). Se o PLU já estiver em uso, o produto fica sem PLU.
--
--  ATENÇÃO
--    - Preços são APENAS REFERÊNCIA (R$/kg, un. ou maço): ajuste na tela Produtos e preços.
--    - NCM é sugerido; valide com seu contador antes de emitir nota (vazio = a definir).
--    - Custo médio começa em 0 e é calculado nas entradas de estoque.
--    - A coluna "imagem" usa as chaves do catálogo de imagens do cadastro de produtos.
-- =====================================================================
SET client_encoding = 'UTF8';
BEGIN;

CREATE TEMP TABLE pre_categoria (nome text, ordem int) ON COMMIT DROP;
INSERT INTO pre_categoria (nome, ordem) VALUES
  ('Frutas', 1),
  ('Legumes', 2),
  ('Verduras', 3),
  ('Raízes e Tubérculos', 4),
  ('Temperos e Ervas', 5);

CREATE TEMP TABLE pre_produto (ordem int, nome text, unidade text, categoria text, preco numeric(10,2), perda numeric(5,2), ncm text, imagem text) ON COMMIT DROP;
INSERT INTO pre_produto (ordem, nome, unidade, categoria, preco, perda, ncm, imagem) VALUES
  (1, 'Banana Prata', 'KG', 'Frutas', 7.90, 8, '08039000', 'banana'),
  (2, 'Tomate Longa Vida', 'KG', 'Legumes', 9.90, 10, '07020000', 'tomate'),
  (3, 'Maçã Fuji', 'KG', 'Frutas', 11.90, 6, '08081000', 'maca'),
  (4, 'Laranja Pera', 'KG', 'Frutas', 5.90, 6, '08051000', 'laranja'),
  (5, 'Batata Inglesa', 'KG', 'Raízes e Tubérculos', 6.90, 4, '07019000', 'batata'),
  (6, 'Cebola', 'KG', 'Raízes e Tubérculos', 5.90, 4, '07031019', 'cebola'),
  (7, 'Cenoura', 'KG', 'Raízes e Tubérculos', 6.50, 6, '07061000', 'cenoura'),
  (8, 'Alface Crespa', 'UN', 'Verduras', 3.50, 18, '07051900', 'alface-crespa'),
  (9, 'Limão Taiti', 'KG', 'Frutas', 6.90, 5, '08055000', 'limao'),
  (10, 'Melancia', 'KG', 'Frutas', 4.99, 5, '08071100', 'melancia'),
  (11, 'Mamão Papaya', 'KG', 'Frutas', 8.90, 10, '08072000', 'mamao'),
  (12, 'Uva Thompson', 'KG', 'Frutas', 14.90, 8, '08061000', 'uva'),
  (13, 'Morango', 'KG', 'Frutas', 22.90, 15, '08101000', 'morango'),
  (14, 'Abacaxi Pérola', 'UN', 'Frutas', 7.90, 8, '08043000', 'abacaxi'),
  (15, 'Manga Palmer', 'KG', 'Frutas', 8.90, 8, '08045020', 'manga'),
  (16, 'Pera Williams', 'KG', 'Frutas', 12.90, 8, '08083000', 'pera'),
  (17, 'Pepino', 'KG', 'Legumes', 5.90, 8, '07070000', 'pepino'),
  (18, 'Pimentão Verde', 'KG', 'Legumes', 9.90, 8, '07096000', 'pimentao'),
  (19, 'Berinjela', 'KG', 'Legumes', 7.90, 8, '07093000', 'berinjela'),
  (20, 'Abobrinha Italiana', 'KG', 'Legumes', 6.90, 8, '07099300', 'abobrinha'),
  (21, 'Brócolis', 'MACO', 'Legumes', 6.90, 15, '07041000', 'brocolis'),
  (22, 'Couve-flor', 'UN', 'Legumes', 9.90, 12, '07041000', 'couve-flor'),
  (23, 'Repolho Verde', 'UN', 'Verduras', 6.90, 10, '07049010', 'repolho'),
  (24, 'Couve Manteiga', 'MACO', 'Verduras', 3.50, 20, '07049090', 'couve'),
  (25, 'Rúcula', 'MACO', 'Verduras', 3.90, 20, NULL, 'rucula'),
  (26, 'Agrião', 'MACO', 'Verduras', 3.90, 20, NULL, 'agriao'),
  (27, 'Espinafre', 'MACO', 'Verduras', 4.50, 20, '07097000', 'espinafre'),
  (28, 'Acelga', 'MACO', 'Verduras', 4.50, 18, NULL, 'acelga'),
  (29, 'Almeirão', 'MACO', 'Verduras', 3.90, 20, NULL, 'almeirao'),
  (30, 'Alface Americana', 'UN', 'Verduras', 4.50, 18, '07051100', 'alface-americana'),
  (31, 'Batata-doce', 'KG', 'Raízes e Tubérculos', 6.90, 4, '07142000', 'batata-doce'),
  (32, 'Beterraba', 'KG', 'Raízes e Tubérculos', 5.90, 6, '07069000', 'beterraba'),
  (33, 'Mandioca (Aipim)', 'KG', 'Raízes e Tubérculos', 5.90, 6, '07141000', 'mandioca'),
  (34, 'Inhame', 'KG', 'Raízes e Tubérculos', 12.90, 6, '07143000', 'inhame'),
  (35, 'Alho', 'KG', 'Raízes e Tubérculos', 39.90, 6, '07032090', 'alho'),
  (36, 'Gengibre', 'KG', 'Raízes e Tubérculos', 24.90, 6, '09101100', 'gengibre'),
  (37, 'Rabanete', 'MACO', 'Raízes e Tubérculos', 4.50, 12, '07069000', 'rabanete'),
  (38, 'Cebola Roxa', 'KG', 'Raízes e Tubérculos', 8.90, 4, '07031019', 'cebola-roxa'),
  (39, 'Milho Verde', 'UN', 'Legumes', 2.50, 8, '07099910', 'milho'),
  (40, 'Abóbora Cabotiá', 'KG', 'Legumes', 5.90, 6, '07099300', 'abobora'),
  (41, 'Chuchu', 'KG', 'Legumes', 4.90, 8, '07099990', 'chuchu'),
  (42, 'Vagem', 'KG', 'Legumes', 14.90, 10, '07082000', 'vagem'),
  (43, 'Quiabo', 'KG', 'Legumes', 14.90, 10, '07099990', 'quiabo'),
  (44, 'Jiló', 'KG', 'Legumes', 12.90, 10, '07099990', 'jilo'),
  (45, 'Pimenta Dedo-de-moça', 'KG', 'Legumes', 29.90, 8, '07096000', 'pimenta'),
  (46, 'Pimentão Vermelho', 'KG', 'Legumes', 14.90, 8, '07096000', 'pimentao-vermelho'),
  (47, 'Pimentão Amarelo', 'KG', 'Legumes', 14.90, 8, '07096000', 'pimentao-amarelo'),
  (48, 'Champignon', 'UN', 'Legumes', 9.90, 10, '07095100', 'cogumelo'),
  (49, 'Tomate Cereja', 'KG', 'Legumes', 19.90, 10, '07020000', 'tomate-cereja'),
  (50, 'Goiaba', 'KG', 'Frutas', 9.90, 10, '08045010', 'goiaba'),
  (51, 'Maracujá Azedo', 'KG', 'Frutas', 14.90, 10, NULL, 'maracuja'),
  (52, 'Kiwi', 'KG', 'Frutas', 24.90, 8, '08105000', 'kiwi'),
  (53, 'Abacate', 'KG', 'Frutas', 9.90, 10, '08044000', 'abacate'),
  (54, 'Coco Verde', 'UN', 'Frutas', 5.90, 5, '08011900', 'coco'),
  (55, 'Pêssego', 'KG', 'Frutas', 14.90, 10, '08093000', 'pessego'),
  (56, 'Ameixa', 'KG', 'Frutas', 16.90, 10, '08094000', 'ameixa'),
  (57, 'Caqui', 'KG', 'Frutas', 11.90, 12, '08107000', 'caqui'),
  (58, 'Figo', 'KG', 'Frutas', 29.90, 15, '08042000', 'figo'),
  (59, 'Acerola', 'KG', 'Frutas', 14.90, 15, NULL, 'acerola'),
  (60, 'Tangerina Ponkan', 'KG', 'Frutas', 6.90, 6, '08052100', 'tangerina'),
  (61, 'Melão Amarelo', 'KG', 'Frutas', 5.90, 6, '08071900', 'melao'),
  (62, 'Maçã Verde', 'KG', 'Frutas', 12.90, 6, '08081000', 'maca-verde'),
  (63, 'Banana Nanica', 'KG', 'Frutas', 6.90, 8, '08039000', 'banana-nanica'),
  (64, 'Cheiro-verde', 'MACO', 'Temperos e Ervas', 3.00, 20, NULL, 'cheiro-verde'),
  (65, 'Salsinha', 'MACO', 'Temperos e Ervas', 2.50, 20, NULL, 'salsa'),
  (66, 'Coentro', 'MACO', 'Temperos e Ervas', 2.50, 20, NULL, 'coentro'),
  (67, 'Cebolinha', 'MACO', 'Temperos e Ervas', 2.50, 20, NULL, 'cebolinha'),
  (68, 'Hortelã', 'MACO', 'Temperos e Ervas', 3.00, 20, NULL, 'hortela'),
  (69, 'Manjericão', 'MACO', 'Temperos e Ervas', 3.50, 20, NULL, 'manjericao');

-- Categorias (as novas entram depois das já existentes)
INSERT INTO categoria (id, nome, ordem, ativo)
SELECT nextval('categoria_seq'), c.nome, c.ordem + (SELECT COALESCE(MAX(ordem), 0) FROM categoria), TRUE
FROM pre_categoria c
WHERE NOT EXISTS (SELECT 1 FROM categoria x WHERE lower(x.nome) = lower(c.nome))
ORDER BY c.ordem;

-- Produtos
INSERT INTO produto (id, nome, unidade, categoria, precoVarejo, plu, ncm, taxaPerdaPct, custoMedio, ativo, imagem)
SELECT nextval('produto_seq'),
       t.nome,
       t.unidade,
       COALESCE((SELECT x.nome FROM categoria x WHERE lower(x.nome) = lower(t.categoria) LIMIT 1), t.categoria),
       t.preco,
       CASE WHEN EXISTS (SELECT 1 FROM produto p WHERE p.plu = t.ordem) THEN NULL ELSE t.ordem END,
       t.ncm,
       t.perda,
       0,
       TRUE,
       t.imagem
FROM pre_produto t
WHERE NOT EXISTS (SELECT 1 FROM produto p WHERE lower(p.nome) = lower(t.nome))
ORDER BY t.ordem;

COMMIT;

-- Conferência
SELECT categoria, count(*) AS produtos FROM produto WHERE ativo GROUP BY categoria ORDER BY categoria;
