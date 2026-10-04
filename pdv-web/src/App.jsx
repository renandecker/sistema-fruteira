import React, { useState } from 'react'
import PDV from './PDV.jsx'
import { MENU, NIVEL } from './menu.js'
import { EmBreve, Produtos, Entrada, Perdas, RelPerdas, Abc, Fechamento } from './paginas.jsx'
import * as P2 from './paginas2.jsx'
import './theme.css'

const PAGINAS = { pdv: PDV, produtos: Produtos, entrada: Entrada, perdas: Perdas, relperdas: RelPerdas, abc: Abc, fechamento: Fechamento,
  selfcheckout: P2.SelfCheckout, consulta: P2.Consulta, fornecedores: P2.Fornecedores, clientes: P2.Clientes, producao: P2.Producao,
  xml: P2.ImportarXml, ceasa: P2.Ceasa, sugestao: P2.Sugestao, contas: P2.Contas, dre: P2.Dre, nfce: P2.Nfce, margem: P2.Margem,
  encartes: P2.Encartes, etiquetas: P2.Etiquetas, usuarios: P2.Usuarios, perifericos: P2.Perifericos }

export default function App() {
  const [atual, setAtual] = useState('pdv'); const [aberto, setAberto] = useState(true); const [perfil, setPerfil] = useState('gerente')
  const todos = MENU.flatMap(g => g.itens); const item = todos.find(i => i.id === atual)
  const Pag = PAGINAS[atual]
  return (
    <div className="app" style={{ '--w': aberto ? '250px' : '64px' }}>
      <nav className="nav">
        <div className="nav-topo">
          <button className="hamb" onClick={() => setAberto(!aberto)} title="Recolher/expandir menu">☰</button>
          {aberto && <img src="/logo.png" alt="Fruteira Conventos" />}
        </div>
        <div className="nav-lista">
          {MENU.map(g => {
            const itens = g.itens.filter(i => NIVEL[perfil] >= NIVEL[i.perfil]); if (!itens.length) return null
            return <div key={g.grupo}>
              {aberto && <div className="grupo">{g.grupo}</div>}
              {itens.map(i => <button key={i.id} title={i.titulo} className={`navitem ${atual === i.id ? 'on' : ''}`} onClick={() => setAtual(i.id)}>
                <span>{i.icone}</span>{aberto && <><em>{i.titulo}</em>{i.status !== 'ok' && <small>breve</small>}</>}</button>)}
            </div>
          })}
        </div>
        {aberto && <div className="nav-rodape">Perfil (demo)
          <select value={perfil} onChange={e => { setPerfil(e.target.value); setAtual('pdv') }}>
            <option value="operador">Operador</option><option value="supervisor">Supervisor</option><option value="gerente">Gerente</option></select></div>}
      </nav>
      <main className="conteudo">{Pag ? <Pag /> : <EmBreve item={item} />}</main>
    </div>)
}
