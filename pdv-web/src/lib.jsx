import React, { useEffect, useState } from 'react'
export const brl = n => Number(n ?? 0).toLocaleString('pt-BR', { style: 'currency', currency: 'BRL' })
export const api = (p, o) => fetch(p, { headers: { 'Content-Type': 'application/json' }, ...o }).then(r => r.ok ? r.json().catch(() => ({})) : r.text().then(t => Promise.reject(t)))
export const post = (p, body) => api(p, { method: 'POST', body: body && JSON.stringify(body) })
export const cfg = () => { try { return JSON.parse(localStorage.getItem('cfg') || '{}') } catch { return {} } }
export function useProdutos() {
  const [l, setL] = useState([]); const c = () => api('/api/catalogo/produtos').then(setL).catch(() => {})
  useEffect(() => { c() }, []); return [l, c]
}
export function useLista(url) {
  const [l, setL] = useState([]); const c = () => api(url).then(setL).catch(() => {})
  useEffect(() => { c() }, []); return [l, c]
}
export function useForm(ini) { const [f, setF] = useState(ini); return [f, k => e => setF({ ...f, [k]: e.target.value }), setF] }
export const Pagina = ({ titulo, children }) => <div className="page"><h1>{titulo}</h1>{children}</div>
export const Aviso = ({ m }) => m ? <div className="msg" style={{ margin: '0 0 12px' }}>{m}</div> : null
export const SelProduto = ({ lista, v, onChange }) => (
  <select value={v} onChange={e => onChange(e.target.value)}><option value="">Produto…</option>
    {lista.map(p => <option key={p.id} value={p.id}>{p.plu} · {p.nome}</option>)}</select>)
