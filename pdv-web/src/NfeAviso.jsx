import React, { useEffect, useState } from 'react'
import { DanfeNfe } from './Nfe.jsx'
import { mascaraDoc } from './fiscal.js'

/**
 * Resultado da NF-e (venda a empresa) logo após a venda. A venda já está gravada: aqui o caixa vê se a nota saiu, o que falta no cadastro
 * da empresa, ou pode tentar de novo. Nunca trava o caixa ("Resolver depois" → NF-e — venda a empresa).
 */
export default function NfeAviso({ aviso, onTentar, onFechar }) {
  const { venda, doc, empresa, nota, problemas, erro } = aviso
  const [imprimindo, setImprimindo] = useState(false); const [env, setEnv] = useState(false)
  useEffect(() => { if (!imprimindo) return; const t = setTimeout(() => { try { window.print() } finally { setImprimindo(false) } }, 50); return () => clearTimeout(t) }, [imprimindo])
  const tentar = async () => { setEnv(true); try { await onTentar() } finally { setEnv(false) } }
  const ok = nota?.status === 'AUTORIZADA'
  return <div className="modal" role="dialog" aria-label="Resultado da NF-e"><div className="nfce-box">
    <h2>{ok ? '✔ NF-e autorizada' : nota ? (nota.status === 'PENDENTE' ? '⚠ NF-e pendente' : '✖ NF-e ' + String(nota.status || '').toLowerCase()) : '⚠ NF-e não emitida'}</h2>
    <p>Venda #{venda?.id ?? '—'} para {empresa?.nome || 'a empresa'} · CNPJ {mascaraDoc(doc)}<br /><small>Venda para empresa sai em NF-e (modelo 55), não em NFC-e. A venda já está concluída.</small></p>
    {nota && <p>NF-e nº {nota.numero}{nota.cStat ? ` · ${nota.cStat} - ${nota.xMotivo}` : ''}{nota.status === 'PENDENTE' ? <><br />Sem comunicação com a SEFAZ: a nota fica pendente e é transmitida depois em NF-e › Transmitir.</> : null}
      {nota.status === 'REJEITADA' && <><br />Corrija o motivo (cadastro da empresa ou .env) e use Reemitir em NF-e — venda a empresa.</>}</p>}
    {problemas?.length > 0 && <><p><b>Corrija antes de emitir:</b></p><ul>{problemas.map(x => <li key={x}>{x}</li>)}</ul>
      <p><small>Ajuste o cadastro da empresa em Cadastros → Clientes (razão social, endereço e IE) e tente de novo.</small></p></>}
    {erro && <p className="erro">{erro}</p>}
    <div className="botoes">
      {(problemas?.length > 0 || erro) && <button className="btn" disabled={env} onClick={tentar}>{env ? 'Tentando…' : 'Tentar de novo'}</button>}
      {ok && <button className="btn" onClick={() => setImprimindo(true)}>Imprimir DANFE (A4)</button>}
      <button className="btn sec" onClick={onFechar}>{ok ? 'Fechar' : 'Resolver depois'}</button></div>
    {imprimindo && nota && <DanfeNfe nota={nota} venda={venda} empresa={empresa} />}
  </div></div>
}
