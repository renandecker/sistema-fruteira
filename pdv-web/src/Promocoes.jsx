import React, { useState } from 'react'
import { api, post, brl, useProdutos, useLista, Pagina, Aviso } from './lib.jsx'

const R = '/api/catalogo/promocoes'
const iso = d => { const x = new Date(d); x.setMinutes(x.getMinutes() - x.getTimezoneOffset()); return x.toISOString().slice(0, 10) }
const somar = (s, n) => { const d = new Date(s + 'T12:00:00'); d.setDate(d.getDate() + n); return iso(d) }
const dataBR = s => s ? new Date(s + 'T12:00:00').toLocaleDateString('pt-BR') : '—'
const SIT = { EM_VIGOR: ['Em vigor', 'var(--verde)'], AGENDADA: ['Agendada', '#1976d2'], ENCERRADA: ['Encerrada', '#757575'], DESATIVADA: ['Desativada', '#8d6e63'] }
const desc = x => x.tipo === 'PERCENTUAL' ? `${Number(x.valor)}% off` : `${brl(x.valor)} (preço fixo)`

export default function Promocoes() {
  const [prods, recProds] = useProdutos(); const [cats] = useLista('/api/catalogo/categorias'); const [promos, recPromos] = useLista(R + '?inativos=true')
  const [sel, setSel] = useState([]); const [busca, setBusca] = useState(''); const [cat, setCat] = useState('')
  const [f, setF] = useState({ tipo: 'PERCENTUAL', valor: '', inicio: iso(new Date()), fim: '' }); const [msg, setMsg] = useState(''); const [edit, setEdit] = useState(null)
  const erro = e => setMsg(String(e) || 'Operação não permitida para o seu perfil')
  const recarregar = () => { recPromos(); recProds() }

  const filtrados = prods.filter(p => (!cat || (p.categoria || '').toLowerCase() === cat.toLowerCase())
    && (!busca || p.nome.toLowerCase().includes(busca.toLowerCase()) || String(p.plu) === busca))
  const alterna = id => setSel(s => s.includes(id) ? s.filter(x => x !== id) : [...s, id])
  const marcarTodos = () => setSel(s => [...new Set([...s, ...filtrados.map(p => p.id)])])
  const novoPreco = p => { const v = +f.valor; return !v ? null : f.tipo === 'PERCENTUAL' ? +(+p.precoVarejo * (1 - v / 100)).toFixed(2) : v }
  const valido = sel.length > 0 && +f.valor > 0 && f.inicio && f.fim && f.fim >= f.inicio
  const prazo = n => setF({ ...f, fim: somar(f.inicio, n - 1) })

  const criar = () => post(R, { produtoIds: sel, tipo: f.tipo, valor: +f.valor, inicio: f.inicio, fim: f.fim })
    .then(r => { setMsg(`Promoção criada para ${r.length} produto(s) ✔ Já aparece no PDV nas datas definidas.`); setSel([]); setF({ ...f, valor: '', fim: '' }); recarregar() }).catch(erro)
  const salvar = () => api(`${R}/${edit.id}`, { method: 'PUT', body: JSON.stringify({ tipo: edit.tipo, valor: +edit.valor, inicio: edit.inicio, fim: edit.fim }) })
    .then(() => { setEdit(null); setMsg('Promoção atualizada ✔'); recarregar() }).catch(erro)
  const encerrar = x => window.confirm(`Desativar a promoção de "${x.produtoNome}"?\nO produto volta ao preço normal. O registro fica no histórico e na auditoria (somente o gerente o vê e pode reativar).`)
    && api(`${R}/${x.id}`, { method: 'DELETE' }).then(recarregar).catch(erro)
  const reativar = x => post(`${R}/${x.id}/reativar`).then(recarregar).catch(erro)

  return <Pagina titulo="🔥 Promoções">
    <Aviso m={msg} />
    <h3>Nova promoção</h3>
    <div className="barra" style={{ alignItems: 'flex-end' }}>
      <label>Tipo<select value={f.tipo} onChange={e => setF({ ...f, tipo: e.target.value })}><option value="PERCENTUAL">% de desconto</option><option value="PRECO_FIXO">Preço promocional (R$)</option></select></label>
      <label>{f.tipo === 'PERCENTUAL' ? 'Desconto (%)' : 'Preço promocional (R$)'}<input type="number" step="0.01" value={f.valor} onChange={e => setF({ ...f, valor: e.target.value })} style={{ width: 120 }} /></label>
      <label>Início<input type="date" value={f.inicio} onChange={e => setF({ ...f, inicio: e.target.value })} /></label>
      <label>Fim (prazo)<input type="date" value={f.fim} min={f.inicio} onChange={e => setF({ ...f, fim: e.target.value })} /></label>
      <span>{[['Só hoje', 1], ['3 dias', 3], ['7 dias', 7], ['15 dias', 15]].map(([t, n]) => <button key={n} className="btn sec" onClick={() => prazo(n)} style={{ marginRight: 4 }}>{t}</button>)}</span>
    </div>

    <div className="barra">
      <b>Marque os produtos:</b>
      <input placeholder="Buscar nome ou PLU" value={busca} onChange={e => setBusca(e.target.value)} />
      <select value={cat} onChange={e => setCat(e.target.value)}><option value="">Todas as categorias</option>{cats.map(c => <option key={c.id} value={c.nome}>{c.nome}</option>)}</select>
      <button className="btn sec" onClick={marcarTodos}>Marcar todos da lista</button>
      <button className="btn sec" disabled={!sel.length} onClick={() => setSel([])}>Limpar ({sel.length})</button>
    </div>
    <table className="tab"><thead><tr><th></th><th>PLU</th><th>Produto</th><th>Categoria</th><th>Preço atual</th><th>Preço na promoção</th></tr></thead>
      <tbody>{filtrados.map(p => { const n = novoPreco(p), marcado = sel.includes(p.id)
        return <tr key={p.id} onClick={() => alterna(p.id)} style={{ cursor: 'pointer', background: marcado ? '#fff9c4' : undefined }}>
          <td><input type="checkbox" checked={marcado} onChange={() => alterna(p.id)} onClick={e => e.stopPropagation()} /></td><td>{p.plu}</td>
          <td>{p.nome} {p.precoPromocional != null && <span className="promo-tag">PROMO ATÉ {dataBR(p.promocaoFim).slice(0, 5)}</span>}</td><td>{p.categoria}</td><td>{brl(p.precoVarejo)}/{p.unidade}</td>
          <td>{marcado && n != null ? <b style={{ color: n >= +p.precoVarejo ? 'var(--vermelho)' : 'var(--verde-escuro)' }}>{brl(n)}</b> : '—'}</td></tr> })}</tbody></table>
    <p><button className="btn pagar" disabled={!valido} onClick={criar} style={{ fontSize: 18, padding: '12px 20px' }}>Criar promoção para {sel.length} produto(s)</button>
      {!valido && <small style={{ marginLeft: 10 }}>Marque produtos, informe o valor e o prazo (início e fim).</small>}</p>

    <h3>Promoções cadastradas</h3>
    <table className="tab"><thead><tr><th>Produto</th><th>Desconto</th><th>Preço promo</th><th>Prazo</th><th>Situação</th><th></th></tr></thead>
      <tbody>{promos.map(x => { const emEdicao = edit?.id === x.id; const [rot, cor] = SIT[x.situacao] || [x.situacao, '#666']
        return <tr key={x.id} className={x.situacao === 'DESATIVADA' ? 'inativo' : ''}>
          <td>{x.produtoNome}</td>
          <td>{emEdicao ? <><select value={edit.tipo} onChange={e => setEdit({ ...edit, tipo: e.target.value })}><option value="PERCENTUAL">%</option><option value="PRECO_FIXO">R$</option></select> <input type="number" step="0.01" style={{ width: 90 }} value={edit.valor} onChange={e => setEdit({ ...edit, valor: e.target.value })} /></> : desc(x)}</td>
          <td>{x.precoPromocional != null ? brl(x.precoPromocional) : '—'}</td>
          <td>{emEdicao ? <><input type="date" value={edit.inicio} onChange={e => setEdit({ ...edit, inicio: e.target.value })} /> a <input type="date" value={edit.fim} onChange={e => setEdit({ ...edit, fim: e.target.value })} /></> : `${dataBR(x.inicio)} a ${dataBR(x.fim)}`}</td>
          <td><span className="classe" style={{ background: cor }}>{rot}</span></td>
          <td>{emEdicao ? <><button className="btn" onClick={salvar}>Salvar</button> <button className="btn sec" onClick={() => setEdit(null)}>Cancelar</button></>
            : x.situacao === 'DESATIVADA' ? <button className="btn sec" onClick={() => reativar(x)}>Reativar</button>
            : <><button className="btn sec" onClick={() => setEdit({ id: x.id, tipo: x.tipo, valor: x.valor, inicio: x.inicio, fim: x.fim })}>Editar</button> <button className="btn sec" onClick={() => encerrar(x)}>Desativar</button></>}</td></tr> })}</tbody></table>
    {!promos.length && <p>Nenhuma promoção cadastrada.</p>}
  </Pagina>
}
