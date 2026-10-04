import React, { useEffect, useState } from 'react'
import { QRCodeSVG } from 'qrcode.react'
import { useBalanca } from './balanca.js'
import { enfileirar, sincronizar } from './offline.js'


const brl = n => Number(n).toLocaleString('pt-BR', { style: 'currency', currency: 'BRL' })
const api = (p, o) => fetch(p, { headers: { 'Content-Type': 'application/json' }, ...o }).then(r => r.ok ? r.json() : r.text().then(t => Promise.reject(t)))
const posVenda = (v, cpf) => { // NFC-e (contingência se offline) e cashback do cliente
  const ign = () => {}
  fetch('/api/retaguarda/nfce/emitir', { method: 'POST', headers: { 'Content-Type': 'application/json' }, body: JSON.stringify({ vendaId: v.id, valor: v.total, cpf, contingencia: !navigator.onLine }) }).catch(ign)
  if (cpf) fetch(`/api/retaguarda/clientes/cpf/${cpf}/compra?valor=${v.total}`, { method: 'POST' }).catch(ign)
}
const enviarVenda = v => api('/api/vendas/vendas', { method: 'POST', body: JSON.stringify(v) })

export default function PDV() {
  const [produtos, setProdutos] = useState([]); const [itens, setItens] = useState([])
  const [plu, setPlu] = useState(''); const [cpf, setCpf] = useState(''); const [pagando, setPagando] = useState(false)
  const [msg, setMsg] = useState(''); const balanca = useBalanca()

  useEffect(() => { api('/api/catalogo/produtos').then(setProdutos).catch(() => setMsg('Catálogo indisponível')) }, [])
  useEffect(() => { window.addEventListener('online', () => sincronizar(enviarVenda)); }, [])

  const total = itens.reduce((s, i) => s + i.subtotal, 0)
  const preco = p => Number(p.precoPromocional ?? p.precoVarejo)

  function adicionar(p) {
    const porKg = p.unidade === 'KG'
    if (porKg && !(balanca.peso > 0)) return setMsg(`Coloque ${p.nome} na balança`)
    const qtd = porKg ? balanca.peso : 1
    setItens(l => [...l, { produtoId: p.id, nome: p.nome, quantidade: qtd, preco: preco(p), subtotal: +(qtd * preco(p)).toFixed(2), pesoBalanca: porKg }])
    setMsg(''); setPlu('')
  }
  // Atalho de teclado: digitar o PLU + Enter; F2 = pagamento
  useEffect(() => {
    const h = e => {
      const emCampo = ['INPUT', 'SELECT', 'TEXTAREA'].includes(e.target.tagName)
      if (e.ctrlKey && !e.altKey && !e.metaKey) {          // Ctrl + tecla do produto (AltGr = Ctrl+Alt é ignorado)
        if (e.repeat || pagando) return
        const k = e.key.length === 1 ? e.key.toUpperCase() : ''
        if (!k || (emCampo && 'ACVXZY'.includes(k))) return   // não rouba copiar/colar dentro dos campos
        const p = produtos.find(x => x.atalho === k)
        if (p) { e.preventDefault(); adicionar(p) }
        return
      }
      if (emCampo) return
      if (/^\d$/.test(e.key)) setPlu(x => x + e.key)
      if (e.key === 'Enter' && plu) { const p = produtos.find(x => x.plu === +plu); p ? adicionar(p) : (setMsg('PLU inválido'), setPlu('')) }
      if (e.key === 'F2' && itens.length) setPagando(true)
    }
    window.addEventListener('keydown', h); return () => window.removeEventListener('keydown', h)
  })

  async function finalizar(pagamentos) {
    const venda = { cpf, atacado: false, pagamentos, itens: itens.map(i => ({ produtoId: i.produtoId, quantidade: i.quantidade, pesoBalanca: i.pesoBalanca })) }
    try { const v = await enviarVenda(venda); setMsg('Venda concluída ✔'); posVenda(v, venda.cpf) }
    catch (e) { if (e instanceof TypeError) { enfileirar(venda); setMsg('Sem rede: venda salva offline') } else return setMsg(String(e)) }
    setItens([]); setPagando(false); setCpf('')
  }

  return (
    <div className="pdv">
      <section className="main">
        <header className="topbar">
          <img src="/logo.png" alt="Fruteira Conventos" />
          <span className="plu">PLU: <b>{plu || '—'}</b></span>
          {!balanca.conectada && <button className="btn sec" onClick={() => balanca.conectar().catch(() => setMsg('Falha na balança'))}>Conectar balança</button>}
          <span className="peso">{balanca.peso.toFixed(3)} kg</span>
        </header>
        {msg && <div className="msg">{msg}</div>}
        <div style={{ padding: '4px 14px' }}><small>Atalhos: PLU + Enter · Ctrl + tecla do produto · F2 pagar</small></div>
        <div className="grid">
          {produtos.map(p => (
            <button key={p.id} className="card" onClick={() => adicionar(p)}>
              {p.fotoUrl ? <img src={p.fotoUrl} alt="" width="80" height="80"/> : <div className="emoji">🥬</div>}
              <div><span className="plu-tag">{p.plu}</span>{p.atalho && <span className="atalho-tag">Ctrl+{p.atalho}</span>} {p.nome}</div>
              <div className="preco">{p.precoPromocional ? <s>{brl(p.precoVarejo)}</s> : null} {brl(preco(p))}/{p.unidade}</div>
            </button>))}
        </div>
      </section>
      <aside className="side">
        <input placeholder="CPF na nota (fidelidade)" value={cpf} onChange={e => setCpf(e.target.value)} />
        <ul>
          {itens.map((i, k) => <li key={k}>
            <span>{i.nome}<br/><small>{i.quantidade.toFixed(3)} × {brl(i.preco)}</small></span><b>{brl(i.subtotal)}</b></li>)}
        </ul>
        <div className="total">{brl(total)}</div>
        <button className="btn pagar" disabled={!itens.length} onClick={() => setPagando(true)}>Pagar (F2)</button>
      </aside>
      {pagando && <Pagamento total={total} onOk={finalizar} onCancel={() => setPagando(false)} />}
    </div>)
}

function Pagamento({ total, onOk, onCancel }) {
  const [meio, setMeio] = useState('PIX'); const [recebido, setRecebido] = useState(total)
  const meios = ['PIX', 'DINHEIRO', 'CREDITO', 'DEBITO', 'VALE_ALIMENTACAO', 'VALE_REFEICAO']
  const troco = meio === 'DINHEIRO' ? Math.max(0, recebido - total) : 0
  return (
    <div className="modal">
      <div>
        <img src="/logo.png" alt="" style={{ height: 48, borderRadius: 6 }} />
        <h2>Total {brl(total)}</h2>
        <div className="meios">{meios.map(m => <button key={m} className={m === meio ? 'on' : ''} onClick={() => setMeio(m)}>{m.replace('_', ' ')}</button>)}</div>
        {meio === 'PIX' && <div style={{ textAlign: 'center', margin: 12 }}>
          {/* Troque o payload pelo "copia e cola" gerado pelo pagamento-service (PIX dinâmico via PSP) */}
          <QRCodeSVG value={`PIX-DEMO|valor=${total.toFixed(2)}`} size={200} fgColor="#066b43" /><p>Aguardando confirmação…</p></div>}
        {meio === 'DINHEIRO' && <p>Recebido: <input type="number" value={recebido} onChange={e => setRecebido(+e.target.value)} /> Troco: <b>{brl(troco)}</b></p>}
        <button className="btn" onClick={() => onOk([{ meio, valor: meio === 'DINHEIRO' ? recebido : total }])}>Confirmar</button>{' '}
        <button className="btn sec" onClick={onCancel}>Voltar</button>
      </div>
    </div>)
}
