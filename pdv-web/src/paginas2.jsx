import React, { useEffect, useState } from 'react'
import { QRCodeSVG } from 'qrcode.react'
import { brl, api, post, cfg, useProdutos, useLista, useForm, Pagina, Aviso, SelProduto } from './lib.jsx'
import { useBalanca } from './balanca.js'
import { PROD, KC } from './auth.js'

const R = '/api/retaguarda'
const Selo = ({ x }) => x.ativo === false ? <span className="classe off">Desativado</span> : null
const BtnAtivo = ({ x, onDesativar, onReativar }) => x.ativo === false
  ? <button className="btn sec" onClick={() => onReativar(x)}>Reativar</button>
  : <button className="btn sec" onClick={() => onDesativar(x)}>Desativar</button>
const nota = 'Registros desativados continuam no histórico e na auditoria; somente o gerente os vê e pode reativá-los.'
const dataBR = d => d ? new Date(d + 'T00:00:00').toLocaleDateString('pt-BR') : '—'

/* ---------- Cadastros ---------- */
// Teclas permitidas p/ Ctrl+tecla. Ficam de fora N, T e W: o navegador reserva Ctrl+N/T/W e a página não consegue interceptá-los.
export const TECLAS = [...'0123456789ABCDEFGHIJKLMOPQRSUVXYZ']
export function NovoProduto({ onSave, usados = [], categorias = [] }) {
  const ini = { nome: '', unidade: 'KG', categoria: '', precoVarejo: '', plu: '', atalho: '', taxaPerdaPct: 0, ncm: '', codigoBarras: '' }
  const [f, s, setF] = useForm(ini); const [msg, setMsg] = useState('')
  const salvar = () => post('/api/catalogo/produtos', { ...f, precoVarejo: +f.precoVarejo, plu: f.plu ? +f.plu : null, taxaPerdaPct: +f.taxaPerdaPct })
    .then(() => { setF(ini); setMsg('Produto cadastrado ✔'); onSave() }).catch(e => setMsg(String(e)))
  return <details className="barra" style={{ display: 'block' }}><summary><b>+ Novo produto</b></summary><Aviso m={msg} />
    <div className="grid2" style={{ marginTop: 10 }}>
      <label>Nome<input value={f.nome} onChange={s('nome')} /></label>
      <label>Unidade<select value={f.unidade} onChange={s('unidade')}><option>KG</option><option>UN</option><option>MACO</option><option>CX</option></select></label>
      <label>Categoria <small>vira aba no PDV</small><select value={f.categoria} onChange={s('categoria')}><option value="">Sem categoria</option>
        {categorias.map(c => <option key={c.id} value={c.nome}>{c.nome}</option>)}</select></label>
      <label>Preço varejo<input type="number" value={f.precoVarejo} onChange={s('precoVarejo')} /></label>
      <label>PLU rápido<input type="number" value={f.plu} onChange={s('plu')} /></label>
      <label>Atalho no caixa (Ctrl + …) <small>opcional</small><select value={f.atalho} onChange={s('atalho')}><option value="">Sem atalho</option>
        {TECLAS.map(t => <option key={t} value={t} disabled={usados.includes(t)}>{t}{usados.includes(t) ? ' (em uso)' : ''}</option>)}</select></label>
      <label>Perda esperada %<input type="number" value={f.taxaPerdaPct} onChange={s('taxaPerdaPct')} /></label>
      <label>NCM<input value={f.ncm} onChange={s('ncm')} /></label>
      <label>Cód. barras<input value={f.codigoBarras} onChange={s('codigoBarras')} /></label></div>
    <button className="btn" style={{ marginTop: 10 }} disabled={!f.nome || !f.precoVarejo} onClick={salvar}>Salvar produto</button></details>
}

export function Fornecedores() {
  const [l, c] = useLista(R + '/fornecedores?inativos=true'); const ini = { nome: '', documento: '', tipo: 'CEASA', telefone: '' }
  const [f, s, setF] = useForm(ini); const [msg, setMsg] = useState('')
  const erro = e => setMsg(String(e) || 'Operação não permitida')
  const add = () => post(R + '/fornecedores', f).then(() => { setF(ini); c() }).catch(erro)
  const desativar = x => window.confirm(`Desativar o fornecedor "${x.nome}"?\n${nota}`) && api(`${R}/fornecedores/${x.id}`, { method: 'DELETE' }).then(c).catch(erro)
  const reativar = x => post(`${R}/fornecedores/${x.id}/reativar`).then(c).catch(erro)
  return <Pagina titulo="🚚 Fornecedores"><Aviso m={msg} />
    <div className="barra"><input placeholder="Nome" value={f.nome} onChange={s('nome')} /><input placeholder="CNPJ/CPF" value={f.documento} onChange={s('documento')} />
      <select value={f.tipo} onChange={s('tipo')}><option value="CEASA">CEASA</option><option value="PRODUTOR_RURAL">Produtor rural</option><option value="EMPRESA">Empresa</option></select>
      <input placeholder="Telefone" value={f.telefone} onChange={s('telefone')} /><button className="btn" disabled={!f.nome} onClick={add}>Adicionar</button></div>
    <table className="tab"><thead><tr><th>Nome</th><th>Documento</th><th>Tipo</th><th>Telefone</th><th>Situação</th><th></th></tr></thead>
      <tbody>{l.map(x => <tr key={x.id} className={x.ativo === false ? 'inativo' : ''}><td>{x.nome}</td><td>{x.documento}</td><td>{x.tipo}</td><td>{x.telefone}</td>
        <td>{x.ativo === false ? <Selo x={x} /> : 'Ativo'}</td><td><BtnAtivo x={x} onDesativar={desativar} onReativar={reativar} /></td></tr>)}</tbody></table></Pagina>
}

export function Clientes() {
  const [l, c] = useLista(R + '/clientes?inativos=true'); const ini = { nome: '', cpf: '', telefone: '' }; const [f, s, setF] = useForm(ini); const [msg, setMsg] = useState('')
  const erro = (e, padrao) => setMsg(String(e) || padrao || 'Operação não permitida')
  const add = () => post(R + '/clientes', f).then(() => { setF(ini); c(); setMsg('') }).catch(e => erro(e, 'Não foi possível cadastrar (CPF já cadastrado ou de cliente desativado).'))
  const desativar = x => window.confirm(`Desativar o cliente "${x.nome}"?\n${nota}`) && api(`${R}/clientes/${x.id}`, { method: 'DELETE' }).then(c).catch(erro)
  const reativar = x => post(`${R}/clientes/${x.id}/reativar`).then(c).catch(erro)
  return <Pagina titulo="👥 Clientes e fidelidade"><Aviso m={msg} />
    <div className="barra"><input placeholder="Nome" value={f.nome} onChange={s('nome')} /><input placeholder="CPF (só números)" value={f.cpf} onChange={s('cpf')} />
      <input placeholder="WhatsApp com DDD" value={f.telefone} onChange={s('telefone')} /><button className="btn" disabled={!f.nome || !f.cpf} onClick={add}>Cadastrar</button>
      <small>Cashback: 2% de cada compra identificada pelo CPF no caixa. Cliente desativado não acumula nem resgata.</small></div>
    <table className="tab"><thead><tr><th>Nome</th><th>CPF</th><th>WhatsApp</th><th>Cashback</th><th>Situação</th><th></th></tr></thead>
      <tbody>{l.map(x => <tr key={x.id} className={x.ativo === false ? 'inativo' : ''}><td>{x.nome}</td><td>{x.cpf}</td><td>{x.telefone}</td><td><b>{brl(x.cashback)}</b></td>
        <td>{x.ativo === false ? <Selo x={x} /> : 'Ativo'}</td><td><BtnAtivo x={x} onDesativar={desativar} onReativar={reativar} /></td></tr>)}</tbody></table></Pagina>
}

/* ---------- Estoque / Compras ---------- */
export function Producao() {
  const [lista] = useProdutos(); const [f, setF] = useState({ insumoId: '', qtdInsumo: '', produtoId: '', qtdProduto: '' }); const [msg, setMsg] = useState('')
  const rend = f.qtdInsumo && f.qtdProduto ? (f.qtdProduto / f.qtdInsumo * 100).toFixed(0) : null
  const enviar = () => post('/api/estoque/estoque/producao', { insumoId: +f.insumoId, qtdInsumo: +f.qtdInsumo, produtoId: +f.produtoId, qtdProduto: +f.qtdProduto })
    .then(() => setMsg('Produção registrada ✔ insumo baixado e produto pronto lançado no estoque')).catch(e => setMsg(String(e)))
  return <Pagina titulo="🔪 Produção / fracionamento"><Aviso m={msg} />
    <div className="form"><p>Transforme frutas prestes a vencer em produtos prontos (ex.: abacaxi → bandeja picada). Cadastre antes o produto final em Produtos e preços.</p>
      <label>Insumo (sai do estoque)<SelProduto lista={lista} v={f.insumoId} onChange={v => setF({ ...f, insumoId: v })} /></label>
      <label>Quantidade usada<input type="number" value={f.qtdInsumo} onChange={e => setF({ ...f, qtdInsumo: e.target.value })} /></label>
      <label>Produto final (entra no estoque)<SelProduto lista={lista} v={f.produtoId} onChange={v => setF({ ...f, produtoId: v })} /></label>
      <label>Quantidade produzida<input type="number" value={f.qtdProduto} onChange={e => setF({ ...f, qtdProduto: e.target.value })} /></label>
      {rend && <p>Rendimento: <b>{rend}%</b></p>}
      <button className="btn" disabled={!f.insumoId || !f.produtoId || !f.qtdInsumo || !f.qtdProduto} onClick={enviar}>Registrar produção</button></div></Pagina>
}

export function ImportarXml() {
  const [lista] = useProdutos(); const [nota, setNota] = useState(null); const [msg, setMsg] = useState('')
  const t = (el, tag) => el.getElementsByTagName(tag)[0]?.textContent ?? ''
  const ler = async e => {
    const d = new DOMParser().parseFromString(await e.target.files[0].text(), 'text/xml')
    const itens = [...d.getElementsByTagName('det')].map(x => {
      const p = x.getElementsByTagName('prod')[0]; const nome = t(p, 'xProd'); const ean = t(p, 'cEAN')
      const m = lista.find(pr => (pr.codigoBarras && pr.codigoBarras === ean) || nome.toLowerCase().includes(pr.nome.toLowerCase().split(' ')[0]))
      return { nome, qtd: +t(p, 'qCom'), un: t(p, 'uCom'), valor: +t(p, 'vProd'), produtoId: m?.id ?? '', fator: 1 }
    })
    if (!itens.length) return setMsg('XML sem itens (é uma NF-e?)')
    setNota({ fornecedor: t(d, 'xNome'), numero: t(d, 'nNF'), itens }); setMsg('')
  }
  const upd = (k, c, v) => setNota({ ...nota, itens: nota.itens.map((i, j) => j === k ? { ...i, [c]: v } : i) })
  const lancar = async () => {
    let n = 0
    try { for (const i of nota.itens) { if (!i.produtoId) continue
      await post('/api/estoque/estoque/entrada', { produtoId: +i.produtoId, qtdEmbalagem: i.qtd, fatorConversao: +i.fator, custoTotal: i.valor, documento: `NF ${nota.numero} - ${nota.fornecedor}` })
      await post(R + '/cotacoes', { produtoNome: i.nome, fornecedor: nota.fornecedor, preco: +(i.valor / (i.qtd * i.fator)).toFixed(4) }); n++ }
      setMsg(`${n} itens lançados no estoque (custo médio e cotações atualizados) ✔`); setNota(null)
    } catch (e) { setMsg(String(e)) }
  }
  return <Pagina titulo="🧾 Importar XML (NF-e)"><Aviso m={msg} />
    <div className="barra"><input type="file" accept=".xml" onChange={ler} /><small>Aceita NF-e de entrada de fornecedores e CEASA. Para produtor rural pessoa física, lance a entrada manualmente (contranota).</small></div>
    {nota && <><p>Fornecedor: <b>{nota.fornecedor}</b> · NF <b>{nota.numero}</b></p>
      <table className="tab"><thead><tr><th>Item da nota</th><th>Qtd</th><th>Valor</th><th>Produto do sistema</th><th>Fator (kg/un por {`embalagem`})</th></tr></thead>
        <tbody>{nota.itens.map((i, k) => <tr key={k}><td>{i.nome}</td><td>{i.qtd} {i.un}</td><td>{brl(i.valor)}</td>
          <td><SelProduto lista={lista} v={i.produtoId} onChange={v => upd(k, 'produtoId', v)} /></td>
          <td><input type="number" style={{ width: 80 }} value={i.fator} onChange={e => upd(k, 'fator', e.target.value)} /></td></tr>)}</tbody></table>
      <button className="btn" style={{ marginTop: 12 }} onClick={lancar}>Dar entrada no estoque</button></>}</Pagina>
}

export function Ceasa() {
  const [lista] = useProdutos(); const [res, cr] = useLista(R + '/cotacoes/resumo'); const [hist, ch] = useLista(R + '/cotacoes?inativos=true')
  const [f, s, setF] = useForm({ produtoNome: '', fornecedor: '', preco: '' }); const [msg, setMsg] = useState('')
  const recarregar = () => { cr(); ch() }
  const erro = e => setMsg(String(e) || 'Operação não permitida')
  const add = () => post(R + '/cotacoes', { ...f, preco: +f.preco }).then(() => { setF({ ...f, preco: '' }); recarregar() }).catch(erro)
  const desativar = x => window.confirm(`Desativar a cotação de ${x.produtoNome} (${brl(x.preco)})?\n${nota}`) && api(`${R}/cotacoes/${x.id}`, { method: 'DELETE' }).then(recarregar).catch(erro)
  const reativar = x => post(`${R}/cotacoes/${x.id}/reativar`).then(recarregar).catch(erro)
  return <Pagina titulo="📈 Cotações CEASA"><Aviso m={msg} />
    <div className="barra"><input list="prods" placeholder="Produto" value={f.produtoNome} onChange={s('produtoNome')} /><datalist id="prods">{lista.map(p => <option key={p.id} value={p.nome} />)}</datalist>
      <input placeholder="Fornecedor" value={f.fornecedor} onChange={s('fornecedor')} /><input type="number" placeholder="Preço pago (R$/un.)" value={f.preco} onChange={s('preco')} />
      <button className="btn" disabled={!f.produtoNome || !f.preco} onClick={add}>Registrar cotação</button></div>
    <h3>Resumo por produto <small>(só cotações ativas)</small></h3>
    <table className="tab"><thead><tr><th>Produto</th><th>Menor</th><th>Média</th><th>Maior</th><th>Último</th><th>Fornecedor</th></tr></thead>
      <tbody>{res.map(r => <tr key={r.produto}><td>{r.produto}</td><td>{brl(r.min)}</td><td>{brl(r.media)}</td><td>{brl(r.max)}</td><td><b>{brl(r.ultima)}</b></td><td>{r.fornecedorUltima}</td></tr>)}</tbody></table>
    <h3>Histórico</h3>
    <table className="tab"><thead><tr><th>Data</th><th>Produto</th><th>Fornecedor</th><th>Preço</th><th>Situação</th><th></th></tr></thead>
      <tbody>{hist.slice(0, 80).map(h => <tr key={h.id} className={h.ativo === false ? 'inativo' : ''}><td>{dataBR(h.data)}</td><td>{h.produtoNome}</td><td>{h.fornecedor}</td><td>{brl(h.preco)}</td>
        <td>{h.ativo === false ? <Selo x={h} /> : 'Ativa'}</td><td><BtnAtivo x={h} onDesativar={desativar} onReativar={reativar} /></td></tr>)}</tbody></table></Pagina>
}

export function Sugestao() {
  const [clima, setClima] = useState(null); const [rows, setRows] = useState([]); const [msg, setMsg] = useState('')
  const amanha = new Date(Date.now() + 864e5); const dow = amanha.getDay() || 7
  useEffect(() => {
    api(`/api/vendas/vendas/relatorio/media-dia-semana?dow=${dow}`).then(setRows).catch(() => setMsg('Sem histórico de vendas ainda'))
    const buscar = (lat, lon) => fetch(`https://api.open-meteo.com/v1/forecast?latitude=${lat}&longitude=${lon}&daily=temperature_2m_max,precipitation_probability_max&timezone=auto&forecast_days=2`)
      .then(r => r.json()).then(d => setClima({ tmax: d.daily.temperature_2m_max[1], chuva: d.daily.precipitation_probability_max[1] })).catch(() => setMsg('Previsão do tempo indisponível'))
    const c = cfg()
    if (c.lat && c.lon) buscar(c.lat, c.lon)
    else navigator.geolocation?.getCurrentPosition(p => buscar(p.coords.latitude, p.coords.longitude), () => setMsg('Informe latitude/longitude em Configurações → Balança e periféricos'))
  }, [])
  const aquosa = /melancia|laranja|abacaxi|uva|mel[ãa]o|tangerina|p[êe]ra/i
  const fator = n => { let f = 0; if (clima) { if (clima.tmax >= 30) f += aquosa.test(n) ? .25 : .05; if (clima.chuva >= 60) f -= .15; if (clima.tmax <= 15) f -= .10 } return f }
  const dias = ['', 'segunda', 'terça', 'quarta', 'quinta', 'sexta', 'sábado', 'domingo']
  return <Pagina titulo="🌦️ Sugestão de compras"><Aviso m={msg} />
    <div className="barra">Amanhã ({dias[dow]}): {clima ? <b>máx. {clima.tmax}°C · chuva {clima.chuva}%</b> : 'carregando previsão…'}
      <small>Regras: ≥30°C aumenta frutas aquosas (+25%) e demais (+5%); chuva ≥60% reduz 15%; ≤15°C reduz 10%.</small></div>
    <table className="tab"><thead><tr><th>Produto</th><th>Média histórica ({dias[dow]})</th><th>Ajuste clima</th><th>Sugestão de compra</th></tr></thead>
      <tbody>{rows.map(r => { const f = fator(r.nome); return <tr key={r.produtoId}><td>{r.nome}</td><td>{r.mediaQtd}</td>
        <td style={{ color: f < 0 ? 'var(--vermelho)' : 'var(--verde-escuro)' }}>{f >= 0 ? '+' : ''}{(f * 100).toFixed(0)}%</td><td><b>{(r.mediaQtd * (1 + f)).toFixed(1)}</b></td></tr> })}</tbody></table></Pagina>
}

/* ---------- Financeiro / Fiscal ---------- */
export function Contas() {
  const [l, c] = useLista(R + '/contas?inativos=true'); const [tipo, setTipo] = useState('PAGAR'); const [msg, setMsg] = useState('')
  const ini = { descricao: '', categoria: 'Fornecedores', valor: '', vencimento: new Date().toISOString().slice(0, 10) }; const [f, s, setF] = useForm(ini)
  const erro = e => setMsg(String(e) || 'Operação não permitida (conta já paga?)')
  const add = () => post(R + '/contas', { ...f, tipo, valor: +f.valor }).then(() => { setF(ini); c(); setMsg('') }).catch(erro)
  const desativar = x => window.confirm(`Desativar a conta "${x.descricao}" (${brl(x.valor)})?\n${nota}`) && api(`${R}/contas/${x.id}`, { method: 'DELETE' }).then(c).catch(erro)
  const reativar = x => post(`${R}/contas/${x.id}/reativar`).then(c).catch(erro)
  const rows = l.filter(x => x.tipo === tipo); const aberto = rows.filter(x => !x.pago && x.ativo !== false).reduce((a, x) => a + x.valor, 0)
  const hoje = new Date().toISOString().slice(0, 10)
  return <Pagina titulo="💳 Contas a pagar / receber"><Aviso m={msg} />
    <div className="meios"><button className={tipo === 'PAGAR' ? 'on' : ''} onClick={() => setTipo('PAGAR')}>A pagar</button><button className={tipo === 'RECEBER' ? 'on' : ''} onClick={() => setTipo('RECEBER')}>A receber</button></div>
    <div className="barra"><input placeholder="Descrição" value={f.descricao} onChange={s('descricao')} />
      <select value={f.categoria} onChange={s('categoria')}>{['Fornecedores', 'Aluguel', 'Energia', 'Salários', 'Impostos', 'Outros'].map(x => <option key={x}>{x}</option>)}</select>
      <input type="number" placeholder="Valor" value={f.valor} onChange={s('valor')} /><input type="date" value={f.vencimento} onChange={s('vencimento')} />
      <button className="btn" disabled={!f.descricao || !f.valor} onClick={add}>Lançar</button></div>
    <p>Em aberto: <b>{brl(aberto)}</b> <small>(contas desativadas não entram no total, no DRE nem no fluxo)</small></p>
    <table className="tab"><thead><tr><th>Vencimento</th><th>Descrição</th><th>Categoria</th><th>Valor</th><th>Situação</th><th></th></tr></thead>
      <tbody>{rows.map(x => <tr key={x.id} className={x.ativo === false ? 'inativo' : ''}><td>{dataBR(x.vencimento)}</td><td>{x.descricao}</td><td>{x.categoria}</td><td>{brl(x.valor)}</td>
        <td>{x.ativo === false ? <Selo x={x} /> : x.pago ? `Pago em ${dataBR(x.dataPagamento)}` : x.vencimento < hoje ? <b style={{ color: 'var(--vermelho)' }}>Vencida</b> : 'Em aberto'}</td>
        <td>{x.ativo !== false && !x.pago && <button className="btn sec" onClick={() => post(`${R}/contas/${x.id}/baixar`).then(c).catch(erro)}>Baixar</button>}{' '}
          {(x.ativo === false || !x.pago) && <BtnAtivo x={x} onDesativar={desativar} onReativar={reativar} />}</td></tr>)}</tbody></table></Pagina>
}

export function Dre() {
  const [res, setRes] = useState({ receita: 0, cmv: 0, vendas: 0 }); const [perdas, setPerdas] = useState(0); const [contas] = useLista(R + '/contas')
  useEffect(() => {
    api('/api/vendas/vendas/relatorio/resumo').then(setRes).catch(() => {})
    api('/api/estoque/estoque/relatorio/perdas').then(r => setPerdas(r.reduce((a, x) => a + x.valorPerdido, 0))).catch(() => {})
  }, [])
  const desp = {}; contas.filter(c => c.tipo === 'PAGAR' && c.pago && c.categoria !== 'Fornecedores').forEach(c => desp[c.categoria] = (desp[c.categoria] || 0) + c.valor)
  const totDesp = Object.values(desp).reduce((a, b) => a + b, 0); const outras = contas.filter(c => c.tipo === 'RECEBER' && c.pago).reduce((a, c) => a + c.valor, 0)
  const bruto = res.receita - res.cmv; const resultado = bruto - perdas - totDesp + outras
  const L = ({ n, v, b }) => <tr><td style={{ fontWeight: b ? 700 : 400 }}>{n}</td><td style={{ textAlign: 'right', fontWeight: b ? 700 : 400 }}>{brl(v)}</td></tr>
  // fluxo de caixa previsto (contas em aberto, próximos 30 dias)
  const lim = new Date(Date.now() + 30 * 864e5).toISOString().slice(0, 10); const hoje = new Date().toISOString().slice(0, 10)
  const dias = {}; contas.filter(c => !c.pago && c.vencimento <= lim).forEach(c => { const d = c.vencimento < hoje ? hoje : c.vencimento; dias[d] = dias[d] || { e: 0, s: 0 }; c.tipo === 'RECEBER' ? dias[d].e += c.valor : dias[d].s += c.valor })
  let acc = 0
  return <Pagina titulo="📊 DRE e fluxo de caixa">
    <h3>DRE simplificado (acumulado)</h3>
    <table className="tab" style={{ maxWidth: 520 }}><tbody>
      <L n="Receita de vendas" v={res.receita} /><L n="(−) CMV (custo das mercadorias vendidas)" v={-res.cmv} /><L n="Lucro bruto" v={bruto} b />
      <L n="(−) Perdas e quebras" v={-perdas} />{Object.entries(desp).map(([k, v]) => <L key={k} n={`(−) ${k}`} v={-v} />)}<L n="(+) Outras receitas" v={outras} />
      <L n="Resultado" v={resultado} b /></tbody></table>
    <p><small>{res.vendas} vendas · compras de mercadoria entram via CMV, não como despesa.</small></p>
    <h3>Fluxo de caixa previsto — 30 dias</h3>
    <table className="tab"><thead><tr><th>Data</th><th>Entradas</th><th>Saídas</th><th>Saldo acumulado</th></tr></thead>
      <tbody>{Object.keys(dias).sort().map(d => { acc += dias[d].e - dias[d].s; return <tr key={d}><td>{dataBR(d)}</td><td>{brl(dias[d].e)}</td><td>{brl(dias[d].s)}</td><td style={{ color: acc < 0 ? 'var(--vermelho)' : 'inherit' }}><b>{brl(acc)}</b></td></tr> })}</tbody></table></Pagina>
}

export function Nfce() {
  const [notas, cn] = useLista(R + '/nfce'); const [vendas] = useLista('/api/vendas/vendas?limit=30'); const [msg, setMsg] = useState('')
  const emitidas = new Set(notas.map(n => n.vendaId)); const act = (p, o) => post(p, o).then(cn).catch(e => setMsg(String(e)))
  const cor = { AUTORIZADA: 'var(--verde)', CONTINGENCIA: '#e0a100', CANCELADA: 'var(--vermelho)' }
  return <Pagina titulo="🧮 NFC-e / SAT"><Aviso m={msg} />
    <div className="msg" style={{ margin: '0 0 12px' }}>⚠ Ambiente <b>simulado</b>: gera chave de 44 dígitos e protocolo fictício. Para valer fiscalmente, integre o emissor (SEFAZ/Focus NFe) em <code>NfceResource</code>.</div>
    <h3>Vendas sem nota</h3>
    <table className="tab"><thead><tr><th>Venda</th><th>Data</th><th>Total</th><th></th></tr></thead>
      <tbody>{vendas.filter(v => v.status === 'PAGA' && !emitidas.has(v.id)).map(v => <tr key={v.id}><td>#{v.id}</td><td>{new Date(v.data).toLocaleString('pt-BR')}</td><td>{brl(v.total)}</td>
        <td><button className="btn" onClick={() => act(R + '/nfce/emitir', { vendaId: v.id, valor: v.total, cpf: v.cpf, contingencia: false })}>Emitir NFC-e</button></td></tr>)}</tbody></table>
    <h3>Notas</h3>
    <table className="tab"><thead><tr><th>Nº</th><th>Venda</th><th>Chave</th><th>Valor</th><th>Status</th><th></th></tr></thead>
      <tbody>{notas.map(n => <tr key={n.id}><td>{n.numero}</td><td>#{n.vendaId}</td><td style={{ fontSize: 11 }}>{n.chave}</td><td>{brl(n.valor)}</td>
        <td><span className="classe" style={{ background: cor[n.status] }}>{n.status}</span></td>
        <td>{n.status === 'CONTINGENCIA' && <button className="btn sec" onClick={() => act(`${R}/nfce/${n.id}/transmitir`)}>Transmitir</button>}
          {n.status === 'AUTORIZADA' && <button className="btn sec" onClick={() => act(`${R}/nfce/${n.id}/cancelar`)}>Cancelar</button>}</td></tr>)}</tbody></table></Pagina>
}

/* ---------- Relatórios / Marketing ---------- */
export function Margem() {
  const [lista] = useProdutos()
  const m = p => { const preco = +(p.precoVarejo || 0), custo = +(p.custoMedio || 0), liq = preco * (1 - (+p.taxaPerdaPct || 0) / 100)
    return { bruta: preco ? (preco - custo) / preco * 100 : 0, real: liq ? (liq - custo) / liq * 100 : 0 } }
  const cats = {}; lista.forEach(p => { const c = p.categoria || 'sem categoria'; (cats[c] = cats[c] || []).push(m(p)) })
  const avg = (l, k) => l.reduce((a, x) => a + x[k], 0) / l.length
  const cor = v => v < 15 ? 'var(--vermelho)' : v < 30 ? '#b8860b' : 'var(--verde-escuro)'
  return <Pagina titulo="💹 Margem por categoria">
    <p>Margem bruta = (preço − custo) ÷ preço. Margem real desconta a perda esperada da categoria.</p>
    <table className="tab"><thead><tr><th>Categoria</th><th>Produtos</th><th>Margem bruta</th><th>Margem real (c/ perda)</th></tr></thead>
      <tbody>{Object.entries(cats).map(([c, l]) => <tr key={c}><td>{c}</td><td>{l.length}</td><td>{avg(l, 'bruta').toFixed(1)}%</td><td style={{ color: cor(avg(l, 'real')), fontWeight: 700 }}>{avg(l, 'real').toFixed(1)}%</td></tr>)}</tbody></table>
    <h3>Por produto</h3>
    <table className="tab"><thead><tr><th>Produto</th><th>Custo</th><th>Preço</th><th>Perda %</th><th>Margem real</th></tr></thead>
      <tbody>{lista.map(p => <tr key={p.id}><td>{p.nome}</td><td>{brl(p.custoMedio)}</td><td>{brl(p.precoVarejo)}</td><td>{p.taxaPerdaPct}</td><td style={{ color: cor(m(p).real), fontWeight: 700 }}>{m(p).real.toFixed(1)}%</td></tr>)}</tbody></table></Pagina>
}

export function Encartes() {
  const [lista] = useProdutos(); const [clientes] = useLista(R + '/clientes'); const [sel, setSel] = useState(null); const [titulo, setTitulo] = useState('Quarta da Feira')
  const marcados = sel ?? lista.filter(p => p.precoPromocional).map(p => p.id)
  const toggle = id => setSel(marcados.includes(id) ? marcados.filter(x => x !== id) : [...marcados, id])
  const linhas = lista.filter(p => marcados.includes(p.id)).map(p => `• ${p.nome}: ${brl(p.precoPromocional ?? p.precoVarejo)}/${p.unidade.toLowerCase()}`)
  const texto = `🍎🥬 *${titulo.toUpperCase()}* — Fruteira Conventos\n\n${linhas.join('\n')}\n\nOfertas enquanto durarem o estoque!`
  const link = tel => `https://wa.me/55${tel.replace(/\D/g, '')}?text=${encodeURIComponent(texto)}`
  return <Pagina titulo="📣 Encartes / WhatsApp">
    <div className="barra"><b>Título:</b><input value={titulo} onChange={e => setTitulo(e.target.value)} /><small>Por padrão vêm marcados os produtos em promoção.</small></div>
    <div style={{ display: 'grid', gridTemplateColumns: '1fr 1fr', gap: 16 }}>
      <div><h3>Produtos no encarte</h3>{lista.map(p => <label key={p.id} style={{ display: 'block' }}><input type="checkbox" checked={marcados.includes(p.id)} onChange={() => toggle(p.id)} /> {p.nome} — {brl(p.precoPromocional ?? p.precoVarejo)}</label>)}</div>
      <div><h3>Mensagem</h3><pre style={{ background: '#fff', padding: 12, borderRadius: 10, whiteSpace: 'pre-wrap' }}>{texto}</pre>
        <button className="btn sec" onClick={() => navigator.clipboard.writeText(texto)}>Copiar texto</button></div></div>
    <h3>Enviar para clientes ({clientes.filter(c => c.telefone).length})</h3>
    <table className="tab"><tbody>{clientes.filter(c => c.telefone).map(c => <tr key={c.id}><td>{c.nome}</td><td>{c.telefone}</td>
      <td><a className="btn" href={link(c.telefone)} target="_blank" rel="noreferrer">Abrir no WhatsApp</a></td></tr>)}</tbody></table>
    <p><small>O envio automático em massa exige a WhatsApp Business Cloud API (token da Meta); aqui cada mensagem abre pronta no WhatsApp para você enviar.</small></p></Pagina>
}

/* ---------- Etiquetas EAN-13 (peso/preço embutido) ---------- */
const L = ['0001101', '0011001', '0010011', '0111101', '0100011', '0110001', '0101111', '0111011', '0110111', '0001011']
const G = ['0100111', '0110011', '0011011', '0100001', '0011101', '0111001', '0000101', '0010001', '0001001', '0010111']
const Rr = ['1110010', '1100110', '1101100', '1000010', '1011100', '1001110', '1010000', '1000100', '1001000', '1110100']
const PAR = ['LLLLLL', 'LLGLGG', 'LLGGLG', 'LLGGGL', 'LGLLGG', 'LGGLLG', 'LGGGLL', 'LGLGLG', 'LGLGGL', 'LGGLGL']
const dv = s => (10 - [...s].reduce((t, c, i) => t + +c * (i % 2 ? 3 : 1), 0) % 10) % 10
const bits = e => { let b = '101'; for (let i = 1; i < 7; i++) b += (PAR[+e[0]][i - 1] === 'L' ? L : G)[+e[i]]; b += '01010'; for (let i = 7; i < 13; i++) b += Rr[+e[i]]; return b + '101' }
function Barras({ ean }) { const b = bits(ean)
  return <svg width={b.length * 2 + 20} height="78"><g transform="translate(10,0)">{[...b].map((x, i) => x === '1' && <rect key={i} x={i * 2} y="0" width="2" height="60" />)}
    <text x={b.length} y="74" fontSize="12" textAnchor="middle" fontFamily="monospace">{ean}</text></g></svg> }

export function Etiquetas() {
  const [lista] = useProdutos(); const [f, setF] = useState({ produtoId: '', qtd: '', lote: '', validade: '', nutri: 'Porção de 100 g: Valor energético ___ kcal | Carboidratos ___ g | Proteínas ___ g | Gorduras ___ g | Fibras ___ g | Sódio ___ mg' })
  const p = lista.find(x => x.id === +f.produtoId); const preco = p ? +(p.precoPromocional ?? p.precoVarejo) : 0
  const valor = p && f.qtd ? +(preco * f.qtd).toFixed(2) : 0
  const base = p && p.plu && valor > 0 && valor < 1000 ? '2' + String(p.plu).padStart(6, '0') + String(Math.round(valor * 100)).padStart(5, '0') : null
  const ean = base ? base + dv(base) : null
  return <Pagina titulo="🔖 Etiquetas e EAN-13">
    <div className="form wide" style={{ maxWidth: 560 }}>
      <label>Produto<SelProduto lista={lista} v={f.produtoId} onChange={v => setF({ ...f, produtoId: v })} /></label>
      <label>Peso (kg) ou quantidade<input type="number" value={f.qtd} onChange={e => setF({ ...f, qtd: e.target.value })} /></label>
      <label>Lote<input value={f.lote} onChange={e => setF({ ...f, lote: e.target.value })} /></label>
      <label>Validade<input type="date" value={f.validade} onChange={e => setF({ ...f, validade: e.target.value })} /></label>
      <label>Tabela nutricional<textarea rows="3" value={f.nutri} onChange={e => setF({ ...f, nutri: e.target.value })} /></label></div>
    {ean ? <><div className="area-print etiqueta"><b style={{ fontSize: 18 }}>FRUTEIRA CONVENTOS</b><div style={{ fontSize: 20 }}>{p.nome}</div>
      <div>{f.qtd} {p.unidade.toLowerCase()} × {brl(preco)} = <b style={{ fontSize: 22 }}>{brl(valor)}</b></div>
      <div>Lote: {f.lote || '—'} · Val.: {dataBR(f.validade)}</div><Barras ean={ean} /><small>{f.nutri}</small></div>
      <button className="btn" style={{ marginTop: 12 }} onClick={() => window.print()}>Imprimir etiqueta</button>
      <p><small>EAN-13 de peso variável: prefixo 2 + código (PLU, 6 díg.) + valor em centavos (5 díg.) + dígito verificador. O caixa deve estar configurado para decodificar esse padrão.</small></p></>
      : <p><small>Escolha um produto com PLU e informe a quantidade (valor total abaixo de R$ 1.000).</small></p>}</Pagina>
}

/* ---------- Autoatendimento e consulta ---------- */
export function SelfCheckout() {
  const [lista] = useProdutos(); const b = useBalanca(); const [it, setIt] = useState([]); const [pag, setPag] = useState(false); const [msg, setMsg] = useState('')
  const total = it.reduce((a, i) => a + i.sub, 0)
  const add = p => { const kg = p.unidade === 'KG'; if (kg && !(b.peso > 0)) return setMsg(`Coloque ${p.nome} na balança`)
    const q = kg ? b.peso : 1, pr = +(p.precoPromocional ?? p.precoVarejo); setIt([...it, { produtoId: p.id, nome: p.nome, q, pr, sub: +(q * pr).toFixed(2), kg }]); setMsg('') }
  const pagar = meio => post('/api/vendas/vendas', { atacado: false, itens: it.map(i => ({ produtoId: i.produtoId, quantidade: i.q, pesoBalanca: i.kg })), pagamentos: [{ meio, valor: total }] })
    .then(() => { setIt([]); setPag(false); setMsg('Obrigado! Compra finalizada ✔') }).catch(e => { setPag(false); setMsg(String(e)) })
  return <div className="pdv" style={{ gridTemplateColumns: '2fr 1fr' }}>
    <section className="main"><header className="topbar"><img src="/logo.png" alt="" /><h2 style={{ margin: 0 }}>Autoatendimento</h2>
      {!b.conectada && <button className="btn sec" onClick={() => b.conectar().catch(() => setMsg('Falha na balança'))}>Conectar balança</button>}<span className="peso">{b.peso.toFixed(3)} kg</span></header>
      {msg && <div className="msg" style={{ fontSize: 20 }}>{msg}</div>}
      <div className="grid">{lista.map(p => <button key={p.id} className="card" style={{ fontSize: 20, padding: 16 }} onClick={() => add(p)}><div className="emoji">🥬</div>{p.nome}<div className="preco">{brl(p.precoPromocional ?? p.precoVarejo)}/{p.unidade}</div></button>)}</div></section>
    <aside className="side"><h2>Sua compra</h2><ul>{it.map((i, k) => <li key={k}><span>{i.nome}<br /><small>{i.q.toFixed(3)} × {brl(i.pr)}</small></span><b>{brl(i.sub)}</b></li>)}</ul>
      <div className="total">{brl(total)}</div><button className="btn pagar" disabled={!it.length} onClick={() => setPag(true)}>Pagar</button></aside>
    {pag && <div className="modal"><div style={{ textAlign: 'center' }}><h2>Total {brl(total)}</h2>
      <QRCodeSVG value={`PIX-DEMO|valor=${total.toFixed(2)}`} size={220} fgColor="#066b43" /><p>Pague com PIX ou use a maquininha</p>
      <button className="btn" onClick={() => pagar('PIX')}>Já paguei (PIX)</button> <button className="btn sec" onClick={() => pagar('CREDITO')}>Cartão</button> <button className="btn sec" onClick={() => setPag(false)}>Voltar</button></div></div>}
  </div>
}

export function Consulta() {
  const [lista] = useProdutos(); const b = useBalanca(); const [p, setP] = useState(null); const [q, setQ] = useState('')
  const buscar = () => { const x = lista.find(i => String(i.plu) === q || i.codigoBarras === q || i.nome.toLowerCase().includes(q.toLowerCase())); setP(x || null) }
  const preco = p ? +(p.precoPromocional ?? p.precoVarejo) : 0
  return <Pagina titulo="🏷️ Consulta de preço">
    <div className="barra"><input placeholder="PLU, código de barras ou nome" value={q} onChange={e => setQ(e.target.value)} onKeyDown={e => e.key === 'Enter' && buscar()} />
      <button className="btn" onClick={buscar}>Buscar</button>{!b.conectada && <button className="btn sec" onClick={() => b.conectar()}>Conectar balança</button>}<b style={{ fontSize: 24, marginLeft: 'auto' }}>{b.peso.toFixed(3)} kg</b></div>
    <div className="grid" style={{ padding: 0, marginBottom: 16 }}>{lista.map(i => <button key={i.id} className="card" onClick={() => setP(i)}><div className="emoji">🥬</div>{i.nome}</button>)}</div>
    {p && <div className="vazio" style={{ margin: '0 auto' }}><h2>{p.nome}</h2><div style={{ fontSize: 22 }}>{brl(preco)}/{p.unidade}</div>
      {p.unidade === 'KG' && <div style={{ fontSize: 48, color: 'var(--verde-escuro)', fontWeight: 700 }}>{brl(preco * b.peso)}</div>}</div>}</Pagina>
}

/* ---------- Configurações ---------- */
function UsuariosLocal() {
  const [l, c] = useLista(R + '/usuarios'); const ini = { nome: '', login: '', perfil: 'operador', pin: '' }; const [f, s, setF] = useForm(ini); const [msg, setMsg] = useState('')
  const add = () => post(R + '/usuarios', f).then(() => { setF(ini); c(); setMsg('') }).catch(e => setMsg(String(e)))
  return <Pagina titulo="🔐 Usuários e perfis"><Aviso m={msg} />
    <div className="barra"><input placeholder="Nome" value={f.nome} onChange={s('nome')} /><input placeholder="Login" value={f.login} onChange={s('login')} />
      <select value={f.perfil} onChange={s('perfil')}><option value="operador">Operador</option><option value="supervisor">Supervisor</option><option value="gerente">Gerente</option></select>
      <input type="password" placeholder="Senha (mín. 4)" value={f.pin} onChange={s('pin')} /><button className="btn" disabled={!f.nome || !f.login} onClick={add}>Criar</button></div>
    <table className="tab"><thead><tr><th>Nome</th><th>Login</th><th>Perfil</th><th>Situação</th><th></th></tr></thead>
      <tbody>{l.map(u => <tr key={u.id}><td>{u.nome}</td><td>{u.login}</td><td>{u.perfil}</td><td>{u.ativo ? 'Ativo' : <span className="classe off">Desativado</span>}</td>
        <td><button className="btn sec" onClick={() => post(`${R}/usuarios/${u.id}/ativo?valor=${!u.ativo}`).then(c)}>{u.ativo ? 'Desativar' : 'Reativar'}</button>{' '}
          <button className="btn sec" onClick={() => { const n = window.prompt(`Nova senha para ${u.login} (mín. 4 caracteres):`); if (n) post(`${R}/usuarios/${u.id}/senha?nova=${encodeURIComponent(n)}`).then(() => setMsg('Senha alterada ✔')).catch(e => setMsg(String(e))) }}>Trocar senha</button></td></tr>)}</tbody></table>
    <p><small>Senhas guardadas com hash (SHA-256). Em produção, use Keycloak/OIDC e valide o perfil também nas APIs.</small></p></Pagina>
}

export function Perifericos() {
  const [f, setF] = useState({ baud: 9600, tef: 'nenhum', impressora: 'Térmica 80mm (USB)', gaveta: 'sim', lat: '', lon: '', ...cfg() }); const [ok, setOk] = useState(false)
  const s = k => e => { setF({ ...f, [k]: e.target.value }); setOk(false) }
  return <Pagina titulo="⚙️ Balança e periféricos">
    <div className="form wide" style={{ maxWidth: 520 }}>
      <label>Balança — baud rate (porta serial)<select value={f.baud} onChange={s('baud')}>{[2400, 4800, 9600, 19200].map(x => <option key={x}>{x}</option>)}</select></label>
      <label>TEF / maquininha<select value={f.tef} onChange={s('tef')}><option value="nenhum">Manual (sem integração)</option><option value="paygo">PayGo</option><option value="sitef">SiTef</option></select></label>
      <label>Impressora<input value={f.impressora} onChange={s('impressora')} /></label>
      <label>Gaveta de dinheiro<select value={f.gaveta} onChange={s('gaveta')}><option value="sim">Abrir ao receber dinheiro</option><option value="nao">Não usa</option></select></label>
      <label>Latitude da loja (previsão do tempo)<input value={f.lat} onChange={s('lat')} placeholder="-30.0346" /></label>
      <label>Longitude da loja<input value={f.lon} onChange={s('lon')} placeholder="-51.2177" /></label>
      <button className="btn" onClick={() => { localStorage.setItem('cfg', JSON.stringify(f)); setOk(true) }}>Salvar neste computador</button>{ok && <span>Salvo ✔</span>}
      <small>O TEF fica registrado como configuração; a integração com o SDK da adquirente é feita no agente local do PDV.</small></div></Pagina>
}

function UsuariosKeycloak() {
  return <Pagina titulo="🔐 Usuários e perfis">
    <div className="vazio" style={{ textAlign: 'left' }}><p>Em <b>produção</b> os usuários, senhas e perfis são gerenciados no <b>Keycloak</b>.</p>
      <p>Perfis (realm roles): <b>gerente</b>, <b>supervisor</b>, <b>operador</b>.</p>
      <a className="btn" target="_blank" rel="noreferrer" href={`${KC.url}/admin/master/console/#/${KC.realm}/users`}>Abrir console do Keycloak</a></div></Pagina>
}
export const Usuarios = () => PROD ? <UsuariosKeycloak /> : <UsuariosLocal />
