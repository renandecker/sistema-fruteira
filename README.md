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

## Categorias, promoções e PDV
- **Categorias** (Cadastros → Categorias): o cadastro de produto agora escolhe a categoria numa lista. Renomear uma categoria renomeia também seus produtos; ▲▼ define a ordem das abas. Categorias já usadas em produtos antigos são criadas automaticamente na primeira subida.
- **PDV em abas:** uma aba por categoria (mais **Todos** e **Outros**). **Tab / Shift+Tab** troca de aba — o `Alt+Tab` é reservado pelo sistema operacional e uma página web não consegue capturá-lo, por isso o caixa usa Tab (também dá para clicar na aba).
- **Mais comprados primeiro:** os produtos de cada aba são ordenados pelo número de vendas (`GET /vendas/ranking`), atualizado a cada venda concluída.
- **Destaque:** ao clicar, usar PLU+Enter ou **Ctrl+tecla**, o botão do produto pisca, ganha um contador de linhas na venda e o item novo aparece destacado na lista da direita.
- **Promoções** (Cadastros → Promoções): marque vários produtos, escolha **% de desconto** ou **preço promocional** e defina o **prazo** (início e fim; atalhos Só hoje/3/7/15 dias). Uma promoção por produto por período (sem sobreposição); edição, desativação e reativação (gerente) com auditoria. O preço promocional é **calculado em tempo real** e deixou de existir no cadastro do produto.
- **No caixa:** produto em promoção mostra a faixa **PROMO −x%**, preço antigo riscado, preço novo e a data final; na lista aparece a etiqueta PROMO, o desconto da linha e o total economizado. O desconto é calculado pelo servidor (a venda grava preço original e desconto por item) e vale só dentro do prazo.

## Balança Toledo Prix e outras balanças
- **Pelo cabo (Web Serial, Chrome/Edge em localhost ou HTTPS):** em *Configurações → Balança e periféricos* escolha o modelo (ou ajuste protocolo, baud, bits, paridade e divisor) e use **Testar balança** para ver o peso e os bytes recebidos. Protocolos: *contínuo* (a balança envia o peso) e *ENQ* (o sistema pede com `0x05` e a balança responde `<STX>00446<ETX>` = 0,446 kg). A porta já autorizada é reconectada sozinha ao abrir o caixa.
- **Prix 3 Fit / 3 Plus:** só enviam o peso se tiverem a interface **RS-232 opcional** (cabo conversor RJ45→serial da Toledo + adaptador USB-serial) e protocolo/velocidade/paridade iguais nos dois lados (relatos de campo: 2400 ou 4800 bps, protocolo P05A/P03, 8N1). **Não testado com a balança física.**
- **Prix 4 Uno e etiquetadoras em geral:** relatos indicam que não transmitem o peso; a balança imprime a etiqueta e o caixa lê o **código de barras EAN-13** (prefixo 2). O formato (dígitos do código, preço total ou peso) é configurável e o código do item na balança deve ser o **PLU** do produto.
- Produto por kg lido por etiqueta é aceito como peso automático (a etiqueta veio da balança).

## TEF (cartões integrados à maquininha)
O navegador não acessa o pinpad. O desenho segue o roteiro do `tef.txt`:

```
[ React (caixa) ] ── HTTP 127.0.0.1:8090 ──> [ Agente TEF local (tef-agent) ] ──> [ Provedor TEF / pinpad ]
       │
       └── HTTPS /api/vendas/tef ───────────> [ vendas-service: transações, vínculo com a venda ] ──> NFC-e (retaguarda)
```
- **Agente TEF** (`tef-agent`, porta 8090, escuta só em 127.0.0.1): roda no computador do caixa. Provedores pluggáveis (`TefProvider`): **simulador** (pronto) e **clisitef** (ponto de extensão *não implementado*: exige a DLL licenciada, contrato e homologação).
- **Fluxo:** (1) o servidor cria a transação `PENDENTE` com a requisição única; (2) o caixa aciona o agente e mostra as mensagens do pinpad em tempo real (Insira o cartão → Digite a senha → Processando); (3) o resultado (NSU, autorização, bandeira, adquirente, CNPJ da credenciadora, comprovantes) é gravado no servidor; (4) a venda é gravada **vinculando** o `tefId` (o servidor confere valor e status); (5) imprime as vias do cliente e da loja e **confirma** no agente e no servidor. Se a venda for recusada, o cartão é **desfeito**.
- **Pendências / queda:** ao abrir o caixa (e ao voltar a rede) o PDV **reconcilia**: transação aprovada com venda → confirma; sem venda → desfaz; sem retorno → marca erro. A tela **Financeiro e Fiscal → Transações TEF** mostra o estado do agente e do pinpad, as pendências, reimprime comprovantes e faz o **estorno** (supervisor/gerente). Venda paga com cartão TEF só pode ser cancelada depois do estorno.
- **NFC-e:** os dados do cartão (tPag 03/04, CNPJ da credenciadora, tBand, nº de autorização, NSU) seguem com a emissão e aparecem na tela NFC-e. A NFC-e continua **simulada**; confira o mapeamento com seu emissor fiscal.
- **Ligar:** Configurações → Balança e periféricos → *Integrado via Agente TEF local* (URL, token, terminal) e **Testar agente TEF**. `FRUTEIRA_TEF_OBRIGATORIO=true` exige TEF para crédito/débito; sem isso, a opção *maquininha manual* continua disponível se o agente cair.
- **Segurança do agente:** só escuta em 127.0.0.1; CORS restrito à origem do front (`TEF_ORIGENS`/`FRONT_URL`); token obrigatório (`TEF_TOKEN`, cabeçalho `X-Tef-Token`) para que outras páginas abertas no navegador não acionem a maquininha; responde ao cabeçalho de rede privada do Chrome/Edge (front HTTPS → 127.0.0.1 é permitido, sem *mixed content*). Em produção use token longo.
- **Simulador** (os **centavos** do valor definem o resultado): `,01` negada (saldo insuficiente) · `,02` negada (senha incorreta) · `,03` erro de comunicação · `,04` cancelada no pinpad · demais: aprovada. O comprovante sai marcado "SIMULADO — sem valor". O CNPJ da credenciadora é **fictício** no simulador; no TEF real vem do provedor.
- **Roteiro de homologação** (o provedor exige): aprovada; negada (sem saldo/senha); cancelamento no meio (botão *Cancelar operação*); queda do agente/energia no meio (reinicie o agente com a transação em andamento → vira ERRO e a reconciliação trata); aprovada e venda recusada (cartão desfeito); estorno; reimpressão; via sem papel (a impressão é feita pelo navegador). Use o simulador para ensaiar e repita no pinpad homologado.
- **Fora do escopo desta versão:** provedor real (CliSiTef/PayGo/Cappta), TEF totalmente offline, impressão direta em térmica pelo agente, WebSocket (o caixa consulta o agente a cada 0,5 s), TLS no agente.

## PIX (cobrança dinâmica + confirmação automática)
Segue o roteiro do `pix.txt`: o React nunca fala com o PSP; o servidor Java guarda chaves/certificados e conversa com o PSP.

```
[ React (caixa) ] ─ POST /pix/cobrancas ─> [ vendas-service ] ─ PUT /v2/cob ─> [ PSP / banco ]
       ▲   polling a cada 3 s (GET)             ▲                                     │ cliente paga
       └──────────── status ────────────────────┴──── POST /pix/webhook <────────────┘ (CONCLUIDA)
```
- **Fluxo:** (1) o caixa pede o Pix; (2) o servidor cria a cobrança no PSP (`txid` de 32 caracteres, expiração `PIX_EXPIRACAO_SEG`, 300 s) e devolve o **Pix Copia e Cola**; (3) o caixa mostra o **QR Code**, o código com botão **Copiar** (retorno visual) e a contagem regressiva; (4) o PSP chama o **webhook** quando o cliente paga; (5) o caixa, em polling, vê `CONCLUIDA` e a venda é gravada **vinculando** o Pix (o servidor confere status e valor exato).
- **Webhook (`POST /pix/webhook`, público):** protegido por **segredo** (`FRUTEIRA_PIX_WEBHOOK_SEGREDO`, cabeçalho `X-Webhook-Token` ou `?token=`; sem segredo o servidor recusa fora do simulador) e, no nginx, limite de taxa. É **idempotente**: o `endToEndId` é único, notificação repetida não reprocessa. Se o PSP tiver mTLS no webhook, valide o certificado no proxy.
- **Rede de segurança:** se o webhook não chegar, o servidor confere a cobrança no PSP a cada 10 s enquanto o caixa consulta. Cobrança vencida vira `EXPIRADA`; pagamento que chega depois de expirar/cancelar **vale como pago** (o dinheiro entrou).
- **Pix recebido sem venda** (caixa travou/venda recusada): fica **reservado** — o caixa não gera outro QR (evita cobrar duas vezes) e permite finalizar de novo; também aparece em *Transações PIX* e como aviso ao abrir o caixa. Pix **não se "desfaz"**: só **devolução** (supervisor/gerente). Venda paga com Pix só pode ser cancelada **depois da devolução**.
- **Tela** *Financeiro e Fiscal → Transações PIX*: provedor, pendências, cancelar cobrança, devolver. **NFC-e:** o Pix entra como tPag 17 com o `endToEndId`.
- **Simulador** (`PIX_PROVIDER=simulador`, padrão): gera Pix Copia e Cola no formato BR Code (CRC16 verificado; a URL é fictícia, app bancário real recusa), paga sozinho após 6 s chamando o próprio webhook (mesmo caminho do PSP) e tem o botão **Simular pagamento do cliente**. Os **centavos** do valor: `,01` nunca paga (veja a expiração) · `,03` webhook duplicado (idempotência).
- **PSP real** (`PIX_PROVIDER=psp`): provedor genérico da **API Pix do Banco Central** (`PUT /v2/cob/{txid}`, consulta, `PATCH` cancelamento, `PUT /v2/pix/{e2e}/devolucao/{id}`, `PUT /v2/webhook/{chave}`), OAuth2 e mTLS por `.p12`. Configure `PIX_PSP_URL`, `PIX_PSP_CLIENT_ID/SECRET`, `PIX_CHAVE`, `PIX_PSP_CERTIFICADO` e registre o webhook (botão em Configurações → PIX, gerente). **Não foi testado contra um PSP real:** valide em **sandbox** (formato do token, escopos e cabeçalhos variam por PSP; para outro, implemente `PixProvider`).
- **Ligar no caixa:** Configurações → Balança e periféricos → PIX → *Integrado ao PSP*. `FRUTEIRA_PIX_OBRIGATORIO=true` recusa PIX sem confirmação do PSP.
- **Fora do escopo:** SSE/WebSocket (hoje é polling), QR na tela do cliente, devolução parcial, conciliação por extrato.

## Pagamento em mais de uma forma
No modal **Pagar**, divida o total em quantas formas precisar: escolha a forma (`F1`–`F6`/`1`–`6`), informe o valor e `Enter` (**Adicionar pagamento**); repita até cobrir o total (**Finalizar venda**). O modal mostra o que já foi pago e o que falta; `F7` preenche o restante, `Delete` ou o × remove um pagamento. Só **dinheiro** pode passar do que falta (gera troco); no cartão/PIX/vales o valor não pode exceder o restante. No dinheiro o sistema grava o valor **aplicado** à venda (sem o troco), o que mantém o fechamento de caixa correto.

## Entrada de mercadoria com fornecedor
Na tela *Estoque → Entrada de mercadoria* escolha o **fornecedor** (cadastrado em Cadastros → Fornecedores; opcional). Ele é gravado na movimentação, aparece na auditoria e na lista **Últimas entradas** (`GET /estoque/entradas`). Com fornecedor, a entrada também registra a **cotação** (preço unitário pago) para o histórico CEASA; dá para desmarcar. Em produção, o supervisor lista fornecedores e registra cotações; só o gerente altera/consulta o restante.

## Produto por kg e teste sem balança
Produto por **kg** só entra com peso lido da balança (anti-fraude); sem balança conectada o caixa recusa e destaca o peso em vermelho com o aviso. Em **desenvolvimento** há o campo **🧪 peso (kg)** no topo do PDV para simular a balança; em produção ele não aparece.

## Testes automatizados do front
`cd pdv-web && npm install && npm test` (vitest + jsdom): vários itens por clique/PLU, aviso de peso, pagamento dividido com troco, validações do modal e entrada com fornecedor.

## Remover itens no caixa
- **×** em cada item remove só ele; o **✕** à direita do campo CPF limpa todos os itens (pede confirmação). Ambos ficam registrados na **Auditoria** como *Cancelamento* (item, valor e usuário).

## Catálogo de imagens e pré-cadastro
- **Imagem no PDV:** no cadastro do produto (e na coluna *Imagem* da tela Produtos) escolha a imagem no **catálogo** (≈70 frutas, legumes, verduras e temperos, com emoji ou ilustração vetorial) ou informe a URL de uma foto. Sem escolha, o sistema sugere pelo nome (Banana Prata → banana); o botão *Mapear imagens pelo nome* aplica isso aos produtos existentes. O PDV, o self-checkout e a consulta de preço usam a imagem escolhida.
- **Script PostgreSQL:** `database/pre_cadastro_frutas_verduras.sql` cadastra 5 categorias e 69 produtos comuns (unidade, perda média, NCM sugerido, imagem) com PLU por popularidade. Idempotente. Rode **depois** de subir o `catalogo-service` em produção (ele cria as tabelas): `psql -h localhost -U postgres -d fruteira -f database/pre_cadastro_frutas_verduras.sql`. Preços e NCM são **referência**: confira antes de vender/emitir nota. **Rodar local:**
  - *Desenvolvimento (H2, sem Docker):* o mesmo pré-cadastro (classe `PreCadastro`) é carregado **sozinho ao subir** o `catalogo-service` (`%dev.fruteira.pre-cadastro=true`). Em qualquer ambiente também há o botão **📥 Importar frutas e verduras mais comuns** na tela Produtos (`POST /produtos/pre-cadastro`); pode repetir sem duplicar.
  - *PostgreSQL local:* `database/aplicar_sql_local.sh` (ou `.bat`) aplica o `.sql` usando o `psql` instalado ou o contêiner `postgres` do `docker-compose.prod.yml`.
  - A lista existe em dois lugares (SQL e `PreCadastro.java`, gerada do SQL); ao editar uma, atualize a outra.

## Modal de pagamento — atalhos
`F1` PIX · `F2` Dinheiro · `F3` Crédito · `F4` Débito · `F5` Vale-alimentação · `F6` Vale-refeição (ou as teclas `1`–`6`) · `F7` valor exato · `F8`/`F9` notas sugeridas · `Enter` confirma · `Esc` volta.

## Leitor de código de barras (topo do PDV)
- O campo **Código de barras** fica no topo, já focado ao abrir o caixa e volta ao foco após cada lançamento e após o pagamento. **F4** leva o cursor até ele de qualquer lugar da tela (Esc limpa).
- **Ctrl+Tab não é possível:** o navegador reserva essa combinação (troca de abas do navegador) e uma página web não consegue capturá-la; por isso o atalho é **F4**.
- **Identificou, lançou:** ao reconhecer o código (8+ dígitos) o produto entra na venda na hora, sem Enter, com bip de confirmação (grave = não encontrado). Enter também funciona e aceita **PLU**.
- Reconhece (1) o **código de barras cadastrado** no produto (único entre os ativos), e (2) as **etiquetas EAN-13 de peso/preço variável** geradas em *Etiquetas* (`2` + PLU + valor + DV): o produto entra com a quantidade calculada pelo valor da etiqueta. Produto por kg lido pelo código de barras cadastrado ainda exige peso na balança.
- Com tela de toque o campo usa `inputMode="none"` (não abre o teclado virtual); leitores USB/Bluetooth no modo "teclado" funcionam sem configuração.

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
| Produto (cadastro, edição, reajuste de categoria), categoria e promoção | Cadastro / Edição |
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

## NCM (validação, rotina diária e TEF)

A NFC-e exige NCM de **8 dígitos, existente e vigente** em cada item. O sistema trata isso assim:

- **Cadastro de produto**: ao digitar o NCM, a tela consulta `GET /api/catalogo/ncm/{codigo}` (BrasilAPI `/api/ncm/v1/{codigo}` primeiro; se falhar, tabela oficial do Siscomex/Portal Único) e mostra descrição e vigência. NCM inválido ou vencido é **recusado** (422) com sugestões da mesma subposição. O sistema **nunca troca um NCM sozinho**: decisão fiscal é sua.
- **Listagem de produtos**: coluna **NCM** (editável, revalida ao sair do campo) com selo VÁLIDO / INVÁLIDO / VENCIDO / AUSENTE.
- **Rotina diária**: `@Scheduled` às 03:00 (`NCM_CRON`, padrão `0 0 3 * * ?`) confere o NCM de todos os produtos, normaliza códigos formatados (`0803.90.00` → `08039000`) e grava status/vigência. Se as APIs estiverem fora do ar, mantém o último status confirmado. Também roda na subida para produtos ainda não verificados.
- **Botão manual**: em *Produtos e preços* → “🔎 Verificar NCM de todos agora” (supervisor ou gerente; auditado), com resumo da última execução.
- **TEF/cartão**: a cobrança no cartão é **bloqueada** no PDV e no `vendas-service` enquanto algum item não tiver NCM VÁLIDO. Variáveis: `FRUTEIRA_NCM_OBRIGATORIO_TEF` (padrão `true`), `FRUTEIRA_NCM_OBRIGATORIO` (bloqueia qualquer venda, padrão `false`), `FRUTEIRA_NCM_AGENDADO`, `FRUTEIRA_NCM_NA_SUBIDA`.
- Produtos do pré-cadastro nascem sem status; a verificação da subida os confere. Até lá, cartão fica bloqueado nesses itens.

## Atalhos novos do PDV

- **Ctrl + ← / →** troca a aba (categoria). Produtos ordenados **alfabeticamente** em cada aba.
- **Alt + nº do PLU + Enter** adiciona o produto; o PLU digitado aparece no campo do canto superior esquerdo (no lugar da imagem da fruteira).
- **Alt + \* + quantidade + Enter** multiplica o **último item adicionado**, somente se vendido **por unidade** (1–999). Esc cancela, Backspace apaga.
- Alguns sistemas/navegadores interceptam Alt+tecla; se isso ocorrer, use o campo de código de barras (F4).

## Cartões de benefício e Banricompras

Catálogo em `pdv-web/src/cartoes.js` e `vendas-service/.../Cartoes.java` (mantenha os dois iguais): **Alelo Alimentação, Alelo Refeição, Alelo Tudo (Flexível), Banricard, Banricompras, Ticket (Edenred), Pluxee, VR Benefícios, Green Card e ValeCard / Vólus**.

- No pagamento, **Vale-alimentação (F5)** e **Vale-refeição (F6)** pedem a operadora (só aparecem as que aceitam aquele saldo; Alelo Alimentação só no vale-alimentação, Alelo Refeição só no vale-refeição, Alelo Tudo nos dois). A venda é recusada (422) sem operadora válida.
- **Banricompras** é débito/crédito: com TEF a bandeira vem da maquininha; sem TEF aparece como "Rede (opcional)".
- A operadora fica gravada no pagamento da venda e vai para a NFC-e (`tPag` 10 vale-alimentação, 11 vale-refeição).
- Limites: vales são lançados manualmente (sem TEF/autorização online); o saldo Mobilidade/Farmácia/Saúde do Alelo Tudo não se aplica à fruteira. Para vale com autorização na maquininha, o provedor TEF precisa suportar o produto.

## Pasta de produção (pronta para instalar)

`./gerar-producao.sh` (Windows: `gerar-producao.bat`) monta a pasta **`producao/`** com tudo que o servidor precisa:

- `servicos/` (5 jars Quarkus já compilados), `web/` (front compilado), `servidor-web.mjs`, `docker-compose.prod.yml` (PostgreSQL + Keycloak + nginx opcional), `keycloak/`, `nginx/`, `database/`, `.env`, `iniciar`/`parar`/`backup`/`ssl` (.sh e .bat) e `LEIA-ME.txt`.
- O `.env` de produção é criado com **senhas aleatórias** (banco, Keycloak e `TEF_TOKEN`) e **nunca é sobrescrito** ao gerar de novo.
- Rode o gerador na máquina de desenvolvimento (Java, Maven, Node). O **servidor** só precisa de Java 21 + Docker (+ Node se não usar nginx) — sem Maven e sem compilar.
- Mudou `KEYCLOAK_URL`/`FRONT_URL`/domínios? Gere de novo: a URL do Keycloak é embutida no front no build.

## Pendências para produção
- NFC-e real (SEFAZ/Focus NFe), PIX dinâmico via PSP com webhook, SDK de TEF (PayGo/SiTef).
- Autenticação (Keycloak) e autorização por perfil no backend (hoje o menu por perfil é só visual).
- Banco local no PDV (SQLite) para o modo híbrido; a fila offline atual fica no navegador.
- Mensageria (Kafka/RabbitMQ) e API Gateway entre os serviços.
