import React, { useState } from 'react'
import { api, post, useProdutos, useLista, Pagina, Aviso } from './lib.jsx'

const R = '/api/catalogo/categorias'
const nota = 'Os produtos continuam cadastrados e passam a aparecer em "Outros" no PDV. Somente o gerente vê e reativa categorias desativadas.'

export default function Categorias() {
  const [cats, recarregar] = useLista(R + '?inativos=true'); const [produtos] = useProdutos()
  const [nome, setNome] = useState(''); const [msg, setMsg] = useState(''); const [edit, setEdit] = useState(null)
  const erro = e => setMsg(String(e) || 'Operação não permitida para o seu perfil')
  const qtd = c => produtos.filter(p => (p.categoria || '').toLowerCase() === c.nome.toLowerCase()).length
  const ativas = cats.filter(c => c.ativo !== false)

  const criar = () => post(R, { nome }).then(() => { setNome(''); setMsg(''); recarregar() }).catch(erro)
  const salvar = () => api(`${R}/${edit.id}`, { method: 'PUT', body: JSON.stringify({ nome: edit.nome, ordem: edit.ordem }) })
    .then(() => { setEdit(null); setMsg('Categoria atualizada ✔ (produtos renomeados junto)'); recarregar() }).catch(erro)
  const mover = (c, dir) => {   // troca a ordem com a vizinha
    const i = ativas.findIndex(x => x.id === c.id), v = ativas[i + dir]; if (!v) return
    const put = (x, ordem) => api(`${R}/${x.id}`, { method: 'PUT', body: JSON.stringify({ nome: x.nome, ordem }) })
    const [oa, ob] = c.ordem === v.ordem ? [i + dir, i] : [v.ordem, c.ordem]
    Promise.all([put(c, oa), put(v, ob)]).then(recarregar).catch(erro)
  }
  const desativar = c => window.confirm(`Desativar a categoria "${c.nome}"?\n${nota}`) && api(`${R}/${c.id}`, { method: 'DELETE' }).then(recarregar).catch(erro)
  const reativar = c => post(`${R}/${c.id}/reativar`).then(recarregar).catch(erro)

  return <Pagina titulo="🗂️ Categorias">
    <Aviso m={msg} />
    <div className="barra"><input placeholder="Nova categoria (ex.: Frutas, Verduras, Legumes)" value={nome} onChange={e => setNome(e.target.value)} onKeyDown={e => e.key === 'Enter' && nome && criar()} style={{ minWidth: 280 }} />
      <button className="btn" disabled={!nome.trim()} onClick={criar}>Adicionar</button>
      <small>Cada categoria vira uma <b>aba</b> no PDV. Use ▲▼ para definir a ordem das abas.</small></div>
    <table className="tab"><thead><tr><th>Ordem</th><th>Categoria</th><th>Produtos</th><th>Situação</th><th></th></tr></thead>
      <tbody>{cats.map(c => { const ativa = c.ativa !== false && c.ativo !== false; const i = ativas.findIndex(x => x.id === c.id)
        return <tr key={c.id} className={ativa ? '' : 'inativo'}>
          <td>{ativa ? <><button className="btn sec" disabled={i <= 0} onClick={() => mover(c, -1)}>▲</button> <button className="btn sec" disabled={i < 0 || i >= ativas.length - 1} onClick={() => mover(c, 1)}>▼</button></> : '—'}</td>
          <td>{edit?.id === c.id ? <input value={edit.nome} onChange={e => setEdit({ ...edit, nome: e.target.value })} /> : c.nome}</td>
          <td>{qtd(c)}</td>
          <td>{ativa ? 'Ativa' : <span className="classe off">Desativada</span>}</td>
          <td>{edit?.id === c.id ? <><button className="btn" onClick={salvar}>Salvar</button> <button className="btn sec" onClick={() => setEdit(null)}>Cancelar</button></>
            : ativa ? <><button className="btn sec" onClick={() => setEdit({ id: c.id, nome: c.nome, ordem: c.ordem })}>Renomear</button> <button className="btn sec" onClick={() => desativar(c)}>Desativar</button></>
            : <button className="btn sec" onClick={() => reativar(c)}>Reativar</button>}</td></tr> })}</tbody></table>
  </Pagina>
}
