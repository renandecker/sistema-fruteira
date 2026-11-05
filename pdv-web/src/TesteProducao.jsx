import React, { useState } from 'react'
import { api } from './lib.jsx'
import { agente } from './tef.js'

// Teste de produção: confere, deste computador, se o TEF, o Pix e a impressora estão respondendo.
// Nada aqui cobra de verdade nem cria cobrança: TEF só consulta o status do agente, Pix só consulta o servidor.

/** Resultado do /tef/status do agente → { nivel: 'ok' | 'aviso' | 'erro', linhas: [...] }. */
export function avaliarTef(r) {
  const l = []; let nivel = 'ok'; const pior = n => { if (n === 'erro' || (n === 'aviso' && nivel === 'ok')) nivel = n }
  l.push(`Provedor: ${r.provedor}${r.descricao ? ` — ${r.descricao}` : ''}`)
  if (r.provedor === 'simulador') { l.push('SIMULADOR: não cobra de verdade. Em produção defina TEF_PROVIDER no .env deste computador.'); pior('aviso') }
  else if (r.implementado === false) { l.push(`A integração com ${r.provedor} ainda NÃO conversa com a maquininha: nenhuma cobrança no cartão vai funcionar (use o modo manual).`); pior('erro') }
  for (const p of r.problemas || []) { l.push(`Falta configurar: ${p}`); pior('erro') }
  if (r.provedor !== 'simulador') l.push(r.pinpad ? 'Maquininha/pinpad: conectada' : 'Maquininha/pinpad: NÃO conectada'), r.pinpad || pior('erro')
  if (r.pendencias > 0) { l.push(`${r.pendencias} transação(ões) aprovada(s) sem confirmação (reconciliação pendente)`); pior('aviso') }
  return { nivel, linhas: l }
}
/** Resultado do /pix/status do servidor. */
export function avaliarPix(r) {
  const l = []; let nivel = 'ok'
  l.push(`Provedor: ${r.provedor}${r.chave ? ` · chave ${r.chave}` : ''}`)
  if (r.simulador) { l.push('SIMULADOR: não é Pix real. Em produção defina PIX_PROVIDER no .env do servidor.'); nivel = 'aviso' }
  for (const p of r.problemas || []) { l.push(`Falta configurar: ${p}`); nivel = 'erro' }
  l.push(r.webhookConfigurado ? 'Webhook (confirmação automática): configurado' : 'Webhook (confirmação automática): NÃO configurado — o caixa consulta o PSP a cada poucos segundos')
  if (!r.webhookConfigurado && nivel === 'ok') nivel = 'aviso'
  return { nivel, linhas: l }
}

const COR = { ok: 'var(--verde, #2e7d32)', aviso: '#b26a00', erro: 'var(--vermelho)' }
const ICONE = { ok: '✔', aviso: '⚠', erro: '✖' }

function Resultado({ r }) {
  if (!r) return null
  if (r.carregando) return <div>consultando…</div>
  return <div style={{ color: COR[r.nivel], fontSize: 14 }}>{r.linhas.map((x, i) => <div key={i}>{i === 0 ? ICONE[r.nivel] + ' ' : '  '}{x}</div>)}</div>
}

export function textoTeste(f, usuario) {
  const agora = new Date().toLocaleString('pt-BR')
  return ['FRUTEIRA CONVENTOS', 'TESTE DE IMPRESSORA', '--------------------------------', `Data: ${agora}`, `Caixa: ${f.tefTerminal || '—'}`, `Usuário: ${usuario || '—'}`,
    `Impressora: ${f.impressora || '—'}`, '--------------------------------', '1234567890 ABCDEFGHIJKLMNOPQRSTUVWXYZ', 'áéíóú ç ã õ R$ 12,50', '--------------------------------', 'Se você leu isto, a impressora', 'está respondendo.', '', '', ''].join('\n')
}

export default function TesteProducao({ f, imprimir = txt => { const el = document.createElement('div'); el.className = 'area-print comprovante'; const pre = document.createElement('pre'); pre.textContent = txt; el.appendChild(pre); document.body.appendChild(el); try { window.print() } finally { el.remove() } } }) {
  const [tef, setTef] = useState(null); const [pix, setPix] = useState(null); const [imp, setImp] = useState(null)
  const usuario = (() => { try { return JSON.parse(sessionStorage.getItem('user') || 'null')?.login } catch { return null } })()

  const testarTef = async () => {
    if (!f.tef || f.tef === 'nenhum') return setTef({ nivel: 'aviso', linhas: ['TEF em modo Manual neste caixa: a maquininha é operada à mão, não há nada a testar.'] })
    setTef({ carregando: true })
    try { setTef(avaliarTef(await agente.status(f))) } catch (e) { setTef({ nivel: 'erro', linhas: [String(e.message || e)] }) }
  }
  const testarPix = async () => {
    if (f.pix !== 'psp') return setPix({ nivel: 'aviso', linhas: ['Pix em modo Demonstração neste caixa (QR fictício). Escolha "Integrado ao PSP" para testar de verdade.'] })
    setPix({ carregando: true })
    try { setPix(avaliarPix(await api('/api/vendas/pix/status'))) } catch (e) { setPix({ nivel: 'erro', linhas: [String(e.message || e)] }) }
  }
  const testarImpressora = () => { setImp({ nivel: 'aviso', linhas: ['Enviei a página de teste. Confirme abaixo se o papel saiu.'], aguardando: true }); imprimir(textoTeste(f, usuario)) }
  const confirmarImp = saiu => setImp(saiu ? { nivel: 'ok', linhas: [`Impressora "${f.impressora}" respondeu: papel de teste impresso`] } : { nivel: 'erro', linhas: ['O papel não saiu: confira cabo/USB, papel, se ela é a impressora padrão do computador e a escolha no diálogo de impressão'] })
  const tudo = async () => { await testarTef(); await testarPix(); testarImpressora() }

  return <div className="teste-producao" style={{ border: '2px solid var(--borda)', borderRadius: 10, padding: 12, display: 'grid', gap: 10 }}>
    <h3 style={{ margin: 0 }}>🧪 Teste de produção deste caixa</h3>
    <small>Não cobra nada e não cria cobrança: o TEF e o Pix só consultam o estado; a impressora imprime uma página de teste.</small>
    <div><button className="btn sec" onClick={testarTef}>Testar TEF (maquininha)</button><Resultado r={tef} /></div>
    <div><button className="btn sec" onClick={testarPix}>Testar Pix</button><Resultado r={pix} /></div>
    <div><button className="btn sec" onClick={testarImpressora}>Imprimir página de teste</button><Resultado r={imp} />
      {imp?.aguardando && <div style={{ marginTop: 6 }}>O papel saiu? <button className="btn" onClick={() => confirmarImp(true)}>Sim, saiu</button>{' '}<button className="btn sec" onClick={() => confirmarImp(false)}>Não saiu</button></div>}
      <small>O navegador não enxerga a impressora: a confirmação é sua. A NFC-e/DANFE sai pela mesma impressora (térmica comum); não há integração com impressora fiscal (ECF).</small></div>
    <div><button className="btn" onClick={tudo}>Testar tudo</button></div>
  </div>
}
