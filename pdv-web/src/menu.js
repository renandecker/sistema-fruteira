// status: 'ok' = tela pronta | 'api' = backend pronto, tela em breve | 'soon' = a desenvolver
// perfil = nível mínimo para ver o item (operador < supervisor < gerente)
export const NIVEL = { operador: 1, supervisor: 2, gerente: 3 }
export const MENU = [
  { grupo: 'Vendas', itens: [
    { id: 'pdv', icone: '🛒', titulo: 'PDV / Caixa', status: 'ok', perfil: 'operador' },
    { id: 'fechamento', icone: '💵', titulo: 'Fechamento de caixa', status: 'ok', perfil: 'operador' },
    { id: 'selfcheckout', icone: '🧺', titulo: 'Self-checkout', status: 'ok', perfil: 'supervisor', desc: 'Terminal de autoatendimento com balança e leitor de código de barras.' },
    { id: 'consulta', icone: '🏷️', titulo: 'Consulta de preço', status: 'ok', perfil: 'operador', desc: 'Terminal touch no salão para pesar e conferir o valor antes do caixa.' },
  ]},
  { grupo: 'Cadastros', itens: [
    { id: 'produtos', icone: '🍎', titulo: 'Produtos e preços', status: 'ok', perfil: 'supervisor' },
    { id: 'categorias', icone: '🗂️', titulo: 'Categorias', status: 'ok', perfil: 'supervisor' },
    { id: 'promocoes', icone: '🔥', titulo: 'Promoções', status: 'ok', perfil: 'supervisor' },
    { id: 'fornecedores', icone: '🚚', titulo: 'Fornecedores', status: 'ok', perfil: 'gerente', desc: 'Feirantes, produtores rurais e CEASA.' },
    { id: 'clientes', icone: '👥', titulo: 'Clientes e fidelidade', status: 'ok', perfil: 'supervisor', desc: 'Cadastro por CPF, pontos e cashback.' },
  ]},
  { grupo: 'Estoque', itens: [
    { id: 'entrada', icone: '📦', titulo: 'Entrada de mercadoria', status: 'ok', perfil: 'supervisor' },
    { id: 'perdas', icone: '🥀', titulo: 'Perdas e avarias', status: 'ok', perfil: 'supervisor' },
    { id: 'producao', icone: '🔪', titulo: 'Produção / fracionamento', status: 'ok', perfil: 'supervisor', desc: 'Transforma insumos (abacaxi inteiro) em produtos prontos (bandeja picada). O endpoint POST /estoque/producao já existe.' },
  ]},
  { grupo: 'Compras', itens: [
    { id: 'xml', icone: '🧾', titulo: 'Importar XML (NF-e)', status: 'ok', perfil: 'gerente', desc: 'Entrada por XML e nota de produtor rural, com atualização de estoque e custo.' },
    { id: 'ceasa', icone: '📈', titulo: 'Cotações CEASA', status: 'ok', perfil: 'gerente', desc: 'Histórico de preço pago por fornecedor e época.' },
    { id: 'sugestao', icone: '🌦️', titulo: 'Sugestão de compras', status: 'ok', perfil: 'gerente', desc: 'Histórico de vendas por dia da semana somado à previsão do tempo.' },
  ]},
  { grupo: 'Financeiro e Fiscal', itens: [
    { id: 'contas', icone: '💳', titulo: 'Contas a pagar/receber', status: 'ok', perfil: 'gerente', desc: 'Lançamentos, vencimentos e baixas.' },
    { id: 'dre', icone: '📊', titulo: 'DRE e fluxo de caixa', status: 'ok', perfil: 'gerente', desc: 'DRE simplificado e fluxo diário/mensal.' },
    { id: 'nfce', icone: '🧮', titulo: 'NFC-e / SAT', status: 'ok', perfil: 'supervisor', desc: 'Emissão, contingência offline, cancelamento e reenvio.' },
  ]},
  { grupo: 'Relatórios', itens: [
    { id: 'abc', icone: '🏆', titulo: 'Curva ABC', status: 'ok', perfil: 'gerente' },
    { id: 'relperdas', icone: '🗑️', titulo: 'Perdas / descarte', status: 'ok', perfil: 'gerente' },
    { id: 'auditoria', icone: '🕵️', titulo: 'Auditoria', status: 'ok', perfil: 'gerente' },
    { id: 'margem', icone: '💹', titulo: 'Margem por categoria', status: 'ok', perfil: 'gerente', desc: 'Margem de lucro por categoria, com a quebra esperada.' },
  ]},
  { grupo: 'Marketing', itens: [
    { id: 'encartes', icone: '📣', titulo: 'Encartes / WhatsApp', status: 'ok', perfil: 'gerente', desc: 'Envio das ofertas do dia (ex.: "Quarta da Feira") para a lista de clientes.' },
    { id: 'etiquetas', icone: '🔖', titulo: 'Etiquetas e EAN-13', status: 'ok', perfil: 'supervisor', desc: 'Etiquetas com lote, validade, tabela nutricional e código com peso/preço embutido.' },
  ]},
  { grupo: 'Configurações', itens: [
    { id: 'usuarios', icone: '🔐', titulo: 'Usuários e perfis', status: 'ok', perfil: 'gerente', desc: 'Permissões para cancelamento, desconto e estorno (login com Keycloak).' },
    { id: 'perifericos', icone: '⚙️', titulo: 'Balança e periféricos', status: 'ok', perfil: 'gerente', desc: 'Porta serial, TEF, impressoras e gaveta.' },
  ]},
]
