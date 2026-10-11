import React, { useEffect, useState } from 'react'
import { api, post, brl, Pagina, Aviso } from './lib.jsx'

const hora = d => d ? new Date(d).toLocaleString('pt-BR', { day: '2-digit', month: '2-digit', hour: '2-digit', minute: '2-digit' }) : '—'
function imprimirTexto(txt) {
  const el = document.createElement('div'); el.className = 'area-print comprovante'
  const pre = document.createElement('pre'); pre.textContent = txt; el.appendChild(pre); document.body.appendChild(el)
  try { window.print() } finally { el.remove() }
}

/** Sangria / suprimento (modal). CEGA: o operador informa o valor que CONTOU e nunca vê o saldo do sistema. */
export function SangriaModal({ onFechar, onFeita, tipoInicial = 'sangria' }) {
  const [tipo, setTipo] = useState(tipoInicial); const [valor, setValor] = useState(''); const [motivo, setMotivo] = useState(''); const [erro, setErro] = useState(''); const [env, setEnv] = useState(false)
  const v = +valor
  const ok = v > 0 && !env
  const enviar = async () => {
    if (!ok) return
    setEnv(true); setErro('')
    try {
      const r = await post(`/api/vendas/caixa/${tipo}`, { valor: v, motivo })
      if (tipo === 'sangria' && r.comprovante) imprimirTexto(r.comprovante)
      onFeita?.(r, tipo)
    } catch (e) { setErro(String(e)); setEnv(false) }
  }
  useEffect(() => {
    const h = e => { if (e.key === 'Escape') { e.preventDefault(); onFechar() } else if (e.key === 'Enter') { e.preventDefault(); enviar() } }
    window.addEventListener('keydown', h); return () => window.removeEventListener('keydown', h)
  })
  return <div className="modal" role="dialog" aria-label="Sangria de caixa"><div style={{ minWidth: 380 }}>
    <h2>{tipo === 'sangria' ? '💰 Sangria de caixa' : '➕ Suprimento (reforço de troco)'}</h2>
    <div className="meios"><button className={tipo === 'sangria' ? 'on' : ''} onClick={() => setTipo('sangria')}>Sangria (retirar)</button>
      <button className={tipo === 'suprimento' ? 'on' : ''} onClick={() => setTipo('suprimento')}>Suprimento (colocar)</button></div>
    <p style={{ margin: '8px 0' }}>Conte as notas e moedas {tipo === 'sangria' ? 'que está retirando da gaveta' : 'que está colocando na gaveta'} e informe o valor. O saldo do sistema não é exibido.</p>
    <label style={{ display: 'block' }}>Valor contado (R$) <input autoFocus type="number" step="0.01" min="0" value={valor} onChange={e => setValor(e.target.value)} style={{ fontSize: 24, width: 160 }} /></label>
    <label style={{ display: 'block', marginTop: 8 }}>Motivo (opcional) <input value={motivo} maxLength={200} onChange={e => setMotivo(e.target.value)} placeholder={tipo === 'sangria' ? 'ex.: levar ao cofre' : 'ex.: troco do banco'} style={{ width: '100%' }} /></label>
    {erro && <div className="msg" role="alert" style={{ margin: '8px 0' }}>{erro}</div>}
    <div className="meios" style={{ marginTop: 12 }}><button className="btn" disabled={!ok} onClick={enviar}>Confirmar <kbd>Enter</kbd></button>
      <button className="btn sec" onClick={onFechar}>Cancelar <kbd>Esc</kbd></button></div>
  </div></div>
}

/** Tela do operador: abrir turno (fundo de troco), sangria/suprimento e encerrar com contagem cega. */
export function Caixa() {
  const [t, setT] = useState(null); const [fundo, setFundo] = useState(''); const [v, setV] = useState(''); const [msg, setMsg] = useState(''); const [modal, setModal] = useState(null); const [fechado, setFechado] = useState(false)
  const carregar = () => api('/api/vendas/caixa/turno').then(setT).catch(e => setMsg(String(e)))
  useEffect(() => { carregar() }, [])
  const abrir = () => post('/api/vendas/caixa/abrir', { fundoTroco: +fundo || 0 }).then(() => { setFundo(''); setFechado(false); carregar() }).catch(e => setMsg(String(e)))
  const encerrar = () => {
    if (!window.confirm('Encerrar o turno com o valor contado informado?')) return
    post('/api/vendas/caixa/fechamento', { contadoDinheiro: +v }).then(() => { setFechado(true); setV(''); carregar() }).catch(e => setMsg(String(e)))
  }
  return <Pagina titulo="💵 Caixa: turno, sangria e fechamento">
    <Aviso m={msg} />
    {t === null ? <p>Carregando…</p> : !t.aberto ? <>
      {fechado && <div className="msg">Turno encerrado ✔ A contagem foi registrada; a conferência será feita pelo gerente.</div>}
      <p>Nenhum turno aberto. Informe o <b>fundo de troco</b> (dinheiro que já está na gaveta) para começar.</p>
      <div className="form"><label>Fundo de troco (R$)<input type="number" step="0.01" min="0" value={fundo} onChange={e => setFundo(e.target.value)} /></label>
        <button className="btn" onClick={abrir}>Abrir turno</button></div>
    </> : <>
      <p>Turno aberto desde <b>{hora(t.abertura)}</b>.</p>
      {t.sangriaRecomendada && <div className="msg" role="alert">💰 A gaveta já está com bastante dinheiro: faça uma <b>sangria</b>.</div>}
      <div className="meios"><button className="btn" onClick={() => setModal('sangria')}>💰 Sangria</button>
        <button className="btn sec" onClick={() => setModal('suprimento')}>➕ Suprimento</button></div>
      <h3>Meus lançamentos neste turno</h3>
      {t.movimentos.length === 0 ? <p>Nenhum.</p> : <table className="tab"><thead><tr><th>Hora</th><th>Tipo</th><th>Valor contado</th><th>Motivo</th></tr></thead>
        <tbody>{t.movimentos.map(m => <tr key={m.id}><td>{hora(m.data)}</td><td>{m.tipo === 'SANGRIA' ? 'Sangria' : 'Suprimento'}</td><td>{brl(m.valor)}</td><td>{m.motivo}</td></tr>)}</tbody></table>}
      <h3>Encerrar turno</h3>
      <p>Conte o dinheiro que ficou na gaveta e informe o valor. O saldo do sistema não é exibido.</p>
      <div className="form"><label>Dinheiro contado (R$)<input type="number" step="0.01" min="0" value={v} onChange={e => setV(e.target.value)} /></label>
        <button className="btn" disabled={v === ''} onClick={encerrar}>Encerrar turno</button></div>
    </>}
    {modal && <SangriaModal tipoInicial={modal} onFechar={() => setModal(null)} onFeita={() => { setModal(null); setMsg('Lançamento registrado ✔'); carregar() }} />}
  </Pagina>
}

/** Conferência do gerente: turnos, sangrias e diferença de caixa. */
export function CaixaGerente() {
  const [l, setL] = useState([]); const [sel, setSel] = useState(null); const [movs, setMovs] = useState([]); const [msg, setMsg] = useState('')
  const carregar = () => api('/api/vendas/caixa/turnos').then(setL).catch(e => setMsg(String(e)))
  useEffect(() => { carregar() }, [])
  const abrir = t => { setSel(t.id); api(`/api/vendas/caixa/turnos/${t.id}/movimentos`).then(setMovs).catch(e => setMsg(String(e))) }
  const cor = d => d == null ? undefined : +d === 0 ? 'var(--verde-escuro)' : 'var(--vermelho)'
  return <Pagina titulo="🧾 Conferência de caixa e sangrias">
    <Aviso m={msg} />
    <p>Diferença = contado − esperado (fundo de troco + vendas em dinheiro + suprimentos − sangrias). O operador nunca vê estes valores.</p>
    <button className="btn sec" onClick={carregar}>Atualizar</button>
    <table className="tab"><thead><tr><th>Turno</th><th>Operador</th><th>Abertura</th><th>Fechamento</th><th>Fundo</th><th>Sangrias</th><th>Suprimentos</th><th>Esperado</th><th>Contado</th><th>Diferença</th><th></th></tr></thead>
      <tbody>{l.map(t => <tr key={t.id}><td>#{t.id} {t.status === 'ABERTO' ? <small>(aberto)</small> : ''}</td><td>{t.operador}</td><td>{hora(t.abertura)}</td><td>{hora(t.fechamento)}</td>
        <td>{brl(t.fundoTroco)}</td><td>{brl(t.sangrias)}{t.sangriasAcimaDoSaldo > 0 && <span title="Sangria maior que o saldo do sistema"> ⚠</span>}</td><td>{brl(t.suprimentos)}</td>
        <td>{brl(t.esperado)}</td><td>{t.contado == null ? '—' : brl(t.contado)}</td><td style={{ color: cor(t.diferenca), fontWeight: 700 }}>{t.diferenca == null ? '—' : brl(t.diferenca)}</td>
        <td><button className="btn sec" onClick={() => abrir(t)}>Lançamentos</button></td></tr>)}</tbody></table>
    {sel && <><h3>Lançamentos do turno #{sel}</h3>{movs.length === 0 ? <p>Nenhum.</p> :
      <table className="tab"><thead><tr><th>Hora</th><th>Tipo</th><th>Valor</th><th>Motivo</th><th>Operador</th><th></th></tr></thead>
        <tbody>{movs.map(m => <tr key={m.id}><td>{hora(m.data)}</td><td>{m.tipo === 'SANGRIA' ? 'Sangria' : 'Suprimento'}</td><td>{brl(m.valor)}</td><td>{m.motivo}</td><td>{m.operador}</td>
          <td>{m.acimaDoSaldo && <span style={{ color: 'var(--vermelho)' }}>⚠ acima do saldo do sistema</span>}</td></tr>)}</tbody></table>}</>}
  </Pagina>
}
