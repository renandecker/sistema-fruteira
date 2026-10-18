import React, { useEffect, useMemo, useState } from 'react'
import { api, brl, Pagina, Aviso } from './lib.jsx'
import { mascaraCpf } from './cpf.js'
import { montarRelatorioClientes, csvClientes, DIAS_ATRASO } from './caderno.js'

const dt = d => d ? new Date(d).toLocaleDateString('pt-BR') : '—'
const hoje = () => new Date().toISOString().slice(0, 10)
const mesAtras = () => { const d = new Date(); d.setDate(d.getDate() - 30); return d.toISOString().slice(0, 10) }

/** Relatório de clientes: quem mais compra (ranking, ticket médio, total) junto de quem está devendo no caderno. */
export default function RelClientes() {
  const [de, setDe] = useState(mesAtras()); const [ate, setAte] = useState(hoje()); const [msg, setMsg] = useState('')
  const [dados, setDados] = useState({ compras: [], devedores: { lista: [] }, nomes: {} }); const [aba, setAba] = useState('compram')
  const carregar = async () => {
    setMsg('')
    try {
      const q = `?de=${de}&ate=${ate}`
      const [compras, devedores, clientes] = await Promise.all([api('/api/vendas/vendas/relatorio/clientes' + q), api('/api/retaguarda/caderno/devedores'), api('/api/retaguarda/clientes')])
      setDados({ compras, devedores, nomes: Object.fromEntries(clientes.map(c => [c.cpf, c.nome])) })
    } catch (e) { setMsg(String(e)) }
  }
  useEffect(() => { carregar() }, [])
  const rel = useMemo(() => montarRelatorioClientes(dados.compras, dados.devedores, dados.nomes), [dados])
  const k = rel.kpis
  const exportar = () => {
    const a = document.createElement('a'); a.href = URL.createObjectURL(new Blob(['﻿' + csvClientes(rel.linhas)], { type: 'text/csv;charset=utf-8' })); a.download = `clientes_${de}_${ate}.csv`; a.click()
  }
  const lista = aba === 'compram' ? rel.porCompra : rel.porDivida
  return <Pagina titulo="🧑‍🤝‍🧑 Clientes: compras e dívidas">
    <Aviso m={msg} />
    <div className="barra"><label>De <input type="date" value={de} onChange={e => setDe(e.target.value)} /></label><label>Até <input type="date" value={ate} onChange={e => setAte(e.target.value)} /></label>
      <button className="btn" onClick={carregar}>Atualizar</button><button className="btn sec" onClick={exportar} disabled={!rel.linhas.length}>Exportar CSV</button>
      <small>O período vale para as compras. A dívida do caderno é sempre a de hoje (tudo que ainda não foi pago).</small></div>
    <div className="kpis">
      <div className="kpi"><small>Total comprado (identificado por CPF)</small><b>{brl(k.totalComprado)}</b></div>
      <div className="kpi"><small>Clientes que compraram</small><b>{k.clientesQueCompraram}</b></div>
      <div className="kpi"><small>Melhor cliente</small><b>{k.melhorCliente ? `${k.melhorCliente.nome || mascaraCpf(k.melhorCliente.cpf)} · ${brl(k.melhorCliente.total)}` : '—'}</b></div>
      <div className="kpi"><small>Total a receber (caderno)</small><b>{brl(k.totalAReceber)}</b></div>
      <div className="kpi"><small>Clientes devendo</small><b>{k.devedores}</b></div>
      <div className="kpi"><small>Em atraso (≥ {DIAS_ATRASO} dias)</small><b style={{ color: k.emAtraso ? 'var(--vermelho)' : undefined }}>{k.emAtraso}</b></div>
      <div className="kpi"><small>Maior devedor</small><b>{k.maiorDevedor ? `${k.maiorDevedor.nome || mascaraCpf(k.maiorDevedor.cpf)} · ${brl(k.maiorDevedor.deve)}` : '—'}</b></div>
      <div className="kpi"><small>Fiado sobre o comprado</small><b>{k.pesoDoFiado}%</b></div>
    </div>
    <div className="meios"><button className={aba === 'compram' ? 'on' : ''} onClick={() => setAba('compram')}>Quem mais compra</button><button className={aba === 'devem' ? 'on' : ''} onClick={() => setAba('devem')}>Quem mais deve</button></div>
    <table className="tab"><thead><tr><th>#</th><th>Cliente</th><th>CPF</th><th>Compras</th><th>Total comprado</th><th>Ticket médio</th><th>Última compra</th><th>Deve no caderno</th></tr></thead>
      <tbody>{lista.map((l, i) => <tr key={l.cpf} className={l.diasAtraso >= DIAS_ATRASO ? 'linha-atraso' : ''}><td>{i + 1}</td><td>{l.nome || '—'}</td><td>{mascaraCpf(l.cpf)}</td><td>{l.compras}</td><td>{brl(l.total)}</td>
        <td>{brl(l.ticketMedio)}</td><td>{dt(l.ultimaCompra)}</td><td>{l.deve > 0 ? <b style={{ color: 'var(--vermelho)' }}>{brl(l.deve)}{l.diasAtraso > 0 ? ` · ${l.diasAtraso}d` : ''}</b> : '—'}</td></tr>)}
        {!lista.length && <tr><td colSpan={8}>Sem dados no período.</td></tr>}</tbody></table>
  </Pagina>
}
