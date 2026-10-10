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

const fmtNcm = n => n && n.length === 8 ? `${n.slice(0, 4)}.${n.slice(4, 6)}.${n.slice(6)}` : (n || '—')
const NCM_ST = { VALIDO: ['✔', 'var(--verde)', 'NCM válido e vigente'], VENCIDO: ['⚠', '#e0a100', 'Vigência do NCM encerrada'], INVALIDO: ['✖', 'var(--vermelho)', 'NCM inexistente na tabela oficial'], AUSENTE: ['—', '#999', 'Produto sem NCM'] }
const ncmBadge = p => { const m = NCM_ST[p.ncmStatus] || ['?', '#999', 'NCM ainda não verificado']
  return <span title={`${m[2]}${p.ncmDescricao ? ': ' + p.ncmDescricao : ''}${p.ncmVerificadoEm ? ' · verificado em ' + new Date(p.ncmVerificadoEm).toLocaleString('pt-BR') : ''}`} style={{ color: m[1], fontWeight: 700, fontSize: 18 }}>{m[0]}</span> }

export function Produtos() {
  const [lista, recarregar] = useProdutos(true); const [categorias] = useLista('/api/catalogo/categorias'); const [msg, setMsg] = useState('')
  const [cat, setCat] = useState(''); const [margem, setMargem] = useState(40); const [seletor, setSeletor] = useState(null); const [ncmSt, setNcmSt] = useState({ rodando: false, ultima: null })
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
  // verificação de NCM (rotina diária às 03:00; aqui o botão roda a mesma rotina agora)
  useEffect(() => { api('/api/catalogo/ncm/status').then(setNcmSt).catch(() => {}) }, [])
  useEffect(() => {
    if (!ncmSt.rodando) return
    const t = setInterval(() => api('/api/catalogo/ncm/status').then(s => { setNcmSt(s); if (!s.rodando) { recarregar(); setMsg('Verificação de NCM concluída') } }).catch(() => {}), 2000)
    return () => clearInterval(t)
  }, [ncmSt.rodando])
  const verificarNcm = () => post('/api/catalogo/ncm/verificar-todos')
    .then(r => { setMsg(r.iniciou ? 'Verificação de NCM iniciada…' : 'Já existe uma verificação em andamento'); setNcmSt(s => ({ ...s, rodando: true })) }).catch(erro)
  const u = ncmSt.ultima
  const ajustar = () => post(`/api/catalogo/produtos/categoria/${cat}/margem?margemPct=${margem}`)
    .then(n => { setMsg(`Preços recalculados (${n} produtos)`); recarregar() }).catch(erro)
  const desativar = p => window.confirm(`Desativar "${p.nome}"?\nEle some do PDV e das vendas, mas o histórico e a auditoria são mantidos (o PLU continua reservado). Somente o gerente vê e pode reativar.`)
    && api(`/api/catalogo/produtos/${p.id}`, { method: 'DELETE' }).then(() => { setMsg(`"${p.nome}" desativado`); recarregar() }).catch(erro)
  const reativar = p => post(`/api/catalogo/produtos/${p.id}/reativar`).then(() => { setMsg(`"${p.nome}" reativado`); recarregar() }).catch(erro)
  const importar = () => window.confirm('Cadastrar as frutas, legumes, verduras e temperos mais comuns (69 itens)?\nO que já existe (mesmo nome) é mantido. Os preços são só referência: revise depois.')
    && post('/api/catalogo/produtos/pre-cadastro').then(r => { setMsg(`Pré-cadastro concluído: ${r.produtosNovos} produto(s) novo(s) e ${r.categoriasNovas} categoria(s) nova(s); ${r.produtosJaExistiam} já existiam. Revise os preços!`); recarregar() }).catch(erro)
  return <Pagina titulo="🍎 Produtos e preços">
    <Aviso m={msg} />
    <div className="barra"><button className="btn sec" onClick={importar}>📥 Importar frutas e verduras mais comuns (69 itens)</button>
      <small>Cadastra categorias, produtos, perda média, NCM sugerido e imagem — o mesmo conteúdo do script SQL. Pode repetir sem duplicar.</small></div>
    <NovoProduto onSave={recarregar} usados={usados} categorias={categorias} />
    <div className="barra"><b>Margem por categoria/safra:</b>
      <select value={cat} onChange={e => setCat(e.target.value)}><option value="">Categoria…</option>{cats.map(c => <option key={c}>{c}</option>)}</select>
      <input type="number" value={margem} onChange={e => setMargem(e.target.value)} style={{ width: 70 }} /> %
      <button className="btn" disabled={!cat} onClick={ajustar}>Recalcular preços</button>
      <small>preço = custo médio ÷ (1 − perda) × (1 + margem). Promoções ficam na tela <b>Promoções</b>.</small></div>
    <div className="barra"><b>NCM:</b>
      <span>{u?.id ? `última verificação ${new Date(u.fim || u.inicio).toLocaleString('pt-BR')} (${u.origem === 'AGENDADA' ? 'automática diária' : u.origem === 'MANUAL' ? 'manual' : 'ao iniciar'}): ${u.validos} válidos · ${u.invalidos} inválidos · ${u.vencidos} vencidos · ${u.ausentes} sem NCM · ${u.naoVerificados} não verificados${u.normalizados ? ` · ${u.normalizados} códigos normalizados` : ''}` : 'ainda não verificado'}</span>
      <button className="btn sec" disabled={ncmSt.rodando} onClick={verificarNcm}>{ncmSt.rodando ? 'Verificando…' : '🔎 Verificar NCM de todos agora'}</button>
      <small>Rotina automática todo dia às 03:00 (BrasilAPI + tabela oficial do Siscomex). A NFC-e e o pagamento com cartão exigem NCM válido.</small></div>
    {semImagem.length > 0 && <div className="barra"><span>🖼️ {semImagem.length} produto(s) sem imagem escolhida têm imagem sugerida pelo nome.</span>
      <button className="btn sec" onClick={mapear}>Mapear imagens do catálogo pelo nome</button></div>}
    {nDesativados > 0 && <p><small>{nDesativados} produto(s) desativado(s) — visíveis somente para o gerente.</small></p>}
    <table className="tab"><thead><tr><th>Imagem</th><th>PLU</th><th>Atalho (Ctrl+)</th><th>Produto</th><th>Un.</th><th>Categoria</th><th>NCM</th><th>Custo médio</th><th>Perda %</th><th>Preço</th><th>Ações</th></tr></thead>
      <tbody>{lista.map(p => <tr key={p.id} className={p.ativo === false ? 'inativo' : ''}>
        <td>{p.ativo === false ? <ImagemProduto p={p} size={34} /> : <button className="tile-mini" title="Trocar imagem (catálogo)" onClick={() => setSeletor(p)}><ImagemProduto p={p} size={34} /></button>}</td>
        <td>{p.plu}</td>
        <td>{p.ativo === false ? (p.atalho ? `Ctrl+${p.atalho}` : '—') : <select value={p.atalho || ''} onChange={e => atualizar(p, { atalho: e.target.value || null }, e.target.value ? `Atalho Ctrl+${e.target.value} definido para "${p.nome}"` : `Atalho removido de "${p.nome}"`)}>
          <option value="">—</option>{TECLAS.map(t => { const emUso = usados.includes(t) && p.atalho !== t; return <option key={t} value={t} disabled={emUso}>{t}{emUso ? ' (em uso)' : ''}</option> })}</select>}</td>
        <td>{p.nome} {p.ativo === false && <span className="classe off">Desativado</span>}</td><td>{p.unidade}</td>
        <td>{p.ativo === false ? p.categoria : <select value={p.categoria || ''} onChange={e => atualizar(p, { categoria: e.target.value || null }, `Categoria de "${p.nome}" alterada`)}>
          <option value="">Sem categoria</option>{p.categoria && !categorias.some(c => c.nome === p.categoria) && <option value={p.categoria}>{p.categoria} (desativada)</option>}
          {categorias.map(c => <option key={c.id} value={c.nome}>{c.nome}</option>)}</select>}</td>
        <td>{p.ativo === false ? fmtNcm(p.ncm) : <span style={{ whiteSpace: 'nowrap' }}><input key={`${p.id}-${p.ncm}`} defaultValue={p.ncm || ''} maxLength={12} style={{ width: 92 }} placeholder="sem NCM"
          title={`${fmtNcm(p.ncm)} — altere e saia do campo para salvar (é conferido nas fontes oficiais)`}
          onBlur={e => { const v = e.target.value; if (v.replace(/\D/g, '') !== (p.ncm || '')) atualizar(p, { ncm: v }, `NCM de "${p.nome}" atualizado e conferido`) }} /> {ncmBadge(p)}</span>}</td>
        <td>{brl(p.custoMedio)}</td><td>{p.taxaPerdaPct}</td><td>{brl(p.precoVarejo)}</td>
        <td>{p.ativo === false ? <button className="btn sec" onClick={() => reativar(p)}>Reativar</button> : <button className="btn sec" onClick={() => desativar(p)}>Desativar</button>}</td></tr>)}</tbody></table>
    {seletor && <SeletorImagem atual={seletor.imagem} onFechar={() => setSeletor(null)} onEscolher={v => { atualizar(seletor, v, `Imagem de "${seletor.nome}" atualizada`); setSeletor(null) }} />}
  </Pagina>
}

export function Entrada() {
  const [lista] = useProdutos(); const [fornecedores] = useLista('/api/retaguarda/fornecedores'); const [hist, recHist] = useLista('/api/estoque/estoque/entradas?limit=30')
  const [f, setF] = useState({ produtoId: '', fornecedorId: '', qtdEmbalagem: 1, fatorConversao: 20, custoTotal: 0, documento: '', cotacao: true })
  const [res, setRes] = useState(null); const [msg, setMsg] = useState('')
  const set = k => e => setF({ ...f, [k]: e.target.value })
  const qtd = f.qtdEmbalagem * f.fatorConversao
  const forn = fornecedores.find(x => String(x.id) === String(f.fornecedorId)); const prod = lista.find(p => String(p.id) === String(f.produtoId))
  const nomeProd = id => lista.find(p => p.id === id)?.nome ?? `#${id}`
  const enviar = () => post('/api/estoque/estoque/entrada', { produtoId: +f.produtoId, qtdEmbalagem: +f.qtdEmbalagem, fatorConversao: +f.fatorConversao, custoTotal: +f.custoTotal,
      documento: f.documento, fornecedorId: forn ? forn.id : null, fornecedor: forn ? forn.nome : null })
    .then(async r => {
      setRes(r); setMsg('')
      // com fornecedor, grava também a cotação (histórico de preços pago por fornecedor)
      if (forn && f.cotacao && prod && qtd > 0) await post('/api/retaguarda/cotacoes', { produtoNome: prod.nome, fornecedor: forn.nome, preco: +(f.custoTotal / qtd).toFixed(4) }).catch(() => {})
      recHist()
    }).catch(e => setMsg(String(e) || 'Não foi possível registrar a entrada'))
  return <Pagina titulo="📦 Entrada de mercadoria">
    <Aviso m={msg} />
    <div className="form">
      <label>Fornecedor <small>({fornecedores.length} cadastrado(s) — cadastre em Cadastros → Fornecedores)</small>
        <select value={f.fornecedorId} onChange={set('fornecedorId')}><option value="">Não informado</option>
          {fornecedores.map(x => <option key={x.id} value={x.id}>{x.nome}{x.tipo ? ` · ${x.tipo.replace('_', ' ').toLowerCase()}` : ''}</option>)}</select></label>
      <label>Produto<SelProduto lista={lista} v={f.produtoId} onChange={v => setF({ ...f, produtoId: v })} /></label>
      <label>Qtd. de embalagens (caixas/fardos)<input type="number" value={f.qtdEmbalagem} onChange={set('qtdEmbalagem')} /></label>
      <label>Fator de conversão (kg ou un. por embalagem)<input type="number" value={f.fatorConversao} onChange={set('fatorConversao')} /></label>
      <label>Custo total da compra (R$)<input type="number" value={f.custoTotal} onChange={set('custoTotal')} /></label>
      <label>Documento / NF<input value={f.documento} onChange={set('documento')} /></label>
      {forn && <label style={{ flexDirection: 'row', alignItems: 'center', gap: 8 }}><input type="checkbox" checked={f.cotacao} onChange={e => setF({ ...f, cotacao: e.target.checked })} /> Registrar também a cotação (histórico de preços do fornecedor)</label>}
      <p>Entrará no estoque: <b>{qtd || 0}</b> · custo unitário: <b>{qtd ? brl(f.custoTotal / qtd) : '—'}</b></p>
      <button className="btn" disabled={!f.produtoId || !qtd} onClick={enviar}>Registrar entrada</button></div>
    {res && <div className="msg">Entrada registrada{forn ? ` (${forn.nome})` : ''}. Saldo: <b>{res.quantidade}</b> · custo médio: <b>{brl(res.custoMedio)}</b></div>}
    <h3>Últimas entradas</h3>
    <table className="tab"><thead><tr><th>Data</th><th>Produto</th><th>Fornecedor</th><th>Documento</th><th>Quantidade</th><th>Custo unit.</th></tr></thead>
      <tbody>{hist.map(h => <tr key={h.id}><td>{new Date(h.data).toLocaleString('pt-BR')}</td><td>{nomeProd(h.produtoId)}</td><td>{h.fornecedor || '—'}</td><td>{h.documento || '—'}</td><td>{h.quantidade}</td><td>{brl(h.custoUnitario)}</td></tr>)}</tbody></table>
    {!hist.length && <p><small>Nenhuma entrada registrada ainda.</small></p>}
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
