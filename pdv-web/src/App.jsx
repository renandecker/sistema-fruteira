import React, { useState } from 'react'
import PDV from './PDV.jsx'
import { MENU, NIVEL } from './menu.js'
import { EmBreve, Produtos, Entrada, Perdas, RelPerdas, Abc, Fechamento } from './paginas.jsx'
import * as P2 from './paginas2.jsx'
import Auditoria from './Auditoria.jsx'
import Login from './Login.jsx'
import { PROD, sairKeycloak } from './auth.js'
import './theme.css'

const PAGINAS = { pdv: PDV, produtos: Produtos, entrada: Entrada, perdas: Perdas, relperdas: RelPerdas, abc: Abc, fechamento: Fechamento,
  selfcheckout: P2.SelfCheckout, consulta: P2.Consulta, fornecedores: P2.Fornecedores, clientes: P2.Clientes, producao: P2.Producao,
  xml: P2.ImportarXml, ceasa: P2.Ceasa, sugestao: P2.Sugestao, contas: P2.Contas, dre: P2.Dre, nfce: P2.Nfce, margem: P2.Margem,
  encartes: P2.Encartes, etiquetas: P2.Etiquetas, usuarios: P2.Usuarios, perifericos: P2.Perifericos, auditoria: Auditoria }

export default function App({ usuarioInicial }) {
  const [atual, setAtual] = useState('pdv'); const [aberto, setAberto] = useState(true); const [user, setUser] = useState(() => { try { return usuarioInicial ?? JSON.parse(sessionStorage.getItem('user') || 'null') } catch { return null } })
  const entrar = u => { sessionStorage.setItem('user', JSON.stringify(u)); setUser(u); setAtual('pdv') }
  const sair = () => { if (PROD) return sairKeycloak(); sessionStorage.removeItem('user'); setUser(null) }
  if (!user) return <Login onLogin={entrar} />
  if (PROD && !user.perfil) return <div className="login-bg"><div className="login-card"><h2>Sem perfil de acesso</h2>
    <p>Seu usuário não possui um dos perfis <b>gerente</b>, <b>supervisor</b> ou <b>operador</b> no Keycloak. Fale com o administrador.</p><button className="btn" onClick={sair}>Sair</button></div></div>
  const perfil = user.perfil
  const todos = MENU.flatMap(g => g.itens); const item = todos.find(i => i.id === atual)
  const Pag = PAGINAS[atual]; const permitido = !item || NIVEL[perfil] >= NIVEL[item.perfil]
  return (
    <div className="app" style={{ '--w': aberto ? '250px' : '64px' }}>
      <nav className="nav">
        <div className="nav-topo">
          <button className="hamb" onClick={() => setAberto(!aberto)} title="Recolher/expandir menu">☰</button>
          {aberto && <img src="/logo.png" alt="Fruteira Conventos" />}
          {aberto && <span className={`env ${PROD ? 'prod' : 'dev'}`} title={PROD ? 'Produção (Keycloak)' : 'Desenvolvimento'}>{PROD ? 'PROD' : 'DEV'}</span>}
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
        <div className="nav-rodape">
          {aberto ? <><span>👤 <b>{user.nome}</b> · {perfil}{user.offline ? ' (offline)' : ''}</span><button className="btn sec" onClick={sair}>Sair</button></>
            : <button className="hamb" title={`Sair (${user.nome})`} onClick={sair}>⏻</button>}
        </div>
      </nav>
      <main className="conteudo">{!permitido ? <div className="page"><h1>🚫 Acesso negado</h1><p>Seu perfil não tem permissão para esta tela.</p></div> : Pag ? <Pag /> : <EmBreve item={item} />}</main>
    </div>)
}
