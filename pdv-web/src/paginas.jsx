import React, { useEffect, useState } from 'react'
import { NovoProduto, TECLAS } from './paginas2.jsx'
import { useLista } from './lib.jsx'
import { ImagemProduto, SeletorImagem, sugerirImagem } from './catalogoImagens.jsx'

const brl = n => Number(n ?? 0).toLocaleString('pt-BR', { style: 'currency', currency: 'BRL' })
const api = (p, o) => fetch(p, { headers: { 'Content-Type': 'application/json' }, ...o }).then(r => r.ok ? r.json().catch(() => ({})) : r.text().then(t => Promise.reject(t)))
const post = (p, body) => api(p, { method: 'POST', body: body && JSON.stringify(body) })
function useProdutos(inativos = false) {
  const [l, setL] = useState([]); const carregar = () => api('/api/catalogo/produtos' + (inativos ? '?inativos=true' : '')).then(setL).catch(() => {})
  useEffect(() => { carregar() }, []); return [l, carregar]
}
const Pagina = ({ titulo, children }) => <div className="page"><h1>{titulo}</h1>{children}</div>
const Aviso = ({ m }) => m ? <div className="msg" style={{ margin: '0 0 12px' }}>{m}</div> : null
const SelProduto = ({ lista, v, onChange }) => (
  <select value={v} onChange={e => onChange(e.target.value)}><option value="">Produto…</option>
    {lista.map(p => <option key={p.id} value={p.id}>{p.plu} · {p.nome}</option>)}</select>)

export function EmBreve({ item }) {
  const txt = { api: 'Backend pronto — tela em desenvolvimento', soon: 'Em desenvolvimento' }[item.status]
  return <Pagina titulo={`${item.icone} ${item.titulo}`}>
    <div className="vazio"><span className="badge">{txt}</span><p>{item.desc}</p></div></Pagina>
}

export function Produtos() {
  const [lista, recarregar] = useProdutos(true); const [categorias] = useLista('/api/catalogo/categorias'); const [msg, setMsg] = useState('')
  const [cat, setCat] = useState(''); const [margem, setMargem] = useState(40); const [seletor, setSeletor] = useState(null)
  const cats = [...new Set(lista.filter(p => p.ativo !== false).map(p => p.categoria).filter(Boolean))]
  const nDesativados = lista.filter(p => p.ativo === false).length
  const usados = lista.filter(p => p.ativo !== false && p.atalho).map(p => p.atalho)
  const semImagem = lista.filter(p => p.ativo !== false && !p.imagem && !p.fotoUrl && sugerirImagem(p.nome))
  const erro = e => setMsg(String(e) || 'Operação não permitida para o seu perfil')
  const put = (p, campos) => api(`/api/catalogo/produtos/${p.id}`, { method: 'PUT', body: JSON.stringify({ ...p, ...campos }) })
  const atualizar = (p, campos, ok) => put(p, campos).then(() => { setMsg(ok); recarregar() }).catch(erro)
  const mapear = async () => {   // mapeia a imagem do catálogo pelo nome de todos os produtos sem imagem
    let n = 0
    for (const p of semImagem) { try { await put(p, { imagem: sugerirImagem(p.nome).key }); n++ } catch (e) { erro(e) } }
    setMsg(`${n} produto(s) mapeado(s) com a imagem do catálogo`); recarregar()
  }
  const ajustar = () => post(`/api/catalogo/produtos/categoria/${cat}/margem?margemPct=${margem}`)
    .then(n => { setMsg(`Preços recalculados (${n} produtos)`); recarregar() }).catch(erro)
  const desativar = p => window.confirm(`Desativar "${p.nome}"?\nEle some do PDV e das vendas, mas o histórico e a auditoria são mantidos (o PLU continua reservado). Somente o gerente vê e pode reativar.`)
    && api(`/api/catalogo/produtos/${p.id}`, { method: 'DELETE' }).then(() => { setMsg(`"${p.nome}" desativado`); recarregar() }).catch(erro)
  const reativar = p => post(`/api/catalogo/produtos/${p.id}/reativar`).then(() => { setMsg(`"${p.nome}" reativado`); recarregar() }).catch(erro)
  return <Pagina titulo="🍎 Produtos e preços">
    <Aviso m={msg} />
    <NovoProduto onSave={recarregar} usados={usados} categorias={categorias} />
    <div className="barra"><b>Margem por categoria/safra:</b>
      <select value={cat} onChange={e => setCat(e.target.value)}><option value="">Categoria…</option>{cats.map(c => <option key={c}>{c}</option>)}</select>
      <input type="number" value={margem} onChange={e => setMargem(e.target.value)} style={{ width: 70 }} /> %
      <button className="btn" disabled={!cat} onClick={ajustar}>Recalcular preços</button>
      <small>preço = custo médio ÷ (1 − perda) × (1 + margem). Promoções ficam na tela <b>Promoções</b>.</small></div>
    {semImagem.length > 0 && <div className="barra"><span>🖼️ {semImagem.length} produto(s) sem imagem escolhida têm imagem sugerida pelo nome.</span>
      <button className="btn sec" onClick={mapear}>Mapear imagens do catálogo pelo nome</button></div>}
    {nDesativados > 0 && <p><small>{nDesativados} produto(s) desativado(s) — visíveis somente para o gerente.</small></p>}
    <table className="tab"><thead><tr><th>Imagem</th><th>PLU</th><th>Atalho (Ctrl+)</th><th>Produto</th><th>Un.</th><th>Categoria</th><th>Custo médio</th><th>Perda %</th><th>Preço</th><th>Ações</th></tr></thead>
      <tbody>{lista.map(p => <tr key={p.id} className={p.ativo === false ? 'inativo' : ''}>
        <td>{p.ativo === false ? <ImagemProduto p={p} size={34} /> : <button className="tile-mini" title="Trocar imagem (catálogo)" onClick={() => setSeletor(p)}><ImagemProduto p={p} size={34} /></button>}</td>
        <td>{p.plu}</td>
        <td>{p.ativo === false ? (p.atalho ? `Ctrl+${p.atalho}` : '—') : <select value={p.atalho || ''} onChange={e => atualizar(p, { atalho: e.target.value || null }, e.target.value ? `Atalho Ctrl+${e.target.value} definido para "${p.nome}"` : `Atalho removido de "${p.nome}"`)}>
          <option value="">—</option>{TECLAS.map(t => { const emUso = usados.includes(t) && p.atalho !== t; return <option key={t} value={t} disabled={emUso}>{t}{emUso ? ' (em uso)' : ''}</option> })}</select>}</td>
        <td>{p.nome} {p.ativo === false && <span className="classe off">Desativado</span>}</td><td>{p.unidade}</td>
        <td>{p.ativo === false ? p.categoria : <select value={p.categoria || ''} onChange={e => atualizar(p, { categoria: e.target.value || null }, `Categoria de "${p.nome}" alterada`)}>
          <option value="">Sem categoria</option>{p.categoria && !categorias.some(c => c.nome === p.categoria) && <option value={p.categoria}>{p.categoria} (desativada)</option>}
          {categorias.map(c => <option key={c.id} value={c.nome}>{c.nome}</option>)}</select>}</td>
        <td>{brl(p.custoMedio)}</td><td>{p.taxaPerdaPct}</td><td>{brl(p.precoVarejo)}</td>
        <td>{p.ativo === false ? <button className="btn sec" onClick={() => reativar(p)}>Reativar</button> : <button className="btn sec" onClick={() => desativar(p)}>Desativar</button>}</td></tr>)}</tbody></table>
    {seletor && <SeletorImagem atual={seletor.imagem} onFechar={() => setSeletor(null)} onEscolher={v => { atualizar(seletor, v, `Imagem de "${seletor.nome}" atualizada`); setSeletor(null) }} />}
  </Pagina>
}

export function Entrada() {
  const [lista] = useProdutos(); const [f, setF] = useState({ produtoId: '', qtdEmbalagem: 1, fatorConversao: 20, custoTotal: 0, documento: '' })
  const [res, setRes] = useState(null); const [msg, setMsg] = useState('')
  const set = k => e => setF({ ...f, [k]: e.target.value })
  const qtd = f.qtdEmbalagem * f.fatorConversao
  const enviar = () => post('/api/estoque/estoque/entrada', { ...f, produtoId: +f.produtoId, qtdEmbalagem: +f.qtdEmbalagem, fatorConversao: +f.fatorConversao, custoTotal: +f.custoTotal })
    .then(r => { setRes(r); setMsg('') }).catch(e => setMsg(String(e)))
  return <Pagina titulo="📦 Entrada de mercadoria">
    <Aviso m={msg} />
    <div className="form">
      <label>Produto<SelProduto lista={lista} v={f.produtoId} onChange={v => setF({ ...f, produtoId: v })} /></label>
      <label>Qtd. de embalagens (caixas/fardos)<input type="number" value={f.qtdEmbalagem} onChange={set('qtdEmbalagem')} /></label>
      <label>Fator de conversão (kg ou un. por embalagem)<input type="number" value={f.fatorConversao} onChange={set('fatorConversao')} /></label>
      <label>Custo total da compra (R$)<input type="number" value={f.custoTotal} onChange={set('custoTotal')} /></label>
      <label>Documento / NF<input value={f.documento} onChange={set('documento')} /></label>
      <p>Entrará no estoque: <b>{qtd || 0}</b> · custo unitário: <b>{qtd ? brl(f.custoTotal / qtd) : '—'}</b></p>
      <button className="btn" disabled={!f.produtoId || !qtd} onClick={enviar}>Registrar entrada</button></div>
    {res && <div className="msg">Entrada registrada. Saldo: <b>{res.quantidade}</b> · custo médio: <b>{brl(res.custoMedio)}</b></div>}
  </Pagina>
}

function RelatorioPerdas({ lista, refresh }) {
  const [rel, setRel] = useState([]); useEffect(() => { api('/api/estoque/estoque/relatorio/perdas').then(setRel).catch(() => {}) }, [refresh])
  const nome = id => lista.find(p => p.id === id)?.nome ?? `#${id}`
  const total = rel.reduce((s, r) => s + r.valorPerdido, 0)
  return <table className="tab"><thead><tr><th>Produto</th><th>Quantidade perdida</th><th>Valor (custo médio)</th></tr></thead>
    <tbody>{rel.map(r => <tr key={r.produtoId}><td>{nome(r.produtoId)}</td><td>{r.quantidade}</td><td>{brl(r.valorPerdido)}</td></tr>)}
      <tr><td colSpan="2"><b>Total</b></td><td><b>{brl(total)}</b></td></tr></tbody></table>
}
export const RelPerdas = () => { const [l] = useProdutos(); return <Pagina titulo="🗑️ Perdas / descarte"><RelatorioPerdas lista={l} /></Pagina> }

export function Perdas() {
  const [lista] = useProdutos(); const [f, setF] = useState({ produtoId: '', quantidade: '', tipo: 'PERDA', motivo: '' }); const [n, setN] = useState(0); const [msg, setMsg] = useState('')
  const enviar = () => post('/api/estoque/estoque/baixa', { ...f, produtoId: +f.produtoId, quantidade: +f.quantidade })
    .then(() => { setMsg('Baixa registrada ✔'); setN(n + 1); setF({ ...f, quantidade: '', motivo: '' }) }).catch(e => setMsg(String(e)))
  return <Pagina titulo="🥀 Perdas e avarias">
    <Aviso m={msg} />
    <div className="form">
      <label>Produto<SelProduto lista={lista} v={f.produtoId} onChange={v => setF({ ...f, produtoId: v })} /></label>
      <label>Tipo<select value={f.tipo} onChange={e => setF({ ...f, tipo: e.target.value })}><option value="PERDA">Perda (estragou)</option><option value="AVARIA">Avaria (amassou/quebrou)</option></select></label>
      <label>Quantidade<input type="number" value={f.quantidade} onChange={e => setF({ ...f, quantidade: e.target.value })} /></label>
      <label>Motivo<input value={f.motivo} onChange={e => setF({ ...f, motivo: e.target.value })} /></label>
      <button className="btn" disabled={!f.produtoId || !f.quantidade} onClick={enviar}>Registrar baixa</button></div>
    <h3>Acumulado</h3><RelatorioPerdas lista={lista} refresh={n} />
  </Pagina>
}

export function Abc() {
  const [rows, setRows] = useState([]); const [msg, setMsg] = useState('')
  useEffect(() => { api('/api/vendas/vendas/relatorio/abc').then(setRows).catch(() => setMsg('Sem dados ou serviço de vendas indisponível')) }, [])
  const total = rows.reduce((s, r) => s + r[1], 0); let acc = 0
  return <Pagina titulo="🏆 Curva ABC (faturamento)"><Aviso m={msg} />
    <table className="tab"><thead><tr><th>Produto</th><th>Faturamento</th><th>%</th><th>% acum.</th><th>Classe</th></tr></thead>
      <tbody>{rows.map(([nome, v]) => { const ant = acc; acc += v; const pa = acc / total * 100
        return <tr key={nome}><td>{nome}</td><td>{brl(v)}</td><td>{(v / total * 100).toFixed(1)}%</td><td>{pa.toFixed(1)}%</td>
          <td><span className={`classe c${ant / total * 100 < 80 ? 'A' : ant / total * 100 < 95 ? 'B' : 'C'}`}>{ant / total * 100 < 80 ? 'A' : ant / total * 100 < 95 ? 'B' : 'C'}</span></td></tr> })}</tbody></table>
  </Pagina>
}

export function Fechamento() {
  const [v, setV] = useState(''); const [ok, setOk] = useState(false)
  // Fechamento cego: a diferença NÃO é mostrada ao operador
  const enviar = () => post('/api/vendas/vendas/caixa/fechamento', { contadoDinheiro: +v }).then(() => setOk(true))
  return <Pagina titulo="💵 Fechamento de caixa">
    <p>Conte o dinheiro da gaveta e informe o valor. O saldo do sistema não é exibido.</p>
    {ok ? <div className="msg">Contagem registrada ✔ A conferência será feita pelo gerente.</div> :
      <div className="form"><label>Dinheiro contado (R$)<input type="number" value={v} onChange={e => setV(e.target.value)} /></label>
        <button className="btn" disabled={!v} onClick={enviar}>Encerrar turno</button></div>}
  </Pagina>
}
