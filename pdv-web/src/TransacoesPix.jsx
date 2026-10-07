import React, { useEffect, useState } from 'react'
import { api, post, brl, useLista, Pagina, Aviso } from './lib.jsx'

const COR = { ATIVA: '#1976d2', CONCLUIDA: 'var(--verde)', EXPIRADA: '#757575', CANCELADA: '#8d6e63', DEVOLVIDA: '#7b1fa2', DIVERGENTE: 'var(--vermelho)' }
const R = '/api/vendas/pix'

export default function TransacoesPix() {
  const [lista, recarregar] = useLista(`${R}/cobrancas?limit=100`); const [pend, recPend] = useLista(`${R}/pendencias`)
  const [st, setSt] = useState(null); const [msg, setMsg] = useState('')
  useEffect(() => { api(`${R}/status`).then(setSt).catch(e => setSt({ erro: String(e.message || e) })) }, [])
  const tudo = () => { recarregar(); recPend() }
  const erro = e => setMsg(String(e.message || e) || 'Operação não permitida para o seu perfil')

  const devolver = c => window.confirm(`Devolver ${brl(c.valorPago)} ao pagador (E2E ${c.endToEndId})?${c.vendaId ? `\nA venda #${c.vendaId} deve ser cancelada DEPOIS da devolução.` : ''}`)
    && post(`${R}/cobrancas/${c.id}/devolver`).then(() => { setMsg('Pix devolvido ✔'); tudo() }).catch(erro)
  const cancelar = c => post(`${R}/cobrancas/${c.id}/cancelar`).then(tudo).catch(erro)
  const simular = c => post(`${R}/simulador/cobrancas/${c.id}/pagar`).then(() => setTimeout(tudo, 500)).catch(erro)

  return <Pagina titulo="💠 Transações PIX"><Aviso m={msg} />
    <div className="barra">
      <b>Provedor:</b>
      {!st ? <span>consultando…</span> : st.erro ? <span style={{ color: 'var(--vermelho)' }}>✖ {st.erro}</span>
        : <span>{st.provedor}{st.chave ? ` · chave ${st.chave}` : ''} · webhook {st.webhookConfigurado ? 'configurado' : 'não configurado'}</span>}
      {st?.simulador && <span className="classe" style={{ background: '#e0a100' }}>SIMULADOR — não é Pix real</span>}
      <button className="btn sec" onClick={tudo}>Atualizar</button>
    </div>
    {pend.length > 0 && <div className="msg" style={{ margin: '0 0 12px' }}>⚠ {pend.length} Pix <b>recebido(s) sem venda</b> (o caixa pode ter travado depois do pagamento): devolva ao cliente ou conclua a venda com o mesmo valor.</div>}
    <table className="tab"><thead><tr><th>Data</th><th>Valor</th><th>Status</th><th>E2E</th><th>Venda</th><th>Origem</th><th></th></tr></thead>
      <tbody>{lista.map(c => <tr key={c.id}>
        <td>{new Date(c.criadaEm).toLocaleString('pt-BR')}</td><td>{brl(c.valorPago ?? c.valor)}</td>
        <td><span className="classe" style={{ background: COR[c.status] || '#666' }}>{c.status}</span></td>
        <td style={{ fontSize: 11 }}>{c.endToEndId || '—'}</td><td>{c.vendaId ? `#${c.vendaId}` : '—'}</td><td>{c.provedor}</td>
        <td>{c.status === 'ATIVA' && <button className="btn sec" onClick={() => cancelar(c)}>Cancelar</button>}{' '}
          {c.status === 'ATIVA' && st?.simulador && <button className="btn sec" onClick={() => simular(c)}>Simular pagamento</button>}{' '}
          {(c.status === 'CONCLUIDA' || c.status === 'DIVERGENTE') && <button className="btn sec" onClick={() => devolver(c)}>Devolver</button>}</td></tr>)}</tbody></table>
    {!lista.length && <p>Nenhum Pix ainda.</p>}
  </Pagina>
}
