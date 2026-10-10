import { cfg, api, post } from './lib.jsx'

// Cliente do AGENTE TEF local (tef-agent, em http://127.0.0.1:8090): o navegador não fala com o pinpad; o agente sim.
export const tefAtivo = () => { const t = cfg().tef; return !!t && t !== 'nenhum' }
const baseUrl = c => (c.tefUrl || 'http://127.0.0.1:8090').replace(/\/$/, '')

async function ag(caminho, metodo = 'GET', corpo, c = cfg()) {
  let r
  try {
    r = await fetch(baseUrl(c) + '/tef' + caminho, { method: metodo, headers: { 'Content-Type': 'application/json', ...(c.tefToken ? { 'X-Tef-Token': c.tefToken } : {}) }, body: corpo ? JSON.stringify(corpo) : undefined })
  } catch {
    const e = new Error(`O agente TEF não responde em ${baseUrl(c)}. Ele está rodando neste computador?`); e.semAgente = true; throw e
  }
  if (!r.ok) throw new Error((await r.text()) || `Agente TEF respondeu HTTP ${r.status}`)
  return r.json()
}
export const agente = {
  status: c => ag('/status', 'GET', undefined, c),
  iniciar: p => ag('/transacoes', 'POST', p),
  consultar: req => ag(`/transacoes/${encodeURIComponent(req)}`),
  cancelar: req => ag(`/transacoes/${encodeURIComponent(req)}/cancelar`, 'POST'),
  confirmar: req => ag(`/transacoes/${encodeURIComponent(req)}/confirmar`, 'POST'),
  desfazer: req => ag(`/transacoes/${encodeURIComponent(req)}/desfazer`, 'POST'),
  pendencias: () => ag('/pendencias'),
  estornar: e => ag('/estornos', 'POST', e),
}

export const tipoTef = (meio, parcelas) => meio === 'DEBITO' ? 'DEBITO' : parcelas > 1 ? 'CREDITO_PARCELADO' : 'CREDITO_A_VISTA'

// ---- NFC-e: dados do cartão (tPag, bandeira, credenciadora, autorização) ----
const TBAND = { visa: '01', mastercard: '02', 'american express': '03', amex: '03', sorocred: '04', diners: '05', 'diners club': '05', elo: '06', hipercard: '07', aura: '08', cabal: '09' }
export const tBand = nome => TBAND[(nome || '').toLowerCase().trim()] || '99'
const TPAG_VALE = { VALE_ALIMENTACAO: '10', VALE_REFEICAO: '11' }   // 10 = vale alimentação · 11 = vale refeição
export function cartoesNfce(pagamentos = []) {
  const vales = pagamentos.filter(p => TPAG_VALE[p.meio]).map(p => ({ tPag: TPAG_VALE[p.meio], bandeira: p.operadora || null, operadora: p.operadora || null, tBand: '99', cnpjCredenciadora: null, cAut: p.autorizacao || null, nsu: p.nsu || null, valor: p.valor }))
  const pix = pagamentos.filter(p => p.pixTxid).map(p => ({ tPag: '17', bandeira: 'PIX', nsu: p.pixE2e, cnpjCredenciadora: null, tBand: null, cAut: null, valor: p.valor }))
  return JSON.stringify([...pagamentos.filter(p => p.nsu || p.tefId).map(p => ({
    tPag: p.meio === 'DEBITO' ? '04' : '03', cnpjCredenciadora: p.cnpjCredenciadora || null, tBand: tBand(p.bandeira),
    bandeira: p.bandeira || null, cAut: p.autorizacao || null, nsu: p.nsu || null, valor: p.valor })), ...pix, ...vales])
}
export function cartoesTexto(json) {
  try { return JSON.parse(json || '[]').map(c => c.tPag === '17' ? `PIX · ${c.nsu || '—'}` : (c.tPag === '10' || c.tPag === '11') ? `${c.tPag === '10' ? 'Vale-alimentação' : 'Vale-refeição'} · ${c.operadora || '—'}` : `${c.bandeira || 'cartão'} · aut ${c.cAut || '—'} · NSU ${c.nsu || '—'}`).join('; ') || '—' } catch { return '—' }
}

/** Imprime as duas vias (cliente e estabelecimento) do comprovante TEF. */
export function imprimirComprovantes(t) {
  if (!t || (!t.comprovanteCliente && !t.comprovanteLoja)) return
  const el = document.createElement('div'); el.className = 'area-print comprovante'
  for (const via of [t.comprovanteCliente, t.comprovanteLoja]) if (via) { const pre = document.createElement('pre'); pre.textContent = via; pre.style.pageBreakAfter = 'always'; el.appendChild(pre) }
  document.body.appendChild(el)
  try { window.print() } finally { el.remove() }
}

/** Confirma no agente e no servidor (venda já gravada). Se algo falhar, a transação fica pendente e a reconciliação resolve. */
export async function confirmarTef({ tefId, requisicao }) {
  try { await agente.confirmar(requisicao); await post(`/api/vendas/tef/${tefId}/confirmar`); return { ok: true } }
  catch (e) { return { ok: false, erro: String(e.message || e) } }
}
/** Desfaz no agente e no servidor (venda não concluída). Se o agente falhar, nada é marcado: continua pendente para nova tentativa. */
export async function desfazerTef({ tefId, requisicao }) {
  try { await agente.desfazer(requisicao); await post(`/api/vendas/tef/${tefId}/desfazer`); return { ok: true } }
  catch (e) { return { ok: false, erro: String(e.message || e) } }
}

/** Reconciliação (queda no meio da operação): venda gravada → confirma; sem venda → desfaz; sem retorno → ERRO. */
export async function reconciliar() {
  if (!tefAtivo()) return { resolvidas: 0 }
  let pend; try { pend = await api('/api/vendas/tef/pendencias') } catch { return { resolvidas: 0 } }
  let n = 0
  for (const p of pend) {
    try {
      if (p.status === 'PENDENTE') {            // iniciada e sem resultado: pergunta ao agente o que aconteceu
        const ag = await agente.consultar(p.requisicao).catch(() => null)
        if (ag && ag.estado === 'APROVADA' && ag.resultado) await post(`/api/vendas/tef/${p.id}/resultado`, ag.resultado)
        else { if (ag) await agente.cancelar(p.requisicao).catch(() => {}); await post(`/api/vendas/tef/${p.id}/resultado`, { status: 'ERRO', mensagem: 'Sem retorno do agente (reconciliação)' }); n++; continue }
      }
      const ref = { tefId: p.id, requisicao: p.requisicao }
      const r = p.vendaId ? await confirmarTef(ref) : await desfazerTef(ref)
      if (r.ok) n++
    } catch { /* tenta de novo na próxima reconciliação */ }
  }
  return { resolvidas: n }
}
