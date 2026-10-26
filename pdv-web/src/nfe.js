// NF-e (modelo 55) para venda a empresa: monta o corpo de POST /nfe/emitir a partir de uma venda do PDV. Funções puras (testadas em tests/nfe.test.jsx).
import { montarEmissao } from './nfce.js'
import { somenteDigitos } from './cpf.js'

const cent = v => Math.round(Number(v || 0) * 100)
const reais = c => Math.round(c) / 100

/** Divide o total em n parcelas (centavos exatos: a última leva a sobra), a cada `intervalo` dias a partir de `hoje`+`primeiro`. venc em AAAA-MM-DD. */
export function gerarDuplicatas(total, n, { hoje = new Date(), primeiro = 30, intervalo = 30 } = {}) {
  const t = cent(total), qtd = Math.max(1, Math.floor(Number(n) || 1)); const base = Math.floor(t / qtd); const l = []
  for (let i = 0; i < qtd; i++) {
    const d = new Date(Date.UTC(hoje.getFullYear(), hoje.getMonth(), hoje.getDate() + primeiro + i * intervalo))
    l.push({ venc: d.toISOString().slice(0, 10), valor: reais(i === qtd - 1 ? t - base * (qtd - 1) : base) })
  }
  return l
}

/** Os pagamentos da venda, cortados para somar exatamente o total da NF-e (a NF-e não tem troco). */
export function ajustarPagamentos(pagamentos, total) {
  let falta = cent(total); const r = []
  for (const p of pagamentos || []) {
    if (falta <= 0) break
    const v = Math.min(cent(p.valor), falta); if (v > 0) { r.push({ ...p, valor: reais(v) }); falta -= v }
  }
  if (falta > 0 && r.length) r[r.length - 1] = { ...r[r.length - 1], valor: reais(cent(r[r.length - 1].valor) + falta) }
  return r
}

/** opcoes: { empresaDoc, aPrazo, parcelas, primeiro, intervalo, modFrete, vFrete, vDesc, natOp, infCpl, consumidorFinal, hoje } */
export function montarNfe(venda, produtos, o = {}) {
  const base = montarEmissao(venda, produtos, '', '', null)
  const vFrete = reais(cent(o.vFrete)), vDesc = reais(cent(o.vDesc))
  const total = reais(base.itens.reduce((s, i) => s + cent(i.valor), 0) + cent(vFrete) - cent(vDesc))
  const duplicatas = o.aPrazo ? gerarDuplicatas(total, o.parcelas || 1, { hoje: o.hoje, primeiro: o.primeiro ?? 30, intervalo: o.intervalo ?? 30 }) : []
  const pagamentos = o.aPrazo ? [{ tPag: '15', valor: total }] : ajustarPagamentos(base.pagamentos, total)
  return { vendaId: venda.id, clienteDoc: somenteDigitos(o.empresaDoc), consumidorFinal: !!o.consumidorFinal, modFrete: vFrete > 0 ? (Number(o.modFrete) === 9 ? 0 : Number(o.modFrete ?? 0)) : 9,
    vFrete: vFrete || null, vDesc: vDesc || null, natOp: o.natOp || null, infCpl: o.infCpl || null, indPres: 1, itens: base.itens, pagamentos, duplicatas }
}

const json = r => r.ok ? r.json() : r.text().then(t => Promise.reject(t || `HTTP ${r.status}`))
const H = { 'Content-Type': 'application/json' }
const P = (u, b) => fetch(u, { method: 'POST', headers: H, body: b == null ? undefined : JSON.stringify(b) }).then(json)
export const conferirNfe = corpo => P('/api/retaguarda/nfe/conferir', corpo)
export const emitirNfe = corpo => P('/api/retaguarda/nfe/emitir', corpo)
export const acaoNfe = (id, acao, corpo) => P(`/api/retaguarda/nfe/${id}/${acao}`, corpo)
