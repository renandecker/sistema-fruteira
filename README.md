# Sistema Fruteira — React + Quarkus (microsserviços)

## Rodar tudo de uma vez
- Linux/macOS: `./iniciar.sh` (Ctrl+C encerra; `./parar.sh` se sobrar processo)
- **Portas:** ao iniciar (`iniciar.sh` e `iniciar.bat`), o que estiver escutando em 8081–8084 (serviços), 8090 (agente TEF) e 5173 (front) é encerrado, para uma execução anterior que ficou pendurada não impedir a subida; o script mostra qual processo foi encerrado e aborta se não conseguir liberar. PostgreSQL, Keycloak e nginx são do Docker e não são tocados. `LIBERAR_PORTAS=false` no `.env` desliga isso.
- **Banco travado / `missing table`:** o retaguarda-service é o dono do banco (Flyway) e sobe primeiro; se ele não subir, o `iniciar` agora **para e mostra o log dele**, em vez de subir os outros (que falhariam com `Schema-validation: missing table [Movimento]`). Em desenvolvimento o script apaga o `data/*.lock.db` que sobra de uma execução anterior e avisa se a pasta está no **OneDrive** (a sincronização pode travar o `data/fruteira.mv.db`; erro `90028` / "Acquisition timeout"). Se persistir, feche todas as janelas, mova o projeto para fora do OneDrive (ex.: `C:\fruteira`) ou apague a pasta `data` (recria o banco).
- **`missing table [Entities$Auditoria]`:** as entidades do retaguarda eram classes aninhadas sem nome (tabela `Entities$X`, que o Hibernate procura com maiúsculas/minúsculas exatas e o H2 guardava em maiúsculas). Agora cada uma tem nome explícito (`@Entity(name = "Cliente")` etc.) e as tabelas são `Fornecedor`, `Cliente`, `Cotacao`, `Conta`, `Nota`, `Auditoria`, `Usuario`; as migrations V1–V3 e V13 foram reescritas com esses nomes (o projeto ainda não tinha banco de verdade). **Apague a pasta `data` uma vez** ao atualizar, senão o Flyway acusa checksum diferente.
- Windows: duplo clique em `iniciar.bat` (`parar.bat` encerra)

## Rodar manualmente (3 terminais + front)
```
cd catalogo-service && mvn quarkus:dev     # :8081  (Swagger: /q/swagger-ui)
cd estoque-service  && mvn quarkus:dev     # :8082
cd vendas-service   && mvn quarkus:dev     # :8083
cd retaguarda-service && mvn quarkus:dev   # :8084  (fornecedores, clientes, cotações, contas, NFC-e, usuários)
cd pdv-web && npm i && npm run dev         # :5173
```
Requer JDK 21, Maven e Docker (Em dev o banco é um H2 em arquivo (pasta data/ da raiz, compartilhado pelos 4 serviços), sem Docker).
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
| Banco | H2 em arquivo (`data/fruteira`, único) | PostgreSQL (`DB_URL`, `DB_USER`, `DB_PASS`) |
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
- Troque as senhas temporárias `trocar123` e as do `.env`; faça backup com `backup/` (ver seção Backup).

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
- **NFC-e:** os dados do cartão (tPag 03/04, CNPJ da credenciadora, tBand, nº de autorização, NSU) seguem com a emissão e aparecem na tela NFC-e. Eles vão no grupo `card` do `detPag` da NFC-e (seção *NFC-e e Nota Fiscal Gaúcha*).
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
- **Webhook com Ngrok (testar o Pix real sem domínio):** a URL do webhook (a que o gerente registra no provedor, ou que vai em cada cobrança no Mercado Pago) vale para **todos** os provedores e é resolvida nesta ordem: `FRUTEIRA_PIX_WEBHOOK_URL` (fixa, vence) → `NGROK_URL` → o túnel que o próprio Ngrok informa em `http://127.0.0.1:4040/api/tunnels` (só em desenvolvimento). A URL fica `{túnel}/pix/webhook?token={segredo}`.
  - **Como usar:** instale o [Ngrok](https://ngrok.com/download), `ngrok config add-authtoken SEU_TOKEN` (ou `NGROK_AUTHTOKEN` no `.env`) e ponha `USAR_NGROK=true` no `.env`: `iniciar.sh/.bat` abrem `ngrok http 8083` (com `NGROK_DOMINIO` se você tem domínio reservado), mostram o endereço e passam-no ao `vendas-service`; `parar.sh/.bat` fecham o túnel. Ou abra você mesmo `ngrok http 8083`: o serviço descobre sozinho.
  - **Registrar:** em Configurações → Balança e periféricos → PIX, **Registrar webhook no PSP (gerente)** usa o endereço de *agora* e mostra a URL (sem o segredo) e de onde ela veio. No plano grátis o endereço muda a cada início do Ngrok: registre de novo, ou use `PIX_WEBHOOK_AUTO_REGISTRAR=true` (registra ao subir; **sobrescreve** o webhook da conta no provedor, então não use com a conta de produção da loja). Mercado Pago não registra: a URL vai em cada cobrança.
  - **Segurança:** o túnel aponta direto para o `vendas-service` (porta 8083), nunca para o front. Pelo endereço do Ngrok **só o `POST /pix/webhook` responde** (`NgrokFilter`; o resto da API devolve 404), e o segredo (`FRUTEIRA_PIX_WEBHOOK_SEGREDO`) continua obrigatório. Se preferir expor pelo front/nginx, use `NGROK_CAMINHO=/api/vendas/pix/webhook` (aí a proteção do restante da API é a do nginx/Keycloak).
  - Variáveis: `USAR_NGROK`, `NGROK_AUTHTOKEN`, `NGROK_DOMINIO`, `NGROK_URL`, `NGROK_ARGS`, `NGROK_CAMINHO`, `NGROK_API` (padrão só em dev), `NGROK_PORTA` (8083), `PIX_WEBHOOK_AUTO_REGISTRAR`. A pasta de produção (`producao-modelo`) não inicia o Ngrok; lá use a URL fixa (domínio + nginx).
  - ⚠ A resolução da URL (`PixWebhook`) é coberta por `vendas-service/testar-pix.sh`; o `NgrokFilter`, o `PixWebhookAuto` e o uso real do Ngrok **ainda não foram compilados/rodados** com Quarkus e o Ngrok de verdade (sem Maven nem conta Ngrok aqui): na 1ª vez, abra o túnel e confira em Configurações → PIX → *Testar Pix no servidor* se a URL aparece; um `curl` em `https://SEU-TUNEL/pix/status` deve dar 404 e `POST /pix/webhook` sem token, 401.
- **Rede de segurança:** se o webhook não chegar, o servidor confere a cobrança no PSP a cada 10 s enquanto o caixa consulta. Cobrança vencida vira `EXPIRADA`; pagamento que chega depois de expirar/cancelar **vale como pago** (o dinheiro entrou).
- **Pix recebido sem venda** (caixa travou/venda recusada): fica **reservado** — o caixa não gera outro QR (evita cobrar duas vezes) e permite finalizar de novo; também aparece em *Transações PIX* e como aviso ao abrir o caixa. Pix **não se "desfaz"**: só **devolução** (supervisor/gerente). Venda paga com Pix só pode ser cancelada **depois da devolução**.
- **Tela** *Financeiro e Fiscal → Transações PIX*: provedor, pendências, cancelar cobrança, devolver. **NFC-e:** o Pix entra como tPag 17 com o `endToEndId`.
- **Simulador** (`PIX_PROVIDER=SIMULADOR`, padrão): gera Pix Copia e Cola no formato BR Code (CRC16 verificado; a URL é fictícia, app bancário real recusa), paga sozinho após 6 s chamando o próprio webhook (mesmo caminho do PSP) e tem o botão **Simular pagamento do cliente**. Os **centavos** do valor: `,01` nunca paga (veja a expiração) · `,03` webhook duplicado (idempotência).
- **Provedores** (`PIX_PROVIDER`, ignora maiúsculas/minúsculas): `SIMULADOR` (padrão) · `EFI` · `ITAU` · `BRADESCO` · `SICOOB` · `SICREDI` · `MERCADO_PAGO` · `ASAAS`. O valor antigo `psp` não existe mais: escolha o provedor. **Cada provedor tem as suas variáveis `PIX_<PROVEDOR>_*`** (bloco pronto no `.env.example`); preencha só as do provedor escolhido. Vazio = padrão do provedor. O `iniciar.sh` confere o que falta antes de subir.
  - **Bancos (EFI, ITAU, BRADESCO, SICOOB, SICREDI)** — API Pix do Banco Central (`PUT {prefixo}/cob/{txid}`, consulta, `PATCH` cancelamento, `PUT {prefixo}/pix/{e2e}/devolucao/{id}`, `PUT {prefixo}/webhook/{chave}`), OAuth2 + mTLS por `.p12`. Variáveis: `PIX_<BANCO>_URL`, `_TOKEN_URL`, `_CLIENT_ID`, `_CLIENT_SECRET`, `_TOKEN_FORMATO` (`basic-json` | `basic-form` | `form`), `_ESCOPOS`, `_CABECALHOS` (`Nome=valor;Outro=valor`, para chaves de API/IDs que o banco exija), `_PREFIXO_API` (`-` = nenhum), `_CAMINHO_COB` (`/cob-emv` se o banco usar), `_CHAVE` (chave Pix só deste banco; senão `PIX_CHAVE`), `_CERTIFICADO`, `_CERTIFICADO_SENHA`, `_SKIP_MTLS_WEBHOOK`. Padrões embutidos: Efí (URL, `basic-json`) · Sicoob (URL, token, `form`, cabeçalho `client_id` e escopos próprios, sem prefixo porque a URL já termina em `/v2`) · Bradesco (URL e token, `basic-form`) · Sicredi (URL, `basic-form`, prefixo `/api/v2`) · Itaú (token `sts.itau.com.br`, `form`; **informe a URL** da documentação do banco). Sandbox: troque `_URL` (ex.: Efí `https://pix-h.api.efipay.com.br`).
    - **Certificado:** o Java lê `.p12`. Se o banco entregou `.pem` + `.key` (Bradesco), converta: `openssl pkcs12 -export -inkey chave.key -in certificado.pem -out certificado.p12`.
  - **MERCADO_PAGO** — `POST /v1/payments` (Pix), consulta por `external_reference`, cancelamento (`PUT status=cancelled`) e devolução (`POST /v1/payments/{id}/refunds`). Variáveis: `PIX_MERCADO_PAGO_ACCESS_TOKEN`, `_EMAIL_PAGADOR`, `_CPF_PAGADOR` (opcional), `_URL`; exige `FRUTEIRA_PIX_WEBHOOK_URL` (vai em cada cobrança como `notification_url`; o aviso traz só o id e o sistema confirma consultando o pagamento). O Pix do Mercado Pago dura no mínimo 30 min: o caixa expira antes e cancela a cobrança.
  - **ASAAS** — `POST /v3/payments` (billingType PIX) + `GET /v3/payments/{id}/pixQrCode`, consulta por `externalReference`, exclusão e estorno (`POST /v3/payments/{id}/refund`). Variáveis: `PIX_ASAAS_API_KEY`, `_CLIENTE_ID` (cliente "Consumidor final" `cus_...` criado uma vez no painel), `_EMAIL_WEBHOOK` (para registrar o webhook pela API; ou cadastre no painel), `_URL` (sandbox `https://api-sandbox.asaas.com/v3`). Eventos aceitos: `PAYMENT_RECEIVED` e `PAYMENT_CONFIRMED`.
  - Em Mercado Pago e Asaas o "identificador do pagamento" guardado (onde os bancos guardam o `endToEndId`) é o **id do pagamento** do provedor; é ele que a devolução usa.
  - **Código** (`vendas-service`): `PixProvider` (enum) · `PixStatus` (ATIVA, CONCLUIDA, EXPIRADA, REMOVIDA_PELO_USUARIO_RECEBEDOR: a situação no provedor) · `PixProviderService` (contrato) · `PixConfig` (configuração central, um grupo por provedor). Bancos = subclasses mínimas de `BacenPixService`; Mercado Pago e Asaas = `ApiKeyPixService`. Provedor novo: valor em `PixProvider`, grupo em `PixConfig` e uma classe que implemente `PixProviderService`.
  - Ao vencer a cobrança, o sistema confere o provedor uma última vez e cancela a cobrança lá (evita Pix tardio). `GET /pix/status` e o log de partida listam o que falta configurar.
  - **Testes sem Maven:** `vendas-service/testar-pix.sh` roda as integrações reais contra um servidor HTTP local que imita Mercado Pago, Asaas, Sicoob, Sicredi e Efí (precisa do Jackson: `JACKSON_DIR` ou `~/.m2`). **Isso não prova compatibilidade com o provedor real: nenhuma integração foi testada com conta/sandbox de verdade.** Valide cada uma no sandbox (formato do token, escopos, URLs, prefixo e cabeçalhos variam por banco).
- **Ligar no caixa:** Configurações → Balança e periféricos → PIX → *Integrado ao PSP*. `FRUTEIRA_PIX_OBRIGATORIO=true` recusa PIX sem confirmação do PSP.
- **Fora do escopo:** SSE/WebSocket (hoje é polling), QR na tela do cliente, devolução parcial, conciliação por extrato.

## Saúde dos pagamentos (Pix e TEF, de hora em hora)

O `vendas-service` tem uma rotina agendada que confere se o Pix e o TEF estão funcionando **como foi definido no `.env`**:

- `SAUDE_INTERVALO=1h` — de quanto em quanto tempo (`30m`, `15m`, `2h`…; `off` desliga). `SAUDE_PRIMEIRA_EM` (padrão `1m`) é o atraso da 1ª conferência depois da partida; `SAUDE_TEF_URL` só muda se o agente TEF roda em outro computador; `SAUDE_HISTORICO` = quantas verificações guardar.
- **Pix:** só o provedor de `PIX_PROVIDER`. Vê se a configuração está completa, se dá para autenticar/consultar o provedor (sem criar cobrança), se há URL de webhook e se há Pix recebido há mais de 30 min sem venda.
- **TEF:** só o de `TEF_PROVIDER`; vazio ou `nenhum` = não é conferido. Vê se o agente responde (`/tef/status`, com `TEF_TOKEN`), se é o mesmo provedor do `.env`, se o pinpad está conectado, pendências de conciliação e cartões aprovados sem venda. Nunca passa cartão.
- **Onde ver:** log do serviço (FALHA = erro, ATENÇÃO = aviso), menu *Financeiro e Fiscal → Saúde dos pagamentos* (com “Verificar agora” para supervisor/gerente) e selos no PDV (💠 PIX fora, 📟 TEF fora; amarelo = só aviso; somem quando está tudo bem) (atualizada a cada 5 min). O histórico fica em memória.
- **Testes:** `vendas-service/testar-saude.sh` (regras, só JDK) e `pdv-web/tests/saude.test.jsx`. ⚠ O agendador Quarkus, a chamada real aos PSPs (`/users/me` no Mercado Pago, `/finance/balance` no Asaas, token OAuth nos bancos) e ao agente TEF **não foram executados aqui**: na 1ª vez, abra a tela e clique em “Verificar agora”.

## Dados obrigatórios da nota fiscal (emitir sem rejeição)

Antes de gastar um número de nota, o servidor confere **todos** os campos obrigatórios e devolve a lista do que corrigir (HTTP 422 com cada problema), em vez de a SEFAZ rejeitar depois. A tela **NFC-e / NFG** mostra uma **Conferência fiscal** (emitente, produtos, clientes e fornecedores com problema) e `POST /api/retaguarda/nfce/conferir` confere uma venda sem emitir.

| Grupo | O que é conferido | Onde se corrige |
|---|---|---|
| **Cadastro** (emitente) | CNPJ com dígitos verificadores, razão social, Inscrição Estadual (RS: 10 dígitos + DV), IM (opcional), endereço completo (logradouro, nº, bairro, município, código IBGE de 7 dígitos começando por 43, CEP de 8 dígitos), regime (CRT 1/2/3/4) | `.env` (`FISCAL_*`) |
| **Cadastro** (fornecedores) | CNPJ (ou CPF de produtor rural) válido, IE, IM, e-mail e endereço (se começar a preencher, completo) | Cadastros → Fornecedores (⚠ completar) |
| **Destinatário** | CPF (Nota Fiscal Gaúcha) **ou CNPJ** válidos; cliente com CPF/CNPJ, IE, e-mail e endereço completo (empresa exige IE e endereço) | Cadastros → Clientes |
| **Operação** | Natureza (`FISCAL_NATOP`), CFOP (4 dígitos, 5xxx), unidade, NCM de 8 dígitos, quantidade, valor unitário e total (que conferem entre si), classificação de IBS/CBS | Produtos e preços → 🧾 Fiscal |
| **Tributação** | Por regime: Simples = CSOSN (102/103/300/400/500) · normal = CST do ICMS (00/40/41/50/60; 00 exige alíquota) · PIS/COFINS (01/02 exigem alíquota, 04–09 não tributado, 49/99 outras) · origem 0–8. Vale a classificação do produto, senão o padrão da loja. Totais de vBC/vICMS/vPIS/vCOFINS saem somados dos itens. IPI não existe na NFC-e de varejo | produto ou `.env` |
| **Forma de pagamento** | Meio (01 dinheiro, 03/04 cartão, 05 caderno, 10/11 vales, 17 Pix, 99 outros com `xPag`), **à vista × a prazo** (`indPag`: caderno e crédito parcelado = a prazo), pagamentos ≥ total e troco só com dinheiro | PDV |
| **TEF / maquininha** | Com `FISCAL_EXIGE_TEF` (padrão: ligado com `FISCAL_PROVIDER=sefaz`) cartão só entra na nota com **CNPJ da credenciadora, bandeira e autorização do TEF** (`tpIntegra` 1); CNPJ e bandeira conferidos. Maquininha não integrada: `FISCAL_EXIGE_TEF=false` (`tpIntegra` 2). As parcelas do crédito vêm do TEF | `.env` / TEF |
| **IBS/CBS** | `FISCAL_IBSCBS=auto`: ligado no regime normal (obrigatório desde 03/08/2026) e desligado no Simples/MEI (obrigatório só a partir de 04/01/2027); `on` antecipa. Exige CST (3) e cClassTrib (6) da tabela oficial, no `.env` ou por produto; alíquotas de 2026: CBS 0,9% e IBS 0,1%; redução por produto (`pRedIbsCbs`) | `.env` / Produtos e preços |

- **Migrations** `V14` (cadastro fiscal de clientes/fornecedores), `V15` (classificação fiscal do produto) e `V16` (parcelas do pagamento): apague a pasta `data` **ou** deixe o Flyway aplicá-las no banco existente (são idempotentes). Testadas por `database/testar-migrations.sh` (756 checks).
- **Testes:** `retaguarda-service/testar-fiscal.sh` (42 + 59 checks, só JDK) e `pdv-web/tests/fiscal.test.jsx`.
- ⚠ **Não foi feito / não foi verificado:** (1) **NF-e (modelo 55)** existe como emissor separado (seção abaixo), mas as **URLs dos webservices de NF-e da SEFAZ-RS não foram validadas**, não há contingência (EPEC/SVC) nem inutilização de numeração; (2) o grupo **IBS/CBS** segue a NT 2025.002 (grupo `IBSCBS` por item conferido no leiaute v1.50); o grupo de totais `IBSCBSTot`, a posição no XML e os **códigos cClassTrib de hortifrúti/cesta básica** (com redução de 100%) **não foram validados contra o XSD nem a tabela oficial**: confirme com o contador e teste em **homologação** antes de produção; (3) **tributos aproximados (Lei 12.741)** em `infAdic` não são gerados; (4) nada disso foi compilado com Quarkus nem transmitido à SEFAZ de verdade (sem Maven/certificado aqui). (5) **Gestão de contas bancárias** não existe: há contas a pagar/receber, DRE e fluxo de caixa, sem cadastro de bancos/contas.

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
- **Pré-cadastro (PostgreSQL):** 5 categorias e 69 produtos comuns (unidade, perda média, NCM sugerido, imagem) com PLU por popularidade, vindos de `retaguarda-service/src/main/resources/db/pre-cadastro/R__pre_cadastro_frutas_verduras.sql`. É uma migration **repetível** do Flyway: em **produção** o `retaguarda-service` (dono do Flyway) a aplica sozinho ao subir (depois do schema) e só a repete se o arquivo mudar; é idempotente. Para não carregar: `FLYWAY_LOCATIONS=db/migration` no `.env`. Preços e NCM são **referência**: confira antes de vender/emitir nota.
  - *Desenvolvimento (H2, sem Docker):* o mesmo pré-cadastro (classe `PreCadastro`) é carregado **sozinho ao subir** o `catalogo-service` (`%dev.fruteira.pre-cadastro=true`). Em qualquer ambiente também há o botão **📥 Importar frutas e verduras mais comuns** na tela Produtos (`POST /produtos/pre-cadastro`); pode repetir sem duplicar.
  - *Manual (PostgreSQL sem subir o serviço):* `database/aplicar_sql_local.sh` (ou `.bat`) aplica o mesmo arquivo com o `psql` ou o contêiner `postgres`.
  - A lista existe em dois lugares (o `R__...sql` e `PreCadastro.java`); ao editar uma, atualize a outra.

## Banco de dados: migrations com Flyway (só no retaguarda-service)
Os 4 serviços (catalogo, estoque, vendas, retaguarda) dividem **um único banco** e o **Flyway roda só no `retaguarda-service`**, que é o dono das migrations de todos. Os outros 3 não têm Flyway: o Hibernate apenas **confere** o schema (`validate`); se uma `@Entity` não bater com o banco, o serviço não sobe e diz o que falta. Por isso o `retaguarda-service` **sobe primeiro**: `iniciar.sh/.bat` (dev e produção) já fazem isso e esperam ele responder antes de iniciar os demais.

Tudo fica em `retaguarda-service/src/main/resources/db/migration/`, com **numeração única** e o serviço no nome do arquivo:

| Arquivos | Serviço |
|---|---|
| `V1`–`V3__retaguarda_...` | retaguarda |
| `V4`–`V6__catalogo_...` | catalogo |
| `V7`–`V9__estoque_...` | estoque |
| `V10`–`V12__vendas_...` | vendas |

Em cada serviço: `..._schema_inicial` (tabelas e sequências, `IF NOT EXISTS`), `..._completa_colunas_banco_antigo` (colunas que faltem em bancos criados antes do Flyway) e `..._indices`. Os dados iniciais ficam em `db/pre-cadastro/R__pre_cadastro_frutas_verduras.sql` (só produção).

- **Desenvolvimento:** os 4 serviços usam o **mesmo arquivo H2** (`data/fruteira` na raiz, modo `AUTO_SERVER`), como o banco único de produção. Se o `retaguarda-service` for encerrado, reinicie os demais (ele é quem "abre" o H2). Mudou o caminho? `H2_ARQUIVO` no `.env`. Quem tinha as pastas `<servico>-service/data/` antigas: pode apagá-las (o banco novo nasce vazio; o backup antigo ainda restaura).
- **Produção (PostgreSQL):** nada a rodar à mão; troque os jars (`servicos/`) e reinicie. Faça um backup antes (`backup/`).
- **Banco que já existia** (criado pelo Hibernate ou pela versão anterior do Flyway, com um histórico por serviço): o Flyway assume o banco como base (`baseline-on-migrate`, versão 0) num histórico novo, `flyway_historico`, e roda tudo sem recriar nada (as migrations são idempotentes). As tabelas `flyway_historico_<servico>` antigas ficam sem uso; pode apagá-las.
- **Mudou uma `@Entity` (de qualquer serviço)?** Crie a próxima migration, **nunca edite uma já aplicada** (o Flyway recusa por checksum):
  1. `./database/nova-migration.sh vendas "adiciona campo observacao"` (Windows: `database\nova-migration.bat ...`) cria `V13__vendas_adiciona_campo_observacao.sql` no `retaguarda-service`, com o próximo número;
  2. escreva o SQL **portátil** (roda no H2 e no PostgreSQL: sem `::`, `SERIAL`, `ON CONFLICT`, `ILIKE`, `RETURNING`, blocos `DO`; sem `BEGIN/COMMIT`);
  3. `./database/testar-migrations.sh` (ou `.bat`; precisa de Node 18+; na 1ª vez baixa o PGlite) aplica tudo num PostgreSQL de teste, **compara tabelas, colunas, tipos, NOT NULL, UNIQUE, FK e sequências com as `@Entity` dos 4 serviços** e imprime o SQL que falta; também recusa versão repetida, arquivo sem o nome do serviço e `db/` fora do retaguarda;
  4. suba o `retaguarda-service`: a migration roda sozinha; depois os demais.
- **Pré-cadastro:** `R__pre_cadastro_frutas_verduras.sql` (5 categorias, 69 produtos) é migration repetível: roda na 1ª subida em produção e só repete se o arquivo mudar; é idempotente. Para não carregar: `FLYWAY_LOCATIONS=db/migration` no `.env`. Em dev quem carrega é a classe `PreCadastro` (e o botão **📥 Importar frutas e verduras** na tela Produtos). Manual: `database/aplicar_sql_local.sh|.bat`. A lista existe em dois lugares (o `R__...sql` e `PreCadastro.java`).
- **Contorno de emergência:** `DB_SCHEMA_MODE=update` no `.env` deixa o Hibernate ajustar o schema (não use em produção).
- ⚠ Conferido contra um PostgreSQL de verdade (PGlite) e contra as entidades, mas **ainda não executado pelo Flyway/Maven nem no H2 reais** (sem acesso ao Maven aqui). Pontos a observar na 1ª subida: o H2 compartilhado com `AUTO_SERVER` e o Flyway lendo `V1`…`V12` da mesma pasta.

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
| NFC-e / SAT | retaguarda | Nota Fiscal Gaúcha: CPF no `<dest>`, assinatura A1, SEFAZ-RS (mTLS), contingência, cancelamento, DANFE. **Simulador** por padrão; real = `FISCAL_PROVIDER=sefaz` (ver seção NFC-e) |
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
- O `iniciar.sh`/`iniciar.bat` da pasta também **se autocorrigem**: se faltar `.env`, compose, Keycloak, nginx etc., copiam da raiz do projeto; senha vazia ou de exemplo (`troque-...`) vira uma senha aleatória gravada no `.env` (`./iniciar.sh --preparar` só prepara, sem iniciar).
- O `.env` de produção é criado com **senhas aleatórias** (banco, Keycloak e `TEF_TOKEN`) e **nunca é sobrescrito** ao gerar de novo.
- Rode o gerador na máquina de desenvolvimento (Java, Maven, Node). O **servidor** só precisa de Java 21 + Docker (+ Node se não usar nginx) — sem Maven e sem compilar.
- Mudou `KEYCLOAK_URL`/`FRONT_URL`/domínios? Gere de novo: a URL do Keycloak é embutida no front no build.

## Caixa: turno, sangria e fechamento cego

- **Caixa: turno e sangria** (operador): abre o turno com o **fundo de troco**, faz **sangria** (retirada para o cofre) e **suprimento** (reforço de troco) e encerra informando o dinheiro **contado**. Atalho no PDV: **F8** abre a sangria; o PDV avisa "Gaveta cheia" quando o saldo passa de `CAIXA_LIMITE_GAVETA` (padrão R$ 500), **sem mostrar valores**.
- **Cego**: o operador informa o que contou e **nunca vê o saldo do sistema**. A sangria imprime um comprovante (nº, operador, valor, motivo, linha de assinatura).
- **Conferência de caixa** (gerente): por turno, fundo, sangrias, suprimentos, **esperado × contado × diferença**; sangria maior que o saldo do sistema aparece com ⚠. Tudo também vai para a Auditoria.
- Esperado = fundo de troco + vendas em dinheiro do operador no turno + suprimentos − sangrias. Cada venda guarda o operador (`usuario`). Vendas feitas offline entram no turno do operador que as sincronizar.
- Um turno aberto por operador; o fechamento exige turno aberto. Não há trava de venda sem turno (apenas um aviso no PDV).

## NFC-e e Nota Fiscal Gaúcha (CPF na nota → SEFAZ-RS)

**O que foi feito**
- **CPF no caixa (opcional):** campo com máscara `000.000.000-00`; vazio = venda anônima (a NFC-e sai sem o grupo `<dest>`). CPF incompleto/inválido bloqueia o **Pagar** (apague o campo para vender sem CPF).
- **Validação em Java** (`Cpf.java`, dígitos verificadores módulo 11, rejeita sequências iguais): o `vendas-service` recusa a venda com `422 CPF inválido` e o emissor também recusa; espelho em `pdv-web/src/cpf.js` para o feedback imediato. Endpoints: `GET /api/vendas/vendas/cpf/{cpf}/validar` e `GET /api/retaguarda/nfce/cpf/{cpf}/validar`.
- **XML** (`fiscal/NfceXml.java`): NFC-e modelo 65, layout 4.00, `<dest><CPF>…</CPF>[<xNome>]<indIEDest>9</indIEDest></dest>` só quando há CPF (em homologação o `xNome` e o 1º item recebem os textos obrigatórios do fisco).
- **Assinatura e transmissão** (`Assinador`, `SefazClient`, `NfceEmissor`): XMLDSig com certificado **A1** (.pfx), SOAP 1.2 por **mTLS** para NFeAutorizacao4 / NfeConsulta4 / RecepcaoEvento4 da SEFAZ-RS, síncrono (`indSinc=1`). Implementado **só com o JDK** (sem a biblioteca `wmixvideo/nfe`: o ambiente de desenvolvimento não alcançava o Maven Central, então não pude compilar contra ela). Os mesmos passos (montar → assinar → enviar → interpretar) podem ser trocados pela `wmixvideo/nfe` ou por uma API (Focus NFe, Nuvem Fiscal) mantendo os endpoints de `NfceResource`.
- **QR Code v2** (online e offline), **DANFE NFC-e** 80 mm com CPF, chave, protocolo e QR Code (`Danfe.jsx`), XML baixável (`GET /nfce/{id}/xml`).
- **Retornos:** `100 - Autorizado` conclui e mostra o aviso com **Imprimir DANFE** (sem CPF a venda segue sem interromper o caixa). **Rejeição de CPF** (cStat 237 ou mensagem com “CPF”/“destinatário”): o caixa abre “SEFAZ rejeitou o CPF” e **corrige e reenvia** (mesmo número) ou **emite sem CPF**, sem refazer a venda. Outras rejeições/denegação aparecem na tela NFC-e com cStat e motivo.
- **Contingência offline (tpEmis 9):** sem internet ou SEFAZ fora do ar, a nota é assinada e emitida offline (`dhCont`, `xJust`, QR Code de contingência), fica `CONTINGENCIA` e é transmitida depois pelo botão **Transmitir**. Após timeout o sistema **consulta a chave** antes de decidir (evita nota duplicada); se a SEFAZ não responde, emite em contingência com um novo número (o número pulado deve ser **inutilizado** no portal).
- **Cancelamento** (evento 110111, justificativa ≥ 15 caracteres) pela tela NFC-e.

**Como ligar (`.env`)**: `FISCAL_PROVIDER=sefaz`, emitente (`FISCAL_CNPJ/RAZAO/IE/CMUN/...`), `FISCAL_CSC_ID` + `FISCAL_CSC` (gerados no portal da SEFAZ-RS), o arquivo **`certificado.pfx` na raiz do projeto** (ao lado do `.env`; é achado sozinho) e `FISCAL_CERT_SENHA` (`FISCAL_CERT_PATH` só se o arquivo tiver outro nome ou lugar) (A1) e comece com `FISCAL_AMBIENTE=2` (homologação). `./iniciar.sh` confere o que falta. A tela NFC-e mostra o ambiente e as pendências. Padrão `simulador`: nada vai à SEFAZ.

**Testes sem Maven:** `./retaguarda-service/testar-fiscal.sh` (42 verificações: CPF, XML com/sem `<dest>`, assinatura XMLDSig validada, QR Code, cStat 100/237/302, SEFAZ fora/timeout, contingência e transmissão posterior, cancelamento) usando certificado de teste e uma SEFAZ simulada local. Front: `npm test` (`tests/nfg.test.jsx`).

**NÃO foi testado / confira antes de produção**
- Nunca houve comunicação com a SEFAZ-RS real (a rede do ambiente de desenvolvimento a bloqueia). Valide em **homologação** com o seu certificado: URLs padrão do RS, nomes dos serviços SOAP, a cadeia de certificados (se o TLS falhar use `FISCAL_TRUST_PATH`) e a validação XSD (não há XSD embutido; a SEFAZ responde cStat 225 se o schema divergir).
- Regime tributário montado: **Simples Nacional** (CRT 1, ICMSSN102, PIS/COFINS CST 07). Frutas/verduras podem ter tratamento próprio (isenção, CSOSN 400…): confirme **CFOP/CSOSN** com o contador. Regime normal (CRT 3) não está montado.
- Certificado **A3** (token/cartão) não é lido: use A1 ou um emissor via API. O CSC e o `cIdToken` seguem como configurados (confira zeros à esquerda). Prazo máximo para transmitir a contingência: veja a regra vigente da SEFAZ-RS.
- Vendas gravadas offline no navegador (fila) ainda não geram NFC-e ao sincronizar: emita em NFC-e › Vendas sem nota.

## NF-e — venda a empresa (modelo 55)

Emissor **separado** da NFC-e, usado só quando a empresa compradora exige nota eletrônica. Menu **🏢 NF-e (venda a empresa)** (supervisor). Backend: `/nfe` no retaguarda (`NfeXml`, `NfeEmissor`, `NfeResource`); mesmo certificado A1, emitente e ambiente da NFC-e, **série própria** (`FISCAL_NFE_SERIE`), não exige CSC.

- **Fluxo:** escolha uma venda paga sem nota e uma empresa (cliente com CNPJ, IE e endereço completos em Cadastros → Clientes) → *Conferir dados* → *Emitir NF-e*. Uma venda não recebe NFC-e e NF-e ao mesmo tempo (409).
- **Dados da operação:** consumidor final ou revenda, CFOP 5xxx (RS) ou 6xxx (outra UF) automático, frete (modalidade e valor), desconto (rateado por item), pagamento à vista ou **a prazo com duplicatas** (cobr/fat/dup), informações complementares.
- **Pós-emissão:** cancelar (evento 110111, justificativa ≥ 15 caracteres, dentro do prazo da SEFAZ), **carta de correção** (110110; não corrige valor, imposto, quantidade, destinatário nem datas), DANFE simplificado A4, XML.
- **Sem comunicação com a SEFAZ:** a nota fica **PENDENTE** (XML assinado guardado) e o botão *Transmitir* reenvia. NF-e não tem contingência offline como a NFC-e.
- ⚠ **Não verificado:** compilação com Quarkus, transmissão real à SEFAZ, URLs RS de NF-e (confira e use `FISCAL_NFE_URL_*`), `IBSCBSTot`/cClassTrib, tributos aproximados (Lei 12.741). Teste em **homologação** antes de produção.

### Certificado A1 (`certificado.pfx`), assinatura e integração com a SEFAZ

- **Onde fica:** `certificado.pfx` na raiz do projeto (em produção, na pasta `producao/`, ao lado do `.env`; o `gerar-producao.sh` copia). O `iniciar.sh`/`iniciar.bat` mapeiam o arquivo para `FISCAL_CERT_PATH` e `FRUTEIRA_HOME` sozinhos; a senha vai em `FISCAL_CERT_SENHA`. O arquivo e `*.pfx`/`*.p12` estão no `.gitignore`: **nunca** versione o certificado.
- **Conferência automática:** o sistema lê o certificado e **bloqueia a emissão** se estiver vencido, ainda não vigente, sem uso de assinatura ou com CNPJ de outra empresa (raiz de 8 dígitos diferente do `FISCAL_CNPJ`); avisa 30 dias antes de vencer. Senha errada, arquivo que não é PKCS#12 e arquivo ausente têm mensagem própria. A situação aparece nas telas **NFC-e** e **NF-e** (titular, CNPJ, validade).
- **Assinatura:** XMLDSig enveloped, C14N, RSA-SHA1/SHA1 (padrão da NF-e 4.00), `Signature` sem prefixo depois de `infNFe`/`infNFeSupl`; a assinatura gerada é **verificada antes do envio** (nunca vai à SEFAZ uma nota que ela rejeitaria por assinatura).
- **Integração:** SOAP 1.2 com `action` por serviço, TLS com certificado de cliente (mTLS), HTTP/1.1, timeout configurável. O botão **Testar comunicação com a SEFAZ** (NFC-e e NF-e) faz TLS + `consStatServ` (cStat 107 = em operação) sem emitir nada: **faça isso em homologação antes de emitir a primeira nota**. Se o Java recusar o TLS (cadeia ICP-Brasil fora do `cacerts`), use `FISCAL_TRUST_PATH`.
- **Corrigido nesta revisão:** identificador do CSC no QR Code sem zeros à esquerda (`000001` → `1`); `cNF` nunca igual ao número da nota nem sequencial (rejeição da SEFAZ).
- ⚠ **Não verificado:** nunca falei com a SEFAZ de verdade (sem certificado ICP-Brasil nem rede aqui): os endereços padrão do RS (`FISCAL_URL_*`, `FISCAL_NFE_URL_*`, `*_STATUS`) e os nomes das operações SOAP são os conhecidos, não conferidos; confirme no portal da SEFAZ-RS (a NFC-e do RS pode estar na SVRS, `nfce.svrs.rs.gov.br`) e sobrescreva no `.env` se preciso.

## Backup (pendrive ou Google Drive)

Pasta **`backup/`** (só precisa do Node.js): `backup.bat` / `./backup.sh` gera `backup/saida/fruteira-AAAAMMDD-HHMMSS-<dev|prod>.tar.gz[.enc]` com o banco (dump do PostgreSQL e do Keycloak em produção; pastas `data/` do H2 em desenvolvimento), `.env`, certificado digital A1, realm e certificados TLS, mais um `.sha256` de integridade.

- **Destinos extras** em `backup/backup.env` → `BACKUP_DESTINOS` (pendrive `E:\fruteira`, pasta do app *Drive para computador* `G:\Meu Drive\Fruteira`) ou `RCLONE_DESTINO` (Google Drive via rclone). Pendrive ausente: o backup local fica e o script avisa (código de saída 2).
- **Senha** (`BACKUP_SENHA`, AES-256-GCM): recomendada, pois o backup tem senhas, o certificado e CPFs de clientes. Sem a senha não há como abrir.
- **Agendar:** `agendar.bat` (Windows, 02:00 todo dia) ou `./agendar.sh` (cron). Retenção de 30 dias (`BACKUP_RETENCAO_DIAS`), na pasta local e nos destinos.
- **Restaurar:** `restaurar.bat|.sh <arquivo> --senha ...` (confere o hash, exige digitar SIM; `.env`/certificado/realm saem em `backup/restaurado-<data>/` sem sobrescrever nada).
- Testado com Docker simulado e um pendrive em pasta (backup, criptografia, senha errada, arquivo corrompido, retenção, restauração dev e prod); **não testado** com PostgreSQL real nem no Windows (`.bat`, `schtasks`). Detalhes em `backup/LEIA-ME.txt`.

## Caderno (fiado) e dívida de cliente

- **No PDV:** ao digitar o CPF de um cliente que está devendo, aparece um **alerta** (amarelo; vermelho se a dívida mais velha tem 30 dias ou mais) com o botão **Ver caderno**: saldo, desde quando, limite, últimos lançamentos e **Receber** (dinheiro/Pix/cartão; em dinheiro o valor entra no turno como suprimento, então a conferência cega do caixa continua certa).
- **Anotar a dívida:** na tela de pagamento use **Caderno (fiado)** (**F10** ou tecla 7). Exige CPF de **cliente cadastrado** e respeita o limite (padrão R$ 300, `CADERNO_LIMITE_PADRAO`; por cliente, o gerente muda na tela *Clientes*: vazio = padrão, 0 = não pode fiar). Pode misturar com outras formas (ex.: R$ 20 em dinheiro + o resto no caderno). Ao escolher Caderno, **tudo o que falta pagar** já vai para a dívida (a tela mostra "No caderno, para pagar depois" e o botão vira "Finalizar e anotar R$ X no caderno"): a venda fecha sem receber nada na hora. Precisa de rede: não entra na fila offline.
- **Tela Caderno** (menu Financeiro, supervisor): quem está devendo, total a receber, atrasados, maior devedor, receber; **perdão** só gerente (com motivo, vai para a auditoria).
- **Relatório "Clientes: compras e dívidas"** (menu Relatórios, gerente): período, quem mais compra (total, nº de compras, ticket médio, última compra), quem mais deve, fiado sobre o comprado e exportação CSV.
- **Regras:** o pagamento abate a dívida **mais antiga primeiro**; cancelar a venda (PIN do gerente) estorna a dívida; nada é apagado (lançamento errado = cancelar, só gerente; tudo na auditoria). NFC-e usa tPag **05** (crédito loja).
- **Banco:** migration `V13` (tabela `CadernoLancamento` + `Cliente.limiteCaderno`). Teste da conta: `retaguarda-service/testar-caderno.sh`.
- Limite conhecido: o cashback de 2% continua sendo creditado sobre o total da compra, inclusive a parte fiada.

## Pendências para produção

**Antes de operar de verdade**
- Compilar e subir os 5 serviços Java numa máquina com Maven (nada do Java foi compilado/executado no ambiente de desenvolvimento do assistente; só checagem de sintaxe). Rode `gerar-producao` e acompanhe os `logs/`.
- Confirmar o formato real das APIs de NCM (BrasilAPI e Siscomex): os leitores aceitam variações de campos, mas não foram testados contra as APIs reais.
- **NFC-e real**: emissor SEFAZ-RS implementado (seção *NFC-e e Nota Fiscal Gaúcha*), mas **ainda não homologado** com certificado e SEFAZ reais; falta validar regime tributário/CFOP com o contador e o A3.
- **TEF real**: trocar `TEF_PROVIDER=simulador` pelo provedor contratado (CliSiTef/PayGo) e homologar; vales (Alelo, Ticket, Pluxee, VR...) estão lançados manualmente, sem autorização online.
- **PIX real**: `PIX_PROVIDER=EFI` (ou ITAU, BRADESCO, SICOOB, SICREDI) com as variáveis `PIX_<PROVEDOR>_*`, o certificado do provedor e `FRUTEIRA_PIX_WEBHOOK_SEGREDO`; MERCADO_PAGO e ASAAS usam as variáveis `PIX_MERCADO_PAGO_*` / `PIX_ASAAS_*`.
- **Migrations Flyway** (só no retaguarda-service; seção *Banco de dados*): conferidas contra PostgreSQL (PGlite) e as `@Entity`, mas nunca executadas pelo Flyway/Maven nem no H2 reais; valide na primeira subida (dev e produção).
- Trocar as senhas temporárias do Keycloak (`trocar123`) e definir `TEF_TOKEN` igual nos caixas.
- Testes automatizados existem só no front (24); faltam testes dos serviços Java.

**Evoluções**
- Banco local no PDV (SQLite) para o modo híbrido; a fila offline atual fica no navegador. Sincronização nuvem+local completa.
- Precificação dinâmica (preço por fim de dia/validade) e delivery com pedidos.
- Mensageria (Kafka/RabbitMQ) e API Gateway entre os serviços.
