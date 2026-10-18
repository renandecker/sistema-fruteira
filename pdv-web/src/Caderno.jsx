import React, { useEffect, useState } from 'react'
import { api, brl, Pagina, Aviso } from './lib.jsx'
import { mascaraCpf } from './cpf.js'
import { situacaoCaderno, receberCaderno, perdoarCaderno, cancelarLancamento, nivelAlerta, DIAS_ATRASO } from './caderno.js'

const dt = d => d ? new Date(d).toLocaleDateString('pt-BR') : '—'
const ROT = { DIVIDA: 'Fiado', PAGAMENTO: 'Pagamento', PERDAO: 'Perdão' }

/** Receber: o cliente paga (parte da) dívida. Em dinheiro, o valor entra no caixa do turno como suprimento (a gaveta confere certo). */
export function ReceberModal({ sit, onFechar, onFeito }) {
  const [valor, setValor] = useState(sit.saldo); const [forma, setForma] = useState('DINHEIRO'); const [msg, setMsg] = useState(''); const [env, setEnv] = useState(false)
  const v = +valor || 0
  const ok = v > 0 && v <= sit.saldo + 0.0001
  const confirmar = async () => {
    if (!ok || env) return
    setEnv(true); setMsg('')
    try {
      const nova = await receberCaderno(sit.cpf, v, `Recebido em ${forma.toLowerCase()}`)
      let aviso = ''
      if (forma === 'DINHEIRO') {
        try { await api('/api/vendas/caixa/suprimento', { method: 'POST', body: JSON.stringify({ valor: v, motivo: `Recebimento do caderno — ${sit.nome}` }) }) }
        catch { aviso = 'Recebido, mas não foi possível lançar o dinheiro no caixa (abra o turno e faça um suprimento manual).' }
      }
      onFeito(nova, aviso)
    } catch (e) { setMsg(String(e)); setEnv(false) }
  }
  return <div className="modal" role="dialog" aria-label="Receber do caderno"><div className="pag">
    <h2>Receber de {sit.nome}</h2>
    <p>Deve <b>{brl(sit.saldo)}</b>{sit.diasAtraso > 0 && <> · em aberto há <b>{sit.diasAtraso}</b> dias</>}</p>
    <div className="meios">{[['DINHEIRO', 'Dinheiro'], ['PIX', 'PIX'], ['CARTAO', 'Cartão']].map(([id, r]) => <button key={id} className={forma === id ? 'on' : ''} onClick={() => setForma(id)}>{r}</button>)}</div>
    <label>Valor recebido: <input autoFocus type="number" step="0.01" value={valor} onChange={e => setValor(e.target.value)} onKeyDown={e => e.key === 'Enter' && confirmar()} style={{ fontSize: 22, width: 150 }} aria-label="Valor recebido" /></label>
    {v > sit.saldo && <div style={{ color: 'var(--vermelho)' }}>Passa do que o cliente deve ({brl(sit.saldo)}).</div>}
    {forma !== 'DINHEIRO' && <small style={{ display: 'block' }}>Cobre o valor na maquininha ou no Pix da loja; aqui só fica o registro do pagamento.</small>}
    {msg && <div className="msg" role="alert">{msg}</div>}
    <div style={{ marginTop: 10 }}><button className="btn" disabled={!ok || env} onClick={confirmar}>Registrar recebimento</button>{' '}<button className="btn sec" onClick={onFechar}>Fechar</button></div>
  </div></div>
}

/** Painel do cliente no PDV: situação, últimos lançamentos e "Receber". */
export function CadernoPainel({ sit, onFechar, onAtualizar }) {
  const [receber, setReceber] = useState(false); const [msg, setMsg] = useState('')
  return <div className="modal" role="dialog" aria-label="Caderno do cliente"><div className="pag" style={{ minWidth: 480 }}>
    <h2>📒 Caderno — {sit.nome}</h2>
    <div className={`caderno-saldo ${nivelAlerta(sit)}`}>
      <div>Deve <b style={{ fontSize: 26 }}>{brl(sit.saldo)}</b></div>
      <small>{sit.saldo > 0 ? `desde ${dt(sit.desde)} (${sit.diasAtraso} dias) · ` : ''}limite {brl(sit.limite)} · disponível {brl(sit.disponivel)}</small>
    </div>
    <Aviso m={msg} />
    <table className="tab"><thead><tr><th>Data</th><th>Tipo</th><th>Valor</th><th>Obs.</th></tr></thead>
      <tbody>{(sit.lancamentos || []).map(l => <tr key={l.id} style={l.cancelado ? { textDecoration: 'line-through', opacity: .5 } : null}>
        <td>{dt(l.data)}</td><td>{ROT[l.tipo] || l.tipo}</td><td style={{ color: l.tipo === 'DIVIDA' ? 'var(--vermelho)' : 'var(--verde-escuro)' }}>{l.tipo === 'DIVIDA' ? '+' : '−'} {brl(l.valor)}</td><td>{l.obs || ''}</td></tr>)}
        {!(sit.lancamentos || []).length && <tr><td colSpan={4}>Sem lançamentos.</td></tr>}</tbody></table>
    <div style={{ marginTop: 10 }}>{sit.saldo > 0 && <button className="btn" onClick={() => setReceber(true)}>💵 Receber</button>}{' '}<button className="btn sec" onClick={onFechar}>Fechar</button></div>
    {receber && <ReceberModal sit={sit} onFechar={() => setReceber(false)} onFeito={(n, aviso) => { setReceber(false); setMsg(aviso || 'Recebimento registrado ✔'); onAtualizar() }} />}
  </div></div>
}

/** Tela Caderno: quem está devendo, com busca, receber / perdoar (gerente) / ver lançamentos. */
export default function Caderno({ perfil }) {
  const [d, setD] = useState(null); const [busca, setBusca] = useState(''); const [sel, setSel] = useState(null); const [msg, setMsg] = useState('')
  const gerente = perfil === 'gerente'
  const carregar = () => api('/api/retaguarda/caderno/devedores').then(setD).catch(e => setMsg(String(e)))
  useEffect(() => { carregar() }, [])
  const abrir = cpf => situacaoCaderno(cpf).then(setSel).catch(e => setMsg(String(e)))
  const perdoar = async l => {
    const motivo = window.prompt(`Perdoar a dívida de ${l.nome} (${brl(l.saldo)})? Informe o motivo:`)
    if (!motivo) return
    try { await perdoarCaderno(l.cpf, l.saldo, motivo); setMsg('Dívida perdoada (registrado na auditoria).'); carregar() } catch (e) { setMsg(String(e)) }
  }
  const lista = (d?.lista || []).filter(l => !busca || (l.nome || '').toLowerCase().includes(busca.toLowerCase()) || (l.cpf || '').includes(busca.replace(/\D/g, '') || '§'))
  return <Pagina titulo="📒 Caderno — quem está devendo">
    <Aviso m={msg} />
    {d && <div className="kpis">
      <div className="kpi"><small>Total a receber</small><b>{brl(d.totalAReceber)}</b></div>
      <div className="kpi"><small>Clientes devendo</small><b>{d.devedores}</b></div>
      <div className="kpi"><small>Em atraso (≥ {DIAS_ATRASO} dias)</small><b style={{ color: d.atrasados30 ? 'var(--vermelho)' : undefined }}>{d.atrasados30}</b></div>
      <div className="kpi"><small>Maior devedor</small><b>{d.maiorDevedor ? `${d.maiorDevedor.nome} · ${brl(d.maiorDevedor.saldo)}` : '—'}</b></div>
    </div>}
    <input placeholder="Buscar por nome ou CPF" aria-label="Buscar" value={busca} onChange={e => setBusca(e.target.value)} style={{ margin: '10px 0' }} />
    <table className="tab"><thead><tr><th>Cliente</th><th>CPF</th><th>Telefone</th><th>Deve</th><th>Desde</th><th>Dias</th><th></th></tr></thead>
      <tbody>{lista.map(l => <tr key={l.cpf} className={nivelAlerta(l) === 'atraso' ? 'linha-atraso' : ''}>
        <td>{l.nome}</td><td>{mascaraCpf(l.cpf)}</td><td>{l.telefone || ''}</td><td><b>{brl(l.saldo)}</b></td><td>{dt(l.desde)}</td><td>{l.diasAtraso}</td>
        <td><button className="btn sec" onClick={() => abrir(l.cpf)}>Ver / receber</button>{gerente && <> <button className="btn sec" onClick={() => perdoar(l)}>Perdoar</button></>}</td></tr>)}
        {d && !lista.length && <tr><td colSpan={7}>Ninguém devendo 🎉</td></tr>}</tbody></table>
    <small>Para anotar uma dívida: no PDV informe o CPF do cliente cadastrado e escolha <b>Caderno</b> na forma de pagamento. O limite de cada cliente fica na tela Clientes.</small>
    {sel && <CadernoPainel sit={sel} onFechar={() => setSel(null)} onAtualizar={() => { carregar(); situacaoCaderno(sel.cpf).then(setSel) }} />}
  </Pagina>
}
