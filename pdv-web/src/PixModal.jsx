import React, { useEffect, useRef, useState } from 'react'
import { QRCodeSVG } from 'qrcode.react'
import { brl } from './lib.jsx'
import { criarCobranca, consultarCobranca, cancelarCobranca, simularPagamento, intervaloPix } from './pix.js'

const esperar = ms => new Promise(r => setTimeout(r, ms))
const mmss = s => `${String(Math.floor(s / 60)).padStart(2, '0')}:${String(s % 60).padStart(2, '0')}`

/** Cobrança Pix: gera o QR Code no PSP (via servidor), mostra o Pix Copia e Cola e aguarda a confirmação (webhook → servidor → polling). */
export default function PixModal({ valor, onPago, onFechar }) {
  const [cob, setCob] = useState(null); const [erro, setErro] = useState(''); const [copiado, setCopiado] = useState(false)
  const [resta, setResta] = useState(0); const [tentativa, setTentativa] = useState(0)
  const ref = useRef({ vivo: true, id: null, pago: false })

  useEffect(() => {
    ref.current = { vivo: true, id: null, pago: false }; setErro(''); setCob(null); setCopiado(false)
    ;(async () => {
      try {
        let c = await criarCobranca(valor, 'Venda no caixa')
        ref.current.id = c.id; setCob(c); setResta(c.segundosRestantes)
        while (ref.current.vivo) {
          await esperar(intervaloPix())
          if (!ref.current.vivo) return
          c = await consultarCobranca(c.id); setCob(c); setResta(c.segundosRestantes)
          if (c.status === 'CONCLUIDA') { ref.current.pago = true; onPago({ pixId: c.id, txid: c.txid, endToEndId: c.endToEndId, valor: c.valorPago }); return }
          if (c.status !== 'ATIVA') return                      // EXPIRADA, CANCELADA, DIVERGENTE...
        }
      } catch (e) { if (ref.current.vivo) setErro(String(e.message || e) || 'Não foi possível gerar o Pix') }
    })()
    return () => { ref.current.vivo = false }
  }, [tentativa])

  useEffect(() => { const t = setInterval(() => setResta(r => Math.max(0, r - 1)), 1000); return () => clearInterval(t) }, [])

  const copiar = async () => {
    try { await navigator.clipboard.writeText(cob.pixCopiaECola); setCopiado(true); setTimeout(() => setCopiado(false), 2000) }
    catch { setErro('Não foi possível copiar automaticamente: selecione o código e copie com Ctrl+C.') }
  }
  const cancelar = async () => { if (ref.current.id && !ref.current.pago) await cancelarCobranca(ref.current.id).catch(() => {}); onFechar() }
  const ativa = cob?.status === 'ATIVA'
  const fim = cob && cob.status !== 'ATIVA' && cob.status !== 'CONCLUIDA'

  return (
    <div className="modal tef" role="dialog" aria-label="Pagamento com Pix"><div className="tef-box" style={{ maxWidth: 460 }}>
      <h2>💠 Pix — {brl(valor)}</h2>
      {!cob && !erro && <div className="tef-msg" role="status">Gerando o QR Code…</div>}
      {ativa && <>
        <div style={{ background: '#fff', padding: 10, display: 'inline-block', border: '2px solid var(--borda)', borderRadius: 12 }}><QRCodeSVG value={cob.pixCopiaECola} size={210} fgColor="#066b43" /></div>
        <div className="tef-msg" role="status" style={{ fontSize: 20 }}>Aguardando o pagamento… <b>{mmss(resta)}</b></div>
        {cob.provedor === 'simulador' && <div style={{ margin: '4px 0' }}><span className="classe" style={{ background: '#e0a100' }}>SIMULADOR — não é um Pix real</span>{' '}
          <button className="btn sec" onClick={() => simularPagamento(cob.id).catch(e => setErro(String(e.message || e)))}>Simular pagamento do cliente</button></div>}
        <textarea readOnly aria-label="Pix Copia e Cola" value={cob.pixCopiaECola} rows={3} style={{ width: '100%', fontSize: 11, fontFamily: 'monospace' }} onFocus={e => e.target.select()} />
        <div><button className="btn" onClick={copiar}>{copiado ? '✔ Copiado!' : 'Copiar Pix Copia e Cola'}</button></div></>}
      {fim && <div className="tef-erro" role="alert">{cob.status === 'EXPIRADA' ? 'O Pix expirou sem pagamento.' : cob.status === 'DIVERGENTE' ? 'Pix recebido com valor diferente do cobrado: procure o supervisor (devolução).' : `Cobrança ${cob.status.toLowerCase()}.`}</div>}
      {erro && <div className="tef-erro" role="alert">{erro}</div>}
      <div style={{ marginTop: 14 }}>
        {(fim || erro) && <button className="btn" onClick={() => setTentativa(t => t + 1)}>Gerar novo Pix</button>}{' '}
        <button className="btn sec" onClick={cancelar}>{ativa ? 'Cancelar cobrança' : 'Fechar'}</button>
      </div>
    </div></div>)
}
