import React, { useEffect, useState } from 'react'
import { api, brl, useLista, useProdutos, Pagina, Aviso } from './lib.jsx'
import { montarNfe, conferirNfe, emitirNfe, acaoNfe } from './nfe.js'
import { mascaraDoc, problemasCliente } from './fiscal.js'
import CertificadoSefaz from './CertificadoSefaz.jsx'

const R = '/api/retaguarda'
const COR = { AUTORIZADA: 'var(--verde)', PENDENTE: '#e0a100', CANCELADA: 'var(--vermelho)', REJEITADA: 'var(--vermelho)', DENEGADA: 'var(--vermelho)' }

/** DANFE simplificado da NF-e (retrato, A4) para impressão; vale a consulta da chave de acesso no portal da SEFAZ. */
function DanfeNfe({ nota, venda, empresa }) {
  const itens = venda?.itens || []
  return <div className="danfe-a4" aria-label="DANFE NF-e">
    <h2>DANFE — Documento Auxiliar da NF-e (simplificado)</h2>
    <div className="danfe-aviso">NF-e nº {nota.numero} · série {nota.serie ?? 1} · {nota.status === 'AUTORIZADA' ? 'AUTORIZADA' : nota.status}{nota.provider === 'SIMULADOR' ? ' — SIMULADA, SEM VALOR FISCAL' : ''}</div>
    <p><b>Chave de acesso:</b> <span style={{ wordBreak: 'break-all' }}>{nota.chave}</span></p>
    <p><b>Protocolo de autorização:</b> {nota.protocolo || '—'} {nota.dhAutorizacao ? `em ${new Date(nota.dhAutorizacao).toLocaleString('pt-BR')}` : ''}</p>
    <p><b>Destinatário:</b> {empresa?.nome || nota.nome} — {mascaraDoc(nota.cpf)}{empresa?.ie ? ` · IE ${empresa.ie}` : ''}<br />
      {empresa ? `${empresa.logradouro || ''}, ${empresa.numero || ''} — ${empresa.bairro || ''} — ${empresa.municipio || ''}/${empresa.uf || ''} — CEP ${empresa.cep || ''}` : ''}</p>
    <table><thead><tr><th>Item</th><th>Qtd</th><th>Unitário</th><th>Total</th></tr></thead>
      <tbody>{itens.map((i, k) => <tr key={k}><td>{i.nome}</td><td>{i.quantidade}</td><td>{brl(i.precoUnit)}</td><td>{brl(i.subtotal)}</td></tr>)}</tbody></table>
    <p><b>Valor total da nota: {brl(nota.valor)}</b></p>
    <small>Consulte a autenticidade pela chave de acesso no portal da SEFAZ. DANFE simplificado, sem código de barras.</small>
  </div>
}

export default function Nfe() {
  const [notas, recNotas] = useLista(R + '/nfe'); const [nfces] = useLista(R + '/nfce'); const [vendas] = useLista('/api/vendas/vendas?limit=30'); const [produtos] = useProdutos()
  const [clientes] = useLista(R + '/clientes'); const [st, setSt] = useState(null); const [msg, setMsg] = useState(''); const [probs, setProbs] = useState(null); const [imp, setImp] = useState(null)
  const ini = { vendaId: '', empresaDoc: '', aPrazo: false, parcelas: 1, modFrete: 9, vFrete: '', vDesc: '', natOp: '', infCpl: '', consumidorFinal: false }
  const [f, setF] = useState(ini); const set = k => e => setF({ ...f, [k]: e.target.type === 'checkbox' ? e.target.checked : e.target.value })
  useEffect(() => { api(R + '/nfe/status').then(setSt).catch(() => {}) }, [])
  const comNota = new Set([...notas, ...nfces].filter(n => ['AUTORIZADA', 'PENDENTE', 'CONTINGENCIA'].includes(n.status)).map(n => n.vendaId))
  const disponiveis = vendas.filter(v => v.status === 'PAGA' && !comNota.has(v.id))
  const empresas = clientes.filter(c => String(c.cpf || '').length === 14 && c.ativo !== false)
  const empresa = empresas.find(c => c.cpf === f.empresaDoc)
  const erro = e => setMsg(String(e?.message || e) || 'Operação não permitida')
  const corpo = async () => { const venda = await api(`/api/vendas/vendas/${f.vendaId}`); return montarNfe(venda, produtos, f) }
  const conferir = async () => { try { setMsg(''); const r = await conferirNfe(await corpo()); setProbs(r.ok ? [] : r.problemas) } catch (e) { erro(e) } }
  const emitir = async () => { try { setMsg(''); setProbs(null); const n = await emitirNfe(await corpo()); setMsg(`NF-e nº ${n.numero}: ${n.status}${n.cStat ? ` (${n.cStat} - ${n.xMotivo})` : ''}`); setF(ini); recNotas() } catch (e) { erro(e) } }
  const agir = (n, acao, corpoAcao, ok) => acaoNfe(n.id, acao, corpoAcao).then(() => { setMsg(ok); recNotas() }).catch(erro)
  const cancelar = n => { const j = window.prompt('Justificativa do cancelamento (mínimo 15 caracteres):'); if (j) agir(n, 'cancelar', { justificativa: j }, `NF-e nº ${n.numero} cancelada`) }
  const carta = n => { const t = window.prompt('Texto da carta de correção (15 a 1000 caracteres). Não corrige valor, imposto, quantidade, destinatário nem datas:'); if (t) agir(n, 'carta-correcao', { texto: t }, `Carta de correção registrada na NF-e nº ${n.numero}`) }
  const imprimir = async n => { try { const venda = n.vendaId ? await api(`/api/vendas/vendas/${n.vendaId}`) : null; setImp({ nota: n, venda, empresa: clientes.find(c => c.cpf === n.cpf) }); setTimeout(() => window.print(), 80) } catch (e) { erro(e) } }
  const pronto = f.vendaId && f.empresaDoc

  return <Pagina titulo="🏢 NF-e — venda a empresa"><Aviso m={msg} />
    <div className="msg" style={{ margin: '0 0 12px' }}>Use a <b>NF-e</b> quando a empresa (CNPJ) exigir nota fiscal eletrônica de contribuinte. Venda a consumidor final continua na NFC-e. A empresa precisa estar em <b>Cadastros → Clientes</b> com CNPJ, Inscrição Estadual e endereço completo.</div>
    {st && !st.real && <div className="msg" style={{ margin: '0 0 12px' }}>⚠ Emissão <b>simulada</b> (FISCAL_PROVIDER=simulador): chave e protocolo fictícios, sem valor fiscal.</div>}
    {st?.real && <div className="msg" style={{ margin: '0 0 12px' }}>SEFAZ-RS · ambiente <b>{st.ambiente}</b>{st.pendencias?.length > 0 && <> · ⚠ faltam: {st.pendencias.join(', ')}</>}</div>}
    <CertificadoSefaz st={st} base={R + '/nfe'} />
    {st?.problemasEmitente?.length > 0 && <div className="conf-fiscal" role="status"><b>⚠ Emitente (loja) incompleto, no .env:</b><ul>{st.problemasEmitente.map(x => <li key={x}>{x}</li>)}</ul></div>}
    <h3>Emitir NF-e</h3>
    <div className="form">
      <label>Venda (paga, sem nota)<select aria-label="Venda" value={f.vendaId} onChange={set('vendaId')}><option value="">Escolha…</option>{disponiveis.map(v => <option key={v.id} value={v.id}>#{v.id} · {new Date(v.data).toLocaleString('pt-BR')} · {brl(v.total)}</option>)}</select></label>
      <label>Empresa (cliente com CNPJ)<select aria-label="Empresa" value={f.empresaDoc} onChange={set('empresaDoc')}><option value="">Escolha…</option>{empresas.map(c => <option key={c.id} value={c.cpf}>{c.nome} · {mascaraDoc(c.cpf)}</option>)}</select></label>
      {empresa && problemasCliente(empresa).length > 0 && <small className="erro" role="alert">Cadastro da empresa incompleto: {problemasCliente(empresa).join(' · ')} (corrija em Clientes)</small>}
      {empresas.length === 0 && <small>Nenhuma empresa cadastrada: cadastre o cliente com CNPJ em Cadastros → Clientes.</small>}
      <label><input type="checkbox" checked={f.consumidorFinal} onChange={set('consumidorFinal')} /> A empresa usa a mercadoria (consumidor final, não revenda)</label>
      <label>Pagamento<select aria-label="Pagamento" value={f.aPrazo ? 'prazo' : 'vista'} onChange={e => setF({ ...f, aPrazo: e.target.value === 'prazo' })}><option value="vista">À vista (como foi pago na venda)</option><option value="prazo">A prazo (boleto / duplicatas)</option></select></label>
      {f.aPrazo && <label>Parcelas (a cada 30 dias)<input aria-label="Parcelas" type="number" min="1" max="12" value={f.parcelas} onChange={set('parcelas')} /></label>}
      <label>Frete<select aria-label="Frete" value={f.modFrete} onChange={set('modFrete')}><option value={9}>Sem frete</option><option value={0}>Por conta do emitente (CIF)</option><option value={1}>Por conta do destinatário (FOB)</option><option value={2}>Por conta de terceiros</option></select></label>
      {Number(f.modFrete) !== 9 && <label>Valor do frete (R$)<input aria-label="Valor do frete" inputMode="decimal" value={f.vFrete} onChange={set('vFrete')} /></label>}
      <label>Desconto (R$)<input aria-label="Desconto" inputMode="decimal" value={f.vDesc} onChange={set('vDesc')} /></label>
      <label>Informações complementares<input aria-label="Informações complementares" maxLength={500} value={f.infCpl} onChange={set('infCpl')} placeholder="Ex.: pedido 123" /></label>
      <div className="botoes"><button className="btn sec" disabled={!pronto} onClick={conferir}>Conferir dados</button><button className="btn" disabled={!pronto} onClick={emitir}>Emitir NF-e</button></div>
    </div>
    {probs && <div className={`conf-fiscal ${probs.length ? '' : 'ok'}`} role="status" aria-label="Conferência da NF-e"><b>{probs.length ? '⚠ Corrija antes de emitir:' : '✔ Dados obrigatórios completos'}</b>{probs.length > 0 && <ul>{probs.map(x => <li key={x}>{x}</li>)}</ul>}</div>}
    <h3>NF-e emitidas</h3>
    <table className="tab"><thead><tr><th>Nº</th><th>Venda</th><th>Empresa</th><th>Chave</th><th>Valor</th><th>Status / retorno SEFAZ</th><th></th></tr></thead>
      <tbody>{notas.map(n => <tr key={n.id}><td>{n.numero}</td><td>{n.vendaId ? `#${n.vendaId}` : '—'}</td><td>{n.nome}<small style={{ display: 'block' }}>{mascaraDoc(n.cpf)}</small></td><td style={{ fontSize: 11 }}>{n.chave}</td><td>{brl(n.valor)}</td>
        <td><span className="classe" style={{ background: COR[n.status] }}>{n.status}</span>{n.cStat ? <small style={{ display: 'block' }}>{n.cStat} - {n.xMotivo}</small> : null}{n.seqCce ? <small style={{ display: 'block' }}>{n.seqCce} carta(s) de correção</small> : null}</td>
        <td>{n.status === 'PENDENTE' && <button className="btn" onClick={() => agir(n, 'transmitir', null, `NF-e nº ${n.numero} transmitida`)}>Transmitir</button>}
          {n.status === 'REJEITADA' && <button className="btn" title="Depois de corrigir o cadastro ou o .env" onClick={() => agir(n, 'reemitir', null, `NF-e nº ${n.numero} reenviada`)}>Reemitir</button>}
          {n.status === 'AUTORIZADA' && <><button className="btn sec" onClick={() => imprimir(n)}>DANFE</button><button className="btn sec" onClick={() => carta(n)}>Carta de correção</button><button className="btn sec" onClick={() => cancelar(n)}>Cancelar</button></>}
          {st?.real && n.xml !== undefined && <a className="btn sec" href={`${R}/nfe/${n.id}/xml`}>XML</a>}</td></tr>)}</tbody></table>
    {imp && <DanfeNfe nota={imp.nota} venda={imp.venda} empresa={imp.empresa} />}</Pagina>
}
