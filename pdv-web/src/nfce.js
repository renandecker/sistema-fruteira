import { cfg } from './lib.jsx'
import { tBand } from './tef.js'
import { somenteDigitos } from './cpf.js'

const TPAG = { DINHEIRO: '01', CREDITO: '03', DEBITO: '04', VALE_ALIMENTACAO: '10', VALE_REFEICAO: '11', PIX: '17' }

/** Monta o corpo de POST /nfce/emitir a partir da venda gravada (itens com NCM, pagamentos com dados do cartão) e do CPF (opcional). */
export function montarEmissao(venda, produtos = [], cpf = '', nome = '', cartoes = null) {
  const porId = new Map(produtos.map(p => [p.id, p]))
  const itens = (venda.itens || []).map(i => { const p = porId.get(i.produtoId) || {}
    return { codigo: String(p.plu ?? i.produtoId), nome: i.nome, ncm: p.ncm || '', unidade: p.unidade || 'UN', qtd: i.quantidade, valorUnit: i.precoUnit, valor: i.subtotal } })
  const pagamentos = (venda.pagamentos || []).map(p => ({ tPag: TPAG[p.meio] || '99', valor: p.valor,
    ...(p.nsu || p.tefId ? { cnpjCredenciadora: p.cnpjCredenciadora || null, tBand: tBand(p.bandeira), cAut: p.autorizacao || null } : {}) }))
  return { vendaId: venda.id, valor: venda.total, cpf: somenteDigitos(cpf) || null, nome: nome || null, contingencia: typeof navigator !== 'undefined' && !navigator.onLine, cartoes, itens, pagamentos }
}

const json = (r) => r.ok ? r.json() : r.text().then(t => Promise.reject(t || `HTTP ${r.status}`))
const H = { 'Content-Type': 'application/json' }
export const emitirNfce = corpo => fetch('/api/retaguarda/nfce/emitir', { method: 'POST', headers: H, body: JSON.stringify(corpo) }).then(json)
/** Nota rejeitada por CPF: reenvia com o CPF corrigido (vazio = sem CPF), mantendo o número. */
export const corrigirCpfNfce = (id, cpf, nome) => fetch(`/api/retaguarda/nfce/corrigir-cpf/${id}`, { method: 'POST', headers: H, body: JSON.stringify({ cpf: somenteDigitos(cpf) || null, nome: nome || null }) }).then(json)

/** O que o caixa deve fazer com o retorno: 'ok' (100) · 'contingencia' · 'corrigir-cpf' · 'rejeitada' */
export function situacaoNota(n) {
  if (!n) return 'rejeitada'
  if (n.status === 'AUTORIZADA') return 'ok'
  if (n.status === 'CONTINGENCIA') return 'contingencia'
  if (n.status === 'REJEITADA' && (n.cStat === 237 || /cpf|destinat/i.test(n.xMotivo || ''))) return 'corrigir-cpf'
  return 'rejeitada'
}
export const emitente = () => { const c = cfg(); return { nome: c.empresaNome || 'Fruteira Conventos', cnpj: c.empresaCnpj || '' } }
