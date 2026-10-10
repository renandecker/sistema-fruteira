import React, { useEffect, useRef, useState } from 'react'
import { post, brl, cfg } from './lib.jsx'
import { agente, tipoTef } from './tef.js'

const FINAIS = ['APROVADA', 'NEGADA', 'CANCELADA', 'ERRO']
const PASSOS = [['AGUARDANDO_CARTAO', '1 Cartão'], ['AGUARDANDO_SENHA', '2 Senha'], ['PROCESSANDO', '3 Processando']]
const esperar = ms => new Promise(r => setTimeout(r, ms))

/** Cobrança no cartão: inicia no servidor, dispara o agente, mostra as mensagens do pinpad e devolve o resultado. */
export default function TefModal({ valor, meio, parcelas, produtoIds = [], onAprovada, onManual, onFechar }) {
  const [e, setE] = useState({ estado: 'INICIANDO', mensagem: 'Conectando ao agente TEF…' })
  const [erro, setErro] = useState(null)          // { texto, repetir, manual }
  const [tentativa, setTentativa] = useState(0)
  const ref = useRef({ requisicao: null, vivo: true })

  async function registrar(tx, r) {              // grava o resultado no servidor (3 tentativas); se falhar, desfaz a aprovação
    for (let i = 0; i < 3; i++) {
      try { await post(`/api/vendas/tef/${tx.id}/resultado`, r); return }
      catch (err) {
        if (i < 2) { await esperar(800); continue }
        if (r.status === 'APROVADA') { await agente.desfazer(tx.requisicao).catch(() => {}); throw new Error('Cartão aprovado, mas o servidor não registrou a transação. A aprovação foi desfeita; tente novamente.') }
        throw err
      }
    }
  }

  useEffect(() => {
    ref.current.vivo = true; setErro(null); setE({ estado: 'INICIANDO', mensagem: 'Conectando ao agente TEF…' })
    ;(async () => {
      let tx
      try {
        tx = await post('/api/vendas/tef/iniciar', { valor, tipo: tipoTef(meio, parcelas), parcelas: meio === 'CREDITO' ? parcelas : 1, terminal: cfg().tefTerminal || 'PDV1', produtoIds })
      } catch (err) { if (ref.current.vivo) setErro({ texto: 'Não foi possível registrar a transação no servidor: ' + (String(err.message || err) || 'sem conexão'), manual: true }); return }
      ref.current.requisicao = tx.requisicao
      try {
        await agente.iniciar({ requisicao: tx.requisicao, valor, tipo: tx.tipo, parcelas: tx.parcelas, operador: '' })
        let ult
        while (ref.current.vivo) {
          ult = await agente.consultar(tx.requisicao); setE(ult)
          if (FINAIS.includes(ult.estado)) break
          await esperar(500)
        }
        if (!ref.current.vivo) return
        const r = ult.resultado || { status: ult.estado, mensagem: ult.mensagem }
        await registrar(tx, r)
        if (r.status === 'APROVADA') onAprovada({ tefId: tx.id, requisicao: tx.requisicao, resultado: r })
        else setErro({ texto: r.mensagem || r.status, repetir: true })
      } catch (err) { if (ref.current.vivo) setErro({ texto: String(err.message || err), manual: !!err.semAgente, repetir: true }) }
    })()
    return () => { ref.current.vivo = false }
  }, [tentativa])

  const andamento = !erro && !FINAIS.includes(e.estado)
  const titulo = meio === 'DEBITO' ? 'Débito' : parcelas > 1 ? `Crédito em ${parcelas}x` : 'Crédito à vista'
  return (
    <div className="modal tef" role="dialog" aria-label="Pagamento com cartão"><div className="tef-box">
      <h2>💳 {titulo} — {brl(valor)}</h2>
      {!erro && <>
        <div className="tef-msg" role="status">{e.mensagem}</div>
        <div className="tef-passos">{PASSOS.map(([k, t]) => <span key={k} className={e.estado === k ? 'on' : ''}>{t}</span>)}</div>
        <small>Siga as instruções no pinpad. Não retire o cartão antes de terminar.</small></>}
      {erro && <div className="tef-erro" role="alert">{erro.texto}</div>}
      <div style={{ marginTop: 14 }}>
        {andamento && <button className="btn sec" onClick={() => ref.current.requisicao && agente.cancelar(ref.current.requisicao).catch(() => {})}>Cancelar operação</button>}{' '}
        {erro?.repetir && <button className="btn" onClick={() => setTentativa(t => t + 1)}>Tentar novamente</button>}{' '}
        {erro?.manual && <button className="btn sec" onClick={onManual}>Usar maquininha manual</button>}{' '}
        {erro && <button className="btn sec" onClick={onFechar}>Fechar</button>}
      </div>
    </div></div>)
}
