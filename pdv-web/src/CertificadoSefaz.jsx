import React, { useState } from 'react'
import { api } from './lib.jsx'

/** Estado do certificado A1 (titular, CNPJ, validade) e teste da comunicação com a SEFAZ. `st` = resposta de /nfce/status ou /nfe/status; `base` = '/api/retaguarda/nfce' ou '/nfe'. */
export default function CertificadoSefaz({ st, base }) {
  const [t, setT] = useState(null)
  if (!st?.real || !st.certificado) return null
  const c = st.certificado
  const testar = () => { setT({ carregando: true }); api(`${base}/sefaz-status`, { method: 'POST' }).then(setT).catch(e => setT({ ok: false, mensagem: String(e?.message || e) })) }
  return <div className={`conf-fiscal ${c.ok && !(c.avisos?.length) ? 'ok' : ''}`} role="status" aria-label="Certificado A1">
    {c.erro ? <><b>⚠ Certificado A1:</b> {c.erro}</>
      : <><b>{c.ok ? '🔐 Certificado A1' : '⚠ Certificado A1 com problema'}</b> · {c.titular}{c.cnpj ? ` · CNPJ ${c.cnpj}` : ''} · válido até {String(c.validoAte).split('-').reverse().join('/')} ({c.diasRestantes} dias) · arquivo {c.arquivo}
        {[...(c.problemas || []), ...(c.avisos || [])].length > 0 && <ul>{[...(c.problemas || []), ...(c.avisos || [])].map(x => <li key={x}>{x}</li>)}</ul>}</>}
    <div style={{ marginTop: 6 }}><button className="btn sec" disabled={t?.carregando} onClick={testar}>{t?.carregando ? 'Consultando…' : 'Testar comunicação com a SEFAZ'}</button>
      {t && !t.carregando && <span role="status" style={{ marginLeft: 8 }}>{t.ok ? '✔' : '✘'} {t.cStat ? `${t.cStat} - ` : ''}{t.mensagem}</span>}</div>
  </div>
}
