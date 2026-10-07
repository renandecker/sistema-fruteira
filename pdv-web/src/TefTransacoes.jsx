import React, { useEffect, useState } from 'react'
import { post, brl, useLista, Pagina, Aviso } from './lib.jsx'
import { agente, reconciliar, imprimirComprovantes, tefAtivo } from './tef.js'

const COR = { PENDENTE: '#e0a100', APROVADA: '#1976d2', CONFIRMADA: 'var(--verde)', NEGADA: 'var(--vermelho)', CANCELADA: '#757575', ERRO: 'var(--vermelho)', DESFEITA: '#8d6e63', ESTORNADA: '#7b1fa2' }
const TIPO = { CREDITO_A_VISTA: 'Crédito à vista', CREDITO_PARCELADO: 'Crédito parcelado', DEBITO: 'Débito' }

export default function TefTransacoes() {
  const [lista, recarregar] = useLista('/api/vendas/tef?limit=100'); const [pend, recPend] = useLista('/api/vendas/tef/pendencias')
  const [st, setSt] = useState(null); const [msg, setMsg] = useState(''); const [ocupado, setOcupado] = useState(false)
  const testar = () => agente.status().then(s => setSt({ ok: true, ...s })).catch(e => setSt({ ok: false, erro: String(e.message || e) }))
  useEffect(() => { testar() }, [])

  const resolver = async () => {
    setOcupado(true); const r = await reconciliar()
    setMsg(`${r.resolvidas} pendência(s) resolvida(s).`); recarregar(); recPend(); testar(); setOcupado(false)
  }
  const estornar = async t => {
    if (!window.confirm(`Estornar ${brl(t.valor)} no cartão (NSU ${t.nsu})?\nSe a venda #${t.vendaId} também deve ser cancelada, faça isso depois do estorno.`)) return
    let r
    try {
      r = await agente.estornar({ requisicao: t.requisicao, nsu: t.nsu, valor: t.valor, data: (t.criadoEm || '').slice(0, 10) })
      if (r.status !== 'APROVADA') throw new Error(r.mensagem || 'Estorno não aprovado pelo TEF')
    } catch (e) { return setMsg('Estorno não realizado: ' + (String(e.message || e) || 'verifique o agente TEF')) }
    try { await post(`/api/vendas/tef/${t.id}/estornar`, r); imprimirComprovantes(r); setMsg(`Estorno aprovado (NSU ${r.nsu}) ✔`); recarregar() }
    catch (e) { setMsg(`ATENÇÃO: o estorno foi APROVADO no TEF (NSU ${r.nsu}) mas não foi registrado no sistema: ${String(e.message || e)}. Anote o NSU e avise o gerente.`) }
  }

  return <Pagina titulo="📟 Transações TEF (cartões)"><Aviso m={msg} />
    <div className="barra">
      <b>Agente TEF:</b>
      {!tefAtivo() ? <span>desligado (Configurações → Balança e periféricos → TEF)</span>
        : !st ? <span>consultando…</span>
        : st.ok ? <span>✔ provedor <b>{st.provedor}</b> · pinpad {st.pinpad ? 'conectado' : <b style={{ color: 'var(--vermelho)' }}>desconectado</b>} · {st.pendencias} pendência(s) no agente</span>
        : <span style={{ color: 'var(--vermelho)' }}>✖ {st.erro}</span>}
      {st?.ok && st.provedor === 'simulador' && <span className="classe" style={{ background: '#e0a100' }}>SIMULADOR — não é cobrança real</span>}
      <button className="btn sec" onClick={testar}>Atualizar</button>
      <button className="btn" disabled={ocupado || !tefAtivo()} onClick={resolver}>Resolver pendências{pend.length ? ` (${pend.length})` : ''}</button>
    </div>
    {pend.length > 0 && <div className="msg" style={{ margin: '0 0 12px' }}>⚠ {pend.length} transação(ões) aprovada(s) ou sem retorno: com venda gravada serão <b>confirmadas</b>; sem venda serão <b>desfeitas</b>.</div>}
    <table className="tab"><thead><tr><th>Data</th><th>Requisição</th><th>Tipo</th><th>Valor</th><th>Bandeira</th><th>NSU / aut.</th><th>Venda</th><th>Status</th><th></th></tr></thead>
      <tbody>{lista.map(t => <tr key={t.id}>
        <td>{new Date(t.criadoEm).toLocaleString('pt-BR')}</td><td style={{ fontSize: 12 }}>{t.requisicao}</td>
        <td>{TIPO[t.tipo] || t.tipo}{t.parcelas > 1 ? ` ${t.parcelas}x` : ''}</td><td>{brl(t.valor)}</td><td>{t.bandeira || '—'}</td>
        <td>{t.nsu ? `${t.nsu} / ${t.autorizacao || '—'}` : '—'}</td><td>{t.vendaId ? `#${t.vendaId}` : '—'}</td>
        <td><span className="classe" style={{ background: COR[t.status] || '#666' }} title={t.mensagem || ''}>{t.status}</span></td>
        <td>{t.comprovanteCliente && <button className="btn sec" onClick={() => imprimirComprovantes(t)}>Comprovante</button>}{' '}
          {t.status === 'CONFIRMADA' && <button className="btn sec" onClick={() => estornar(t)}>Estornar</button>}</td></tr>)}</tbody></table>
    {!lista.length && <p>Nenhuma transação ainda.</p>}
  </Pagina>
}
