// Catálogo de cartões aceitos (benefícios e rede Banricompras).
// saldos: ALIMENTACAO → meio VALE_ALIMENTACAO (NFC-e tPag 10) · REFEICAO → VALE_REFEICAO (tPag 11)
// bancario: cartão de débito/crédito (meios DEBITO/CREDITO, tPag 04/03)
// Mantenha em sincronia com vendas-service/.../Cartoes.java
export const CARTOES = [
  { id: 'ALELO_ALIMENTACAO', nome: 'Alelo Alimentação', grupo: 'Alelo', saldos: ['ALIMENTACAO'], info: 'Compras de alimentos: supermercados, açougues, mercearias e padarias.' },
  { id: 'ALELO_REFEICAO', nome: 'Alelo Refeição', grupo: 'Alelo', saldos: ['REFEICAO'], info: 'Refeições prontas: restaurantes, lanchonetes e fast food.' },
  { id: 'ALELO_TUDO', nome: 'Alelo Tudo (Flexível)', grupo: 'Alelo', saldos: ['ALIMENTACAO', 'REFEICAO'], info: 'Um cartão com vários saldos (Alimentação, Refeição, Mobilidade, Farmácia e Saúde). Na fruteira vale o saldo Alimentação/Refeição.' },
  { id: 'BANRICARD', nome: 'Banricard (benefícios)', grupo: 'Banrisul', saldos: ['ALIMENTACAO', 'REFEICAO'], info: 'Cartão corporativo de benefícios do Grupo Banrisul.' },
  { id: 'BANRICOMPRAS', nome: 'Banricompras', grupo: 'Banrisul', bancario: true, info: 'Cartão de débito/crédito Banricompras (Banrisul).' },
  { id: 'TICKET', nome: 'Ticket (Edenred)', grupo: 'Edenred', saldos: ['ALIMENTACAO', 'REFEICAO'], info: 'Ticket Alimentação e Ticket Restaurante.' },
  { id: 'PLUXEE', nome: 'Pluxee (antiga Sodexo)', grupo: 'Pluxee', saldos: ['ALIMENTACAO', 'REFEICAO'], info: 'Alimentação e refeição.' },
  { id: 'VR', nome: 'VR Benefícios', grupo: 'VR', saldos: ['ALIMENTACAO', 'REFEICAO'], info: 'VR Alimentação e VR Refeição.' },
  { id: 'GREENCARD', nome: 'Green Card', grupo: 'Green Card', saldos: ['ALIMENTACAO', 'REFEICAO'], info: 'Benefícios do PAT (Programa de Alimentação do Trabalhador).' },
  { id: 'VALECARD_VOLUS', nome: 'ValeCard / Vólus', grupo: 'ValeCard', saldos: ['ALIMENTACAO', 'REFEICAO'], info: 'Soluções corporativas regionais e gestão de benefícios.' },
]
const SALDO_DO_MEIO = { VALE_ALIMENTACAO: 'ALIMENTACAO', VALE_REFEICAO: 'REFEICAO' }
export const meioDeBeneficio = meio => !!SALDO_DO_MEIO[meio]
/** Cartões que podem pagar o meio informado (vales → por saldo; débito/crédito → rede bancária). */
export const cartoesDoMeio = meio => meioDeBeneficio(meio) ? CARTOES.filter(c => c.saldos?.includes(SALDO_DO_MEIO[meio]))
  : (meio === 'CREDITO' || meio === 'DEBITO') ? CARTOES.filter(c => c.bancario) : []
export const nomeCartao = id => CARTOES.find(c => c.id === id)?.nome ?? id
