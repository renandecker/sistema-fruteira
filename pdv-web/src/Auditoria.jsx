import React, { useEffect, useState } from 'react'
import { api, Pagina, Aviso } from './lib.jsx'

const ROTULO = { CADASTRO: 'Cadastro', EDICAO: 'Edição', DESATIVACAO: 'Desativação', REATIVACAO: 'Reativação', BAIXA: 'Baixa de estoque', CANCELAMENTO: 'Cancelamento' }
const COR = { CADASTRO: 'var(--verde)', EDICAO: '#e0a100', DESATIVACAO: 'var(--vermelho)', REATIVACAO: '#1976d2', BAIXA: '#5d4037', CANCELAMENTO: '#7b1fa2' }
const parse = s => { try { return s ? JSON.parse(s) : null } catch { return null } }
const fmt = v => v === null || v === undefined ? '—' : typeof v === 'object' ? JSON.stringify(v) : String(v)

function Detalhes({ a }) {
  const antes = parse(a.antes), depois = parse(a.depois)
  if (!antes && !depois) return <small>Sem detalhes de campos para esta ação.</small>
  if (!antes || !depois) {   // cadastro (só "depois") ou exclusão (só "antes")
    const o = antes || depois
    return <table className="tab mini"><thead><tr><th colSpan="2">{antes ? 'Dados do registro' : 'Dados cadastrados'}</th></tr></thead>
      <tbody>{Object.entries(o).filter(([, v]) => v !== null && v !== '').map(([k, v]) => <tr key={k}><td>{k}</td><td>{fmt(v)}</td></tr>)}</tbody></table>
  }
  const ks = [...new Set([...Object.keys(antes), ...Object.keys(depois)])].filter(k => JSON.stringify(antes[k]) !== JSON.stringify(depois[k]))
  if (!ks.length) return <small>Nenhum campo foi alterado.</small>
  return <table className="tab mini"><thead><tr><th>Campo</th><th>Antes</th><th>Depois</th></tr></thead>
    <tbody>{ks.map(k => <tr key={k}><td>{k}</td><td>{fmt(antes[k])}</td><td><b>{fmt(depois[k])}</b></td></tr>)}</tbody></table>
}

export default function Auditoria() {
  const [f, setF] = useState({ usuario: '', acao: '', entidade: '', de: '', ate: '' })
  const [rows, setRows] = useState([]); const [filtros, setFiltros] = useState({ usuarios: [], entidades: [] })
  const [aberto, setAberto] = useState(null); const [msg, setMsg] = useState(''); const [load, setLoad] = useState(false)
  const set = k => e => setF({ ...f, [k]: e.target.value })

  const buscar = (filtro = f) => {
    setLoad(true)
    const q = new URLSearchParams(); Object.entries(filtro).forEach(([k, v]) => v && q.set(k, v)); q.set('limit', '500')
    api('/api/retaguarda/auditoria?' + q).then(r => { setRows(r); setMsg('') })
      .catch(e => setMsg('Não foi possível carregar a auditoria: ' + e)).finally(() => setLoad(false))
    api('/api/retaguarda/auditoria/filtros').then(setFiltros).catch(() => {})
  }
  useEffect(() => { buscar() }, [])
  const limpar = () => { const v = { usuario: '', acao: '', entidade: '', de: '', ate: '' }; setF(v); buscar(v) }

  const baixarCsv = () => {
    const esc = v => `"${String(v ?? '').replace(/"/g, '""')}"`
    const linhas = [['Data/hora', 'Usuário', 'Perfil', 'Ação', 'Entidade', 'Registro', 'Descrição', 'Origem']].concat(
      rows.map(r => [new Date(r.data).toLocaleString('pt-BR'), r.usuario, r.perfil, ROTULO[r.acao] || r.acao, r.entidade, r.entidadeId, r.descricao, r.origem]))
    const blob = new Blob(['\ufeff' + linhas.map(l => l.map(esc).join(';')).join('\n')], { type: 'text/csv;charset=utf-8' })
    const a = document.createElement('a'); a.href = URL.createObjectURL(blob); a.download = 'auditoria.csv'; a.click()
  }
  const contagem = rows.reduce((c, r) => ({ ...c, [r.acao]: (c[r.acao] || 0) + 1 }), {})

  return <Pagina titulo="🕵️ Auditoria">
    <Aviso m={msg} />
    <div className="barra">
      <label>Usuário<select value={f.usuario} onChange={set('usuario')}><option value="">Todos</option>{filtros.usuarios.map(u => <option key={u}>{u}</option>)}</select></label>
      <label>Ação<select value={f.acao} onChange={set('acao')}><option value="">Todas</option>{Object.entries(ROTULO).map(([k, v]) => <option key={k} value={k}>{v}</option>)}</select></label>
      <label>Entidade<select value={f.entidade} onChange={set('entidade')}><option value="">Todas</option>{filtros.entidades.map(u => <option key={u}>{u}</option>)}</select></label>
      <label>De<input type="date" value={f.de} onChange={set('de')} /></label>
      <label>Até<input type="date" value={f.ate} onChange={set('ate')} /></label>
      <button className="btn" onClick={() => buscar()} disabled={load}>{load ? 'Buscando…' : 'Filtrar'}</button>
      <button className="btn sec" onClick={limpar}>Limpar</button>
      <button className="btn sec" onClick={baixarCsv} disabled={!rows.length}>Exportar CSV</button>
    </div>
    <p>{rows.length} registro(s){rows.length >= 500 ? ' (mostrando os 500 mais recentes — refine os filtros)' : ''}{' '}
      {Object.entries(contagem).map(([k, n]) => <span key={k} className="classe" style={{ background: COR[k] || '#666', marginRight: 6 }}>{ROTULO[k] || k}: {n}</span>)}</p>
    <table className="tab"><thead><tr><th>Data/hora</th><th>Usuário</th><th>Ação</th><th>Entidade</th><th>Registro</th><th>Descrição</th><th></th></tr></thead>
      <tbody>{rows.map(r => <React.Fragment key={r.id}>
        <tr><td>{new Date(r.data).toLocaleString('pt-BR')}</td><td><b>{r.usuario}</b><br /><small>{r.perfil}</small></td>
          <td><span className="classe" style={{ background: COR[r.acao] || '#666' }}>{ROTULO[r.acao] || r.acao}</span></td>
          <td>{r.entidade}</td><td>{r.entidadeId ?? '—'}</td><td>{r.descricao}</td>
          <td>{(r.antes || r.depois) && <button className="btn sec" onClick={() => setAberto(aberto === r.id ? null : r.id)}>{aberto === r.id ? 'Ocultar' : 'Detalhes'}</button>}</td></tr>
        {aberto === r.id && <tr><td colSpan="7" style={{ background: 'var(--verde-claro)' }}><Detalhes a={r} /></td></tr>}
      </React.Fragment>)}</tbody></table>
    {!rows.length && !load && <p>Nenhum registro encontrado.</p>}
  </Pagina>
}
