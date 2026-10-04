# Sistema Fruteira — React + Quarkus (microsserviços)

## Rodar tudo de uma vez
- Linux/macOS: `./iniciar.sh` (Ctrl+C encerra; `./parar.sh` se sobrar processo)
- Windows: duplo clique em `iniciar.bat` (`parar.bat` encerra)

## Rodar manualmente (3 terminais + front)
```
cd catalogo-service && mvn quarkus:dev     # :8081  (Swagger: /q/swagger-ui)
cd estoque-service  && mvn quarkus:dev     # :8082
cd vendas-service   && mvn quarkus:dev     # :8083
cd retaguarda-service && mvn quarkus:dev   # :8084  (fornecedores, clientes, cotações, contas, NFC-e, usuários)
cd pdv-web && npm i && npm run dev         # :5173
```
Requer JDK 21, Maven e Docker (Em dev o banco é H2 em arquivo (pasta data/ de cada serviço), sem Docker). Cada serviço usa seu próprio schema/banco.
Sem balança física: no console do navegador `__simulaPeso(0.350)`.

## Já implementado
| Requisito | Onde |
|---|---|
| Leitura de balança por cabo | `pdv-web/src/balanca.js` (Web Serial) |
| PLU rápido (digitar `1`+Enter) e botões touch com foto | `PDV.jsx` |
| Venda por kg / fracionada, preço calculado no servidor | `VendaResource` |
| Trava de peso digitado manualmente | `VendaResource.finalizar` (422) |
| Multimeios + troco + QR PIX (demo) | `PDV.jsx > Pagamento` |
| Fator de conversão (caixa → kg) e custo médio | `EstoqueResource.entrada` |
| Perdas/avarias + relatório | `EstoqueResource` |
| Produção/fracionamento (abacaxi → bandeja) | `EstoqueResource.producao` |
| Desconto por validade / margem por categoria c/ taxa de perda | `ProdutoResource` |
| Curva ABC | `GET /vendas/relatorio/abc` |
| Cancelamento com PIN de gerente | `VendaResource.cancelar` |
| Fechamento de caixa cego | `POST /vendas/caixa/fechamento` |
| Fila offline com sincronização | `offline.js` |

## Ambientes (`.env`)
Copie `.env.example` para `.env` e escolha `APP_ENV`:

| | `desenvolvimento` | `producao` |
|---|---|---|
| Login | tela local (gerente/gerente, supervisor/supervisor, operador/operador) | **Keycloak (OIDC + PKCE)** |
| APIs Quarkus | abertas (OIDC desligado) | exigem Bearer token; **perfil validado por rota** |
| Banco | H2 em arquivo (`data/`) | PostgreSQL (`DB_URL`, `DB_USER`, `DB_PASS`) |
| Dados de exemplo | sim | não |
| Execução | `quarkus:dev` + Vite | jars (`mvn package`) + front buildado (`vite preview` ou **nginx/HTTPS** se `USE_NGINX=true`) |
| Docker | não precisa | Keycloak + PostgreSQL (+ nginx opcional) — `docker-compose.prod.yml` |

`iniciar.sh` / `iniciar.bat` leem o `.env` e fazem o resto. Em produção eles recusam senhas que comecem com `troque`.

### Produção: como funciona
1. **Keycloak** sobe com o realm `fruteira` importado de `keycloak/fruteira-realm.json`: roles `gerente`, `supervisor`, `operador`, client público `pdv-web` (PKCE) e client `fruteira-api` (bearer-only). Usuários iniciais `gerente`/`supervisor`/`operador` com senha temporária `trocar123` (o Keycloak exige trocar no 1º acesso).
2. **Front** (`src/auth.js`): `login-required` no Keycloak, anexa `Authorization: Bearer` em todo `/api/*`, renova o token e define o perfil pela role mais alta. Em `Usuários e perfis`, abre o console do Keycloak.
3. **Quarkus** (`application.properties`, bloco `%prod`): `quarkus-oidc` valida o token e lê as roles de `realm_access/roles`. As políticas por rota/método ficam em `quarkus.http.auth.permission.*`:

| Serviço | Operador | Supervisor | Gerente |
|---|---|---|---|
| catalogo | ler produtos | criar/alterar produtos, preços, desconto | — |
| estoque | baixa (venda), consultar saldo | entrada, produção | relatórios de perdas |
| vendas | vender, fechar caixa | listar vendas, cancelar | relatórios (ABC, DRE, sugestão) |
| retaguarda | CPF/cashback, emitir NFC-e | clientes, NFC-e | fornecedores, cotações, contas |

   O token do usuário é repassado nas chamadas entre microsserviços (`propagateHeaders=Authorization`). Qualquer rota não listada exige apenas estar autenticado.

### Produção local, sem HTTPS (padrão: `USE_NGINX=false`)
O front buildado é servido por `vite preview` em `http://localhost:5173` e o Keycloak em `http://localhost:8180`. O realm usa `FRONT_URL` nos `redirectUris`. **Serve só para testar a produção na sua máquina** — não use na internet.

### HTTPS com nginx (opcional: `USE_NGINX=true`)
```
Internet ──443──> nginx ──┬─ https://FRONT_DOMAIN      -> pdv-web/dist (SPA) e /api/<serviço>/ -> Quarkus no host (8081-8084)
                          └─ https://KEYCLOAK_DOMAIN   -> Keycloak (contêiner)
```
1. **DNS:** aponte `FRONT_DOMAIN` (ex.: `loja.seudominio.com.br`) e `KEYCLOAK_DOMAIN` (ex.: `auth.seudominio.com.br`) para o IP do servidor e libere as portas **80 e 443**.
2. **`.env`:** `USE_NGINX=true` e preencha `FRONT_DOMAIN`, `KEYCLOAK_DOMAIN`, `LE_EMAIL`, `KEYCLOAK_URL=https://<KEYCLOAK_DOMAIN>`, `FRONT_URL=https://<FRONT_DOMAIN>` e as senhas. O `iniciar` confere se as URLs batem com os domínios e recusa domínios `exemplo`.
3. **Certificado:** `./ssl.sh letsencrypt` (Windows: `ssl.bat letsencrypt`). Um certificado único cobre os dois domínios. Renovação: agende `./ssl.sh renovar` (cron semanal); o nginx pausa por alguns segundos.
4. **Subir:** `./iniciar.sh` (build do front → nginx/Keycloak/PostgreSQL → jars).
5. **Redirect URIs:** o realm usa `${FRONT_URL}/*` (placeholder do Keycloak, preenchido pelo `.env`). **A importação só ocorre na criação do realm.** Se já subiu o Keycloak antes de trocar o domínio, ajuste em *Clients → pdv-web* no console ou apague o volume (`docker compose -f docker-compose.prod.yml down -v`, apaga os dados do Keycloak).

**Teste local com HTTPS:** use domínios como `loja.local` / `auth.local`, rode `./ssl.sh local`, adicione-os ao arquivo *hosts* apontando para `127.0.0.1`, defina `OIDC_TLS_VERIFICATION=none` no `.env` e aceite o aviso do navegador.

**Endurecimento recomendado:**
- Bloqueie no firewall as portas 8081–8084 e 5432 (os serviços escutam em todas as interfaces; só o nginx deve acessá-los).
- Restrinja `/admin/` do Keycloak a IPs conhecidos (bloco comentado em `nginx/templates/default.conf.template`).
- Troque as senhas temporárias `trocar123` e as do `.env`; faça backup dos volumes `pgdata` e `kcdata`.

**Limitações conhecidas:** `/estoque/baixa` é liberada ao operador (necessária para vendas), então a mesma rota serve a perdas lançadas por quem tiver acesso; o login offline com cache local só existe em desenvolvimento.

## Login (desenvolvimento)
Usuários criados na primeira execução do retaguarda-service (usuário = senha = perfil):

| Usuário | Senha | Perfil |
|---|---|---|
| gerente | gerente | vê todas as telas |
| supervisor | supervisor | cadastros, estoque, NFC-e, etiquetas, caixa |
| operador | operador | PDV, fechamento de caixa e consulta de preço |

**Troque as senhas** em Configurações → Usuários e perfis. Após um login bem-sucedido o computador guarda um hash local, permitindo entrar com a rede fora do ar (modo offline do PDV). O controle por perfil hoje é feito no front; o backend ainda não valida permissões.

## Atalhos do PDV
- **PLU + Enter** (digitando o número do produto) e **F2** para pagar.
- **Ctrl + tecla do produto** (opcional): no cadastro (ou na coluna *Atalho* da tela Produtos) escolha 1 número (0-9) ou letra (A-Z). No caixa, `Ctrl+B` lança o produto com a tecla B. Cada tecla é única entre os produtos ativos e aparece no botão do produto. Produto sem atalho continua funcionando por clique e PLU.
- Ficam de fora **N, T e W** (o navegador reserva Ctrl+N/T/W e a página não consegue interceptá-los). Dentro de campos de texto, Ctrl+A/C/V/X/Z/Y continuam com a função normal.
- Produtos vendidos por KG pedem peso na balança, como no clique.

## Exclusão lógica (desativação)
Nada é apagado de verdade: o botão **Desativar** (antes "Excluir") só marca o registro como inativo.
- **Quem vê:** supervisor e operador deixam de ver o registro; o **gerente** continua vendo, com o selo **Desativado** (linha esmaecida) e o botão **Reativar** (somente ele reativa — validado no backend).
- **Entidades:** produto, fornecedor, cliente, cotação, conta a pagar/receber (só se ainda não paga) e usuário.
- **Efeitos:** produto desativado some do PDV/consulta/self-checkout e a API de vendas recusa a venda (HTTP 422); o PLU continua reservado. Cliente desativado não acumula nem resgata cashback (e o CPF não pode ser recadastrado). Cotação e conta desativadas saem do resumo CEASA, do DRE e do fluxo de caixa.
- **Auditoria:** cada desativação/reativação aparece como **Desativação**/**Reativação**, com o usuário responsável.
- Para listar também os desativados, a API aceita `?inativos=true`, mas só atende quando o usuário é gerente.

## Auditoria (cadastro, edição e exclusão)
Tela **Relatórios → Auditoria** (somente gerente): quem fez, quando, o quê e **o que mudou** (campo, antes → depois). Filtros por usuário, ação, entidade e período; exportação CSV.

| O que é registrado | Ação |
|---|---|
| Produto (cadastro, edição, desconto por validade, reajuste de categoria) | Cadastro / Edição |
| Entrada de estoque, produção/fracionamento | Cadastro / Edição |
| Perdas e avarias (baixa manual) | Baixa de estoque |
| Fornecedor, cliente, cotação, conta (lançar/baixar), usuário (criar/senha) | Cadastro / Edição |
| Produto, fornecedor, cliente, cotação, conta e usuário **desativados / reativados** | Desativação / Reativação |
| Cancelamento de venda e de NFC-e | Cancelamento |
| Fechamento de caixa (valor contado, esperado e **diferença**) | Cadastro |

- **Como funciona:** cada serviço chama `Auditor.registrar(...)` e envia o evento ao `retaguarda-service` (`POST /auditoria`), que grava na tabela `Auditoria`. No retaguarda, a gravação entra na mesma transação do negócio.
- **Quem fez:** em **produção**, vem do token do Keycloak (o corpo do evento é ignorado); em **desenvolvimento**, dos cabeçalhos `X-Usuario`/`X-Perfil` que o front envia após o login local.
- **Somente inclusão:** a API não tem alterar/excluir auditoria. Em produção, reforce no banco: crie um usuário de aplicação sem `UPDATE`/`DELETE` na tabela `auditoria`.
- **Limitações:** nos serviços catálogo/estoque/vendas o registro é *best-effort* (se o retaguarda estiver fora do ar a operação continua e fica um aviso no log); vendas e baixas automáticas de estoque não entram (volume). O fechamento de caixa não devolve mais a diferença ao operador: ela só aparece aqui.

## Telas do menu (todas ativas)
| Menu | Backend | Observação |
|---|---|---|
| PDV, Fechamento cego, Produtos, Entrada, Perdas, Curva ABC, Perdas/descarte | catalogo / estoque / vendas | prontos |
| Produção / fracionamento | estoque | abacaxi → bandeja picada |
| Fornecedores, Clientes e fidelidade (cashback 2%) | retaguarda | PDV acumula cashback pelo CPF |
| Importar XML (NF-e) | estoque + retaguarda | lê XML no navegador, mapeia itens, aplica fator de conversão, grava cotação |
| Cotações CEASA | retaguarda | mín/média/máx/última por produto |
| Sugestão de compras | vendas + Open-Meteo | média por dia da semana × previsão do tempo (defina lat/lon em Periféricos) |
| Contas a pagar/receber, DRE, fluxo de caixa | retaguarda + vendas | CMV usa o custo médio gravado em cada item vendido |
| NFC-e / SAT | retaguarda | **SIMULADA** (chave 44 díg., contingência offline, cancelamento). Falta integrar emissor real |
| Margem por categoria | catalogo | margem bruta e real (descontando perda) |
| Encartes / WhatsApp | retaguarda | gera texto e abre `wa.me` por cliente; envio em massa automático exige WhatsApp Cloud API |
| Etiquetas e EAN-13 | — | EAN-13 de peso variável (2+PLU+valor), impressão pelo navegador |
| Self-checkout, Consulta de preço | vendas | usam a balança; PIX do autoatendimento é demonstrativo |
| Usuários e perfis | retaguarda | PIN com hash; login real = Keycloak |
| Balança e periféricos | local | baud rate, TEF (somente configuração), impressora, gaveta, coordenadas |

## Pendências para produção
- NFC-e real (SEFAZ/Focus NFe), PIX dinâmico via PSP com webhook, SDK de TEF (PayGo/SiTef).
- Autenticação (Keycloak) e autorização por perfil no backend (hoje o menu por perfil é só visual).
- Banco local no PDV (SQLite) para o modo híbrido; a fila offline atual fica no navegador.
- Mensageria (Kafka/RabbitMQ) e API Gateway entre os serviços.
