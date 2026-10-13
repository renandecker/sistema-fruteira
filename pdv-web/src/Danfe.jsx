import React, { useEffect, useState } from 'react'
import { QRCodeSVG } from 'qrcode.react'
import { brl } from './lib.jsx'
import { mascaraCpf, statusCpf } from './cpf.js'
import { situacaoNota, emitente } from './nfce.js'

const grupos = ch => (ch || '').replace(/\s/g, '').replace(/(\d{4})(?=\d)/g, '$1 ')
const qtd = (q, un) => un === 'KG' ? Number(q).toFixed(3) : Number(q)

/** DANFE NFC-e (cupom 80 mm): itens, total, pagamento, CPF do consumidor, chave, protocolo e QR Code. */
export function Danfe({ nota, itens = [], pagamentos = [], produtos = [] }) {
  const emi = emitente(); const un = id => produtos.find(p => p.id === id)?.unidade
  const cont = nota.status === 'CONTINGENCIA'; const sim = nota.tpAmb === 0 || nota.protocolo?.startsWith('SIM'); const homolog = nota.tpAmb === 2
  const total = Number(nota.valor ?? itens.reduce((t, i) => t + Number(i.subtotal), 0))
  return <div className="area-print danfe" data-testid="danfe">
    <b>{emi.nome}</b>{emi.cnpj && <div>CNPJ {emi.cnpj}</div>}
    <div className="danfe-t">DANFE NFC-e — Documento Auxiliar da Nota Fiscal de Consumidor Eletrônica</div>
    {(homolog || sim) && <div className="danfe-aviso">{sim ? 'SIMULADO — SEM VALOR FISCAL' : 'EMITIDA EM AMBIENTE DE HOMOLOGAÇÃO — SEM VALOR FISCAL'}</div>}
    {cont && <div className="danfe-aviso">EMITIDA EM CONTINGÊNCIA — PENDENTE DE AUTORIZAÇÃO</div>}
    <table><thead><tr><th>Item</th><th>Qtd</th><th>Unit.</th><th>Total</th></tr></thead>
      <tbody>{itens.map((i, k) => <tr key={k}><td>{i.nome}</td><td>{qtd(i.quantidade, un(i.produtoId))}</td><td>{brl(i.precoUnit)}</td><td>{brl(i.subtotal)}</td></tr>)}</tbody></table>
    <div className="danfe-l"><span>Qtde. total de itens</span><span>{itens.length}</span></div>
    <div className="danfe-l"><b>VALOR TOTAL</b><b>{brl(total)}</b></div>
    {pagamentos.map((p, k) => <div className="danfe-l" key={k}><span>{p.meio?.replace('_', ' ')}</span><span>{brl(p.valor)}</span></div>)}
    <hr />
    <div>NFC-e nº {nota.numero} · Série 1 · {new Date(nota.data).toLocaleString('pt-BR')}</div>
    <div className="danfe-ch">Chave de acesso<br />{grupos(nota.chave)}</div>
    {nota.protocolo && !sim && <div>Protocolo de autorização: {nota.protocolo}{nota.dhAutorizacao ? ` · ${new Date(nota.dhAutorizacao).toLocaleString('pt-BR')}` : ''}</div>}
    <div><b>{nota.cpf ? `CONSUMIDOR — CPF ${mascaraCpf(nota.cpf)}` : 'CONSUMIDOR NÃO IDENTIFICADO'}</b></div>
    {nota.cpf && <div><small>Nota Fiscal Gaúcha: o CPF informado participa do programa.</small></div>}
    {nota.qrCode && <div className="danfe-qr"><QRCodeSVG value={nota.qrCode} size={150} /></div>}
    {nota.urlChave && <div><small>Consulte pela chave de acesso em {nota.urlChave}</small></div>}
  </div>
}

/** Resultado da NFC-e logo após a venda: confirma, imprime, e deixa corrigir o CPF rejeitado sem travar o caixa. */
export function NfceAviso({ nota, itens, pagamentos, produtos, onCorrigir, onFechar }) {
  const [cpf, setCpf] = useState(mascaraCpf(nota.cpf || '')); const [erro, setErro] = useState(''); const [env, setEnv] = useState(false); const [imprimindo, setImprimindo] = useState(false)
  const sit = situacaoNota(nota); const st = statusCpf(cpf)
  useEffect(() => { if (!imprimindo) return; const t = setTimeout(() => { try { window.print() } finally { setImprimindo(false) } }, 50); return () => clearTimeout(t) }, [imprimindo])
  const reenviar = async (semCpf) => {
    if (!semCpf && st === 'incompleto' || st === 'invalido') return setErro('CPF inválido: confira os números.')
    setEnv(true); setErro('')
    try { await onCorrigir(semCpf ? '' : cpf) } catch (e) { setErro(String(e)) } finally { setEnv(false) }
  }
  return <div className="modal" role="dialog" aria-label="Resultado da NFC-e"><div className="nfce-box">
    {sit === 'ok' && <><h2>✔ NFC-e autorizada</h2>
      <p>Nº {nota.numero} · {nota.cStat} - {nota.xMotivo}{nota.cpf ? <><br />CPF na nota: <b>{mascaraCpf(nota.cpf)}</b> (Nota Fiscal Gaúcha)</> : <><br />Consumidor não identificado.</>}</p></>}
    {sit === 'contingencia' && <><h2>⚠ NFC-e em contingência</h2>
      <p>Sem comunicação com a SEFAZ-RS. A nota foi emitida offline (tpEmis 9) e será transmitida depois, em NFC-e › Transmitir. Imprima o DANFE com o QR Code de contingência.</p></>}
    {sit === 'corrigir-cpf' && <><h2>✖ SEFAZ rejeitou o CPF</h2>
      <p><b>{nota.cStat}</b> — {nota.xMotivo}</p>
      <p>A venda está concluída. Corrija o CPF do cliente e reenvie, ou emita sem CPF.</p>
      <input autoFocus aria-label="CPF corrigido" inputMode="numeric" placeholder="000.000.000-00" value={cpf} onChange={e => { setCpf(mascaraCpf(e.target.value)); setErro('') }} className={st === 'invalido' ? 'cpf-invalido' : ''} />
      <div className="botoes"><button className="btn" disabled={env || st !== 'ok'} onClick={() => reenviar(false)}>Reenviar com este CPF</button>
        <button className="btn sec" disabled={env} onClick={() => reenviar(true)}>Emitir sem CPF</button></div></>}
    {sit === 'rejeitada' && <><h2>✖ NFC-e {nota.status === 'DENEGADA' ? 'denegada' : 'rejeitada'}</h2><p><b>{nota.cStat}</b> — {nota.xMotivo}</p><p>A venda está concluída. Veja o motivo em NFC-e e reemita depois.</p></>}
    {erro && <p className="erro">{erro}</p>}
    <div className="botoes">{(sit === 'ok' || sit === 'contingencia') && <button className="btn" onClick={() => setImprimindo(true)}>Imprimir DANFE</button>}
      <button className="btn sec" onClick={onFechar}>{sit === 'corrigir-cpf' ? 'Resolver depois' : 'Fechar'}</button></div>
    {imprimindo && <Danfe nota={nota} itens={itens} pagamentos={pagamentos} produtos={produtos} />}
  </div></div>
}
