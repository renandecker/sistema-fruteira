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
