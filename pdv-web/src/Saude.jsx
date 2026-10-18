import React, { useEffect, useState } from 'react'
import { api, post, useLista, Pagina, Aviso } from './lib.jsx'
import { ICONE, ROTULO, horaCurta } from './saude.js'

const R = '/api/vendas/saude/pagamentos'
const quando = iso => { try { return new Date(iso).toLocaleString('pt-BR') } catch { return iso } }

export default function Saude() {
  const [est, setEst] = useState(null); const [msg, setMsg] = useState(''); const [rodando, setRodando] = useState(false)
  const carregar = () => api(R).then(setEst).catch(e => setMsg(String(e.message || e)))
  useEffect(() => { carregar() }, [])
  const verificar = () => { setRodando(true); setMsg(''); post(`${R}/verificar`).then(carregar).catch(e => setMsg(String(e.message || e) || 'Só supervisor ou gerente pode verificar agora')).finally(() => setRodando(false)) }
  const u = est?.ultima

  return <Pagina titulo="🩺 Saúde dos pagamentos"><Aviso m={msg} />
    <div className="barra">
      <span>Verificação automática: <b>{est ? (est.intervalo === 'desligado' ? 'desligada' : `a cada ${est.intervalo}`) : '…'}</b> (mude em SAUDE_INTERVALO no .env)</span>
      <button className="btn" disabled={rodando} onClick={verificar}>{rodando ? 'Verificando…' : 'Verificar agora'}</button>
    </div>
    {!u ? <p>Ainda não houve verificação. A primeira acontece logo depois que o serviço de vendas inicia; ou clique em “Verificar agora”.</p> : <>
      <p>Última verificação: <b>{quando(u.quando)}</b> ({u.origem === 'MANUAL' ? 'manual' : 'automática'}) — <b>{ICONE[u.geral]} {ROTULO[u.geral]}</b></p>
      <table className="tab"><thead><tr><th>Meio</th><th>Provedor</th><th>Situação</th><th>Detalhe</th><th>Tempo</th></tr></thead>
        <tbody>{u.itens.map(i => <tr key={i.nome}><td><b>{i.nome}</b></td><td>{i.provedor || '—'}</td><td>{ICONE[i.status]} {ROTULO[i.status]}</td><td>{i.detalhe}</td><td>{i.nome === 'TEF' && i.status === 'NAO_CONFIGURADO' ? '' : `${i.ms} ms`}</td></tr>)}</tbody></table>
      {est.historico.length > 1 && <details style={{ marginTop: 12 }}><summary>Histórico ({est.historico.length})</summary>
        <table className="tab"><thead><tr><th>Quando</th><th>Geral</th><th>Pix</th><th>TEF</th></tr></thead>
          <tbody>{est.historico.map((h, k) => <tr key={k}><td>{quando(h.quando)}</td><td>{ICONE[h.geral]} {ROTULO[h.geral]}</td>
            {['PIX', 'TEF'].map(n => { const i = h.itens.find(x => x.nome === n); return <td key={n}>{i ? `${ICONE[i.status]} ${ROTULO[i.status]}` : '—'}</td> })}</tr>)}</tbody></table></details>}
    </>}
    <small className="eco">Só o Pix de PIX_PROVIDER e o TEF de TEF_PROVIDER são conferidos; sem TEF definido, ele não entra na conta. A verificação não cria cobrança nem passa cartão. O histórico fica em memória e some quando o serviço reinicia.</small>
  </Pagina>
}
