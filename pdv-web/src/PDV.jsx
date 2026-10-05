import React, { useEffect, useMemo, useRef, useState } from 'react'
import { QRCodeSVG } from 'qrcode.react'
import { useBalanca } from './balanca.js'
import { enfileirar, sincronizar } from './offline.js'
import { ImagemProduto } from './catalogoImagens.jsx'
import { decodificarEtiqueta } from './etiqueta.js'

const brl = n => Number(n).toLocaleString('pt-BR', { style: 'currency', currency: 'BRL' })
const dataCurta = d => d ? new Date(d + 'T12:00:00').toLocaleDateString('pt-BR', { day: '2-digit', month: '2-digit' }) : ''
const api = (p, o) => fetch(p, { headers: { 'Content-Type': 'application/json' }, ...o }).then(r => r.ok ? r.json() : r.text().then(t => Promise.reject(t)))
const posVenda = (v, cpf) => { // NFC-e (contingência se offline) e cashback do cliente
  const ign = () => {}
  fetch('/api/retaguarda/nfce/emitir', { method: 'POST', headers: { 'Content-Type': 'application/json' }, body: JSON.stringify({ vendaId: v.id, valor: v.total, cpf, contingencia: !navigator.onLine }) }).catch(ign)
  if (cpf) fetch(`/api/retaguarda/clientes/cpf/${cpf}/compra?valor=${v.total}`, { method: 'POST' }).catch(ign)
}
const enviarVenda = v => api('/api/vendas/vendas', { method: 'POST', body: JSON.stringify(v) })
let seq = 0

export default function PDV() {
  const [produtos, setProdutos] = useState([]); const [categorias, setCategorias] = useState([]); const [ranking, setRanking] = useState({})
  const [itens, setItens] = useState([]); const [codigo, setCodigo] = useState(''); const [cpf, setCpf] = useState(''); const [pagando, setPagando] = useState(false)
  const [msg, setMsg] = useState(''); const [aba, setAba] = useState(0); const [flash, setFlash] = useState(null)
  const balanca = useBalanca(); const fim = useRef(null); const campo = useRef(null); const bipCtx = useRef(null)

  const carregar = () => {
    api('/api/catalogo/produtos').then(setProdutos).catch(() => setMsg('Catálogo indisponível'))
    api('/api/catalogo/categorias').then(setCategorias).catch(() => {})
    api('/api/vendas/vendas/ranking').then(r => setRanking(Object.fromEntries(r.map(x => [x.produtoId, x])))).catch(() => {})
  }
  useEffect(() => { carregar() }, [])
  useEffect(() => { const on = () => { sincronizar(enviarVenda); carregar() }; window.addEventListener('online', on); return () => window.removeEventListener('online', on) }, [])
  useEffect(() => { if (!flash) return; const t = setTimeout(() => setFlash(null), 1600); return () => clearTimeout(t) }, [flash])
  useEffect(() => { fim.current?.scrollIntoView({ block: 'nearest' }) }, [itens.length])

  // ---- abas por categoria + ordem dos mais comprados ----
  const nomeCat = p => (p.categoria || '').trim().toLowerCase()
  const abas = useMemo(() => {
    const nomes = new Set(categorias.map(c => c.nome.toLowerCase()))
    const l = [{ id: 'todos', nome: 'Todos', filtro: () => true }]
    categorias.forEach(c => { const n = c.nome.toLowerCase(); if (produtos.some(p => nomeCat(p) === n)) l.push({ id: 'c' + c.id, nome: c.nome, filtro: p => nomeCat(p) === n }) })
    if (produtos.some(p => !nomes.has(nomeCat(p)))) l.push({ id: 'outros', nome: 'Outros', filtro: p => !nomes.has(nomeCat(p)) })
    return l
  }, [produtos, categorias])
  const idxAba = Math.min(aba, abas.length - 1)
  const visiveis = useMemo(() => {
    const r = id => ranking[id] || { vendas: 0, quantidade: 0 }
    return produtos.filter(abas[idxAba].filtro).sort((a, b) => r(b.id).vendas - r(a.id).vendas || r(b.id).quantidade - r(a.id).quantidade || a.nome.localeCompare(b.nome))
  }, [produtos, abas, idxAba, ranking])

  const preco = p => Number(p.precoPromocional ?? p.precoVarejo)
  const total = itens.reduce((s, i) => s + i.subtotal, 0)
  const descontoTotal = itens.reduce((s, i) => s + i.desconto, 0)
  const noCarrinho = {}; itens.forEach(i => { noCarrinho[i.produtoId] = (noCarrinho[i.produtoId] || 0) + 1 })

  /** Lança o produto na venda. Retorna true se lançou. `extra.quantidade` vem de etiqueta EAN-13 com valor embutido. */
  function adicionar(p, extra = {}) {
    const porKg = p.unidade === 'KG'
    if (porKg && !extra.quantidade && !(balanca.peso > 0)) { setMsg(`Coloque ${p.nome} na balança`); return false }
    const qtd = extra.quantidade ?? (porKg ? balanca.peso : 1)
    const orig = Number(p.precoVarejo), pr = preco(p), promo = p.precoPromocional != null && pr < orig
    const sub = +(qtd * pr).toFixed(2), bruto = +(qtd * orig).toFixed(2)
    const uid = ++seq
    setItens(l => [...l, { uid, produtoId: p.id, nome: p.nome, quantidade: qtd, preco: pr, precoOriginal: orig, promo, desconto: promo ? +(bruto - sub).toFixed(2) : 0, subtotal: sub, pesoBalanca: porKg }])
    setFlash({ id: p.id, n: uid }); setMsg('')
    return true
  }

  // ---- remover itens (registrado na auditoria: cancelamentos no caixa são controle de perdas) ----
  const auditar = (descricao, antes) => fetch('/api/retaguarda/auditoria', { method: 'POST', headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ acao: 'CANCELAMENTO', entidade: 'Item do caixa', entidadeId: null, descricao, antes: antes ? JSON.stringify(antes) : null, depois: null, usuario: null, perfil: null, origem: 'pdv-web' }) }).catch(() => {})
  const removerItem = i => {
    setItens(l => l.filter(x => x.uid !== i.uid))
    auditar(`Item removido da venda em andamento: ${i.nome} (${i.quantidade.toFixed(3)} × ${brl(i.preco)} = ${brl(i.subtotal)})`, { nome: i.nome, quantidade: i.quantidade, subtotal: i.subtotal })
    setMsg(`"${i.nome}" removido`); focarCampo()
  }
  const limparTudo = () => {
    if (!itens.length || !window.confirm(`Remover todos os ${itens.length} itens da venda?`)) return
    auditar(`Venda em andamento limpa: ${itens.length} itens, total ${brl(itens.reduce((s, i) => s + i.subtotal, 0))}`, { itens: itens.map(i => `${i.nome} ${i.quantidade.toFixed(3)} = ${brl(i.subtotal)}`) })
    setItens([]); setMsg('Venda limpa'); focarCampo()
  }

  // ---- leitor de código de barras ----
  const bip = ok => {
    try {
      if (!bipCtx.current) bipCtx.current = new (window.AudioContext || window.webkitAudioContext)()
      const c = bipCtx.current, o = c.createOscillator(), g = c.createGain()
      o.frequency.value = ok ? 1100 : 220; g.gain.value = 0.06; o.connect(g); g.connect(c.destination); o.start(); o.stop(c.currentTime + (ok ? 0.08 : 0.25))
    } catch { /* sem áudio */ }
  }
  /** Reconhece: 1) código de barras cadastrado; 2) etiqueta EAN-13 de peso/preço variável (2+PLU+valor+DV, gerada em Etiquetas); 3) PLU (só no Enter) */
  const identificar = (txt, aceitaPlu) => {
    const t = txt.trim(); if (!t) return null
    const direto = produtos.find(p => p.codigoBarras && p.codigoBarras.trim() === t)
    if (direto) return { p: direto }
    const dec = decodificarEtiqueta(t)            // etiqueta de balança computadora (ex.: Toledo Prix) ou gerada em Etiquetas
    if (dec) {
      const p = produtos.find(x => x.plu === dec.plu)
      if (p) { const q = dec.peso != null ? dec.peso : dec.valor / preco(p); return { p, quantidade: p.unidade === 'KG' ? +q.toFixed(3) : Math.max(1, Math.round(q)) } }
    }
    if (aceitaPlu && /^\d{1,6}$/.test(t)) { const p = produtos.find(x => x.plu === +t); if (p) return { p } }
    return null
  }
  const lancar = r => bip(adicionar(r.p, r.quantidade ? { quantidade: r.quantidade } : {}))
  /** Enter: lança o que foi identificado ou avisa que não achou */
  const lerCodigo = txt => {
    const r = identificar(txt, true)
    if (r) lancar(r); else if (txt.trim()) { setMsg(`Código não encontrado: ${txt.trim()}`); bip(false) }
    setCodigo(''); campo.current?.focus()
  }
  /** Ao digitar/escanear: assim que o código completo é reconhecido (8+ dígitos), já lança no caixa, sem precisar de Enter */
  const aoDigitar = v => {
    const r = v.length >= 8 ? identificar(v, false) : null
    if (r) { lancar(r); setCodigo('') } else setCodigo(v)
  }
  const focarCampo = () => setTimeout(() => { if (!document.querySelector('.modal')) campo.current?.focus() }, 0)
  useEffect(() => { focarCampo(); window.addEventListener('focus', focarCampo); return () => window.removeEventListener('focus', focarCampo) }, [])

  // Teclado: F4 foca o código de barras · Ctrl+tecla do produto · Tab/Shift+Tab troca a aba · F2 paga
  // (Ctrl+Tab é reservado pelo navegador e não pode ser capturado por páginas web; por isso F4)
  useEffect(() => {
    const h = e => {
      const emCampo = ['INPUT', 'SELECT', 'TEXTAREA'].includes(e.target.tagName)
      const emOutroCampo = emCampo && e.target !== campo.current        // o campo de código de barras não bloqueia os atalhos
      if (e.ctrlKey && !e.altKey && !e.metaKey) {                       // Ctrl + tecla do produto (AltGr = Ctrl+Alt é ignorado)
        if (e.repeat || pagando) return
        const k = e.key.length === 1 ? e.key.toUpperCase() : ''
        if (!k || (emCampo && 'ACVXZY'.includes(k))) return             // não rouba copiar/colar dentro dos campos
        const p = produtos.find(x => x.atalho === k)
        if (p) { e.preventDefault(); adicionar(p); focarCampo() }
        return
      }
      if (pagando) return
      if (e.key === 'F4') { e.preventDefault(); campo.current?.focus(); campo.current?.select(); return }
      if (emOutroCampo) return
      if (e.key === 'Tab' && !e.altKey && !e.ctrlKey && !e.metaKey) {   // "Alt+Tab" do caixa: o SO reserva Alt+Tab, então usamos Tab
        e.preventDefault(); setAba(a => (Math.min(a, abas.length - 1) + (e.shiftKey ? -1 : 1) + abas.length) % abas.length); return
      }
      if (e.key === 'F2' && itens.length) { e.preventDefault(); setPagando(true); return }
      if (!emCampo) {                                                   // digitou fora de qualquer campo: leva ao campo de código/PLU
        if (/^\d$/.test(e.key)) campo.current?.focus()
        else if (e.key === 'Enter' && codigo) lerCodigo(codigo)
      }
    }
    window.addEventListener('keydown', h); return () => window.removeEventListener('keydown', h)
  })

  async function finalizar(pagamentos) {
    const venda = { cpf, atacado: false, pagamentos, itens: itens.map(i => ({ produtoId: i.produtoId, quantidade: i.quantidade, pesoBalanca: i.pesoBalanca })) }
    try { const v = await enviarVenda(venda); setMsg('Venda concluída ✔'); posVenda(v, venda.cpf); carregar() }   // recarrega ranking e promoções
    catch (e) { if (e instanceof TypeError) { enfileirar(venda); setMsg('Sem rede: venda salva offline') } else return setMsg(String(e)) }
    setItens([]); setPagando(false); setCpf(''); focarCampo()
  }

  return (
    <div className="pdv">
      <section className="main">
        <header className="topbar">
          <img src="/logo.png" alt="Fruteira Conventos" />
          <label className="leitor" title="F4 para ir ao campo">
            <span>▮▯▮▮▯ Código de barras <kbd>F4</kbd></span>
            <input ref={campo} autoFocus inputMode="none" autoComplete="off" value={codigo} placeholder="Passe o leitor ou digite o código / PLU e Enter"
              onChange={e => aoDigitar(e.target.value)}
              onKeyDown={e => { if (e.key === 'Enter') { e.preventDefault(); lerCodigo(codigo) } else if (e.key === 'Escape') setCodigo('') }} />
          </label>
          {!balanca.conectada && <button className="btn sec" onClick={() => balanca.conectar().catch(() => setMsg('Falha na balança'))}>Conectar balança</button>}
          <span className="peso">{balanca.peso.toFixed(3)} kg</span>
        </header>
        {msg && <div className="msg">{msg}</div>}
        <div className="abas">
          {abas.map((a, k) => <button key={a.id} className={k === idxAba ? 'on' : ''} onClick={() => setAba(k)}>{a.nome}</button>)}
          <small>F4 código de barras · Tab / Shift+Tab troca a aba · Ctrl + tecla do produto · F2 pagar</small>
        </div>
        <div className="grid">
          {visiveis.map(p => {
            const promo = p.precoPromocional != null, n = noCarrinho[p.id], piscando = flash?.id === p.id
            return (
              <button key={p.id + (piscando ? '-' + flash.n : '')} className={`card ${promo ? 'promo' : ''} ${n ? 'no-carrinho' : ''} ${piscando ? 'flash' : ''}`} onClick={() => adicionar(p)}>
                {promo && <span className="ribbon">PROMO{p.descontoPct ? ` −${Number(p.descontoPct).toFixed(0)}%` : ''}</span>}
                {n > 0 && <span className="qtd-badge" title="Linhas deste produto na venda atual">{n}</span>}
                <div className="emoji"><ImagemProduto p={p} size={64} /></div>
                <div><span className="plu-tag">{p.plu}</span>{p.atalho && <span className="atalho-tag">Ctrl+{p.atalho}</span>} {p.nome}</div>
                <div className="preco">{promo && <s>{brl(p.precoVarejo)}</s>} {brl(preco(p))}/{p.unidade}</div>
                {promo && p.promocaoFim && <small className="eco">promoção até {dataCurta(p.promocaoFim)}</small>}
              </button>)
          })}
        </div>
      </section>
      <aside className="side">
        <div className="campo-x">
          <input placeholder="CPF na nota (fidelidade)" value={cpf} onChange={e => setCpf(e.target.value)} />
          {itens.length > 0 && <button className="x-limpar" title="Limpar todos os itens da venda" onClick={limparTudo}>✕</button>}
        </div>
        <ul>
          {itens.map((i, k) => <li key={i.uid} className={`${k === itens.length - 1 ? 'ultimo' : ''} ${i.promo ? 'item-promo' : ''}`}>
            <span>{i.nome} {i.promo && <span className="promo-tag">PROMO</span>}<br />
              <small>{i.quantidade.toFixed(3)} × {i.promo && <s>{brl(i.precoOriginal)}</s>} {brl(i.preco)}</small>
              {i.promo && <><br /><small className="eco">desconto −{brl(i.desconto)}</small></>}</span>
            <span className="li-dir"><b>{brl(i.subtotal)}</b><button className="x-item" title="Remover este item" onClick={() => removerItem(i)}>×</button></span></li>)}
          <li ref={fim} style={{ height: 0, padding: 0, border: 0, animation: 'none' }} />
        </ul>
        {descontoTotal > 0 && <div className="resumo"><div>Sem promoções: <s>{brl(total + descontoTotal)}</s></div><div className="eco">Desconto das promoções: −{brl(descontoTotal)}</div></div>}
        <div className="total">{brl(total)}</div>
        <button className="btn pagar" disabled={!itens.length} onClick={() => setPagando(true)}>Pagar (F2)</button>
      </aside>
      {pagando && <Pagamento total={total} desconto={descontoTotal} onOk={finalizar} onCancel={() => { setPagando(false); focarCampo() }} />}
    </div>)
}

const MEIOS = [
  { id: 'PIX', rot: 'PIX', tecla: 'F1' }, { id: 'DINHEIRO', rot: 'Dinheiro', tecla: 'F2' }, { id: 'CREDITO', rot: 'Crédito', tecla: 'F3' },
  { id: 'DEBITO', rot: 'Débito', tecla: 'F4' }, { id: 'VALE_ALIMENTACAO', rot: 'Vale-alimentação', tecla: 'F5' }, { id: 'VALE_REFEICAO', rot: 'Vale-refeição', tecla: 'F6' },
]

/** Pagamento. Atalhos: F1–F6 (ou 1–6) forma de pagamento · F7 valor exato · F8/F9 notas sugeridas · Enter confirma · Esc volta */
function Pagamento({ total, desconto = 0, onOk, onCancel }) {
  const [meio, setMeio] = useState('PIX'); const [recebido, setRecebido] = useState(total); const campo = useRef(null); const enviado = useRef(false)
  const troco = meio === 'DINHEIRO' ? Math.max(0, recebido - total) : 0
  const pode = meio !== 'DINHEIRO' || recebido >= total
  const sugestoes = [...new Set([Math.ceil(total / 10) * 10, Math.ceil(total / 50) * 50, Math.ceil(total / 100) * 100])].filter(v => v > total).slice(0, 2)
  const confirmar = () => {
    if (!pode || enviado.current) return
    enviado.current = true; setTimeout(() => { enviado.current = false }, 1500)     // evita duplo Enter
    onOk([{ meio, valor: meio === 'DINHEIRO' ? recebido : total }])
  }
  useEffect(() => { if (meio === 'DINHEIRO') { campo.current?.focus(); campo.current?.select() } }, [meio])
  useEffect(() => {
    const h = e => {
      const emInput = e.target.tagName === 'INPUT'
      const f = MEIOS.find(m => m.tecla === e.key)
      if (f) { e.preventDefault(); setMeio(f.id); return }
      if (e.key === 'F7') { e.preventDefault(); setMeio('DINHEIRO'); setRecebido(total); return }
      if ((e.key === 'F8' || e.key === 'F9') && sugestoes[e.key === 'F8' ? 0 : 1]) { e.preventDefault(); setMeio('DINHEIRO'); setRecebido(sugestoes[e.key === 'F8' ? 0 : 1]); return }
      if (e.key === 'Escape') { e.preventDefault(); onCancel(); return }
      if (e.key === 'Enter') { e.preventDefault(); confirmar(); return }
      if (!emInput && /^[1-6]$/.test(e.key)) setMeio(MEIOS[+e.key - 1].id)
    }
    window.addEventListener('keydown', h); return () => window.removeEventListener('keydown', h)
  })
  return (
    <div className="modal" role="dialog" aria-label="Pagamento">
      <div className="pag">
        <img src="/logo.png" alt="" style={{ height: 48, borderRadius: 6 }} />
        <h2>Total {brl(total)}</h2>
        {desconto > 0 && <p className="eco">Você economizou {brl(desconto)} em promoções 🎉</p>}
        <div className="meios">{MEIOS.map(m => <button key={m.id} className={m.id === meio ? 'on' : ''} onClick={() => setMeio(m.id)}><kbd>{m.tecla}</kbd> {m.rot}</button>)}</div>
        {meio === 'PIX' && <div style={{ textAlign: 'center', margin: 12 }}>
          {/* Troque o payload pelo "copia e cola" gerado pelo pagamento-service (PIX dinâmico via PSP) */}
          <QRCodeSVG value={`PIX-DEMO|valor=${total.toFixed(2)}`} size={190} fgColor="#066b43" /><p>Aguardando confirmação…</p></div>}
        {meio === 'DINHEIRO' && <div style={{ margin: '12px 0' }}>
          <label>Recebido: <input ref={campo} type="number" step="0.01" value={recebido} onChange={e => setRecebido(+e.target.value)} style={{ fontSize: 22, width: 150 }} /></label>
          <div style={{ fontSize: 22, margin: '8px 0' }}>Troco: <b style={{ color: pode ? 'var(--verde-escuro)' : 'var(--vermelho)' }}>{pode ? brl(troco) : 'valor insuficiente'}</b></div>
          <div className="meios"><button onClick={() => setRecebido(total)}><kbd>F7</kbd> Exato</button>
            {sugestoes.map((v, k) => <button key={v} onClick={() => setRecebido(v)}><kbd>F{8 + k}</kbd> {brl(v)}</button>)}</div></div>}
        <div><button className="btn" disabled={!pode} onClick={confirmar}>Confirmar <kbd>Enter</kbd></button>{' '}
          <button className="btn sec" onClick={onCancel}>Voltar <kbd>Esc</kbd></button></div>
        <small style={{ display: 'block', marginTop: 8, opacity: .7 }}>F1–F6 ou 1–6 escolhem a forma de pagamento</small>
      </div>
    </div>)
}
