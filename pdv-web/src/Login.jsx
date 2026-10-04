import React, { useState } from 'react'

const sha = async s => { try { const b = await crypto.subtle.digest('SHA-256', new TextEncoder().encode(s)); return [...new Uint8Array(b)].map(x => x.toString(16).padStart(2, '0')).join('') } catch { return s } }

export default function Login({ onLogin }) {
  const [login, setLogin] = useState(''); const [senha, setSenha] = useState(''); const [erro, setErro] = useState(''); const [load, setLoad] = useState(false)

  const entrar = async e => {
    e.preventDefault(); setLoad(true); setErro('')
    const id = login.trim().toLowerCase(); let motivo = ''
    try {
      const r = await fetch('/api/retaguarda/usuarios/login', { method: 'POST', headers: { 'Content-Type': 'application/json' }, body: JSON.stringify({ login: id, senha }) })
      if (r.status === 401 || r.status === 403) { setLoad(false); return setErro('Usuário ou senha inválidos') }
      if (r.ok) {
        const u = await r.json()
        localStorage.setItem('login-cache:' + id, JSON.stringify({ h: await sha(id + ':' + senha), u })) // permite entrar offline neste computador
        setLoad(false); return onLogin(u)
      }
      motivo = r.status >= 500
        ? `o retaguarda-service (porta 8084) não respondeu (HTTP ${r.status}) — confira se ele está rodando e veja logs/retaguarda-service.log`
        : `o serviço de login respondeu HTTP ${r.status}`
    } catch { motivo = 'não foi possível conectar ao servidor' }

    // sem o serviço de login: tenta o cache deste computador
    const c = JSON.parse(localStorage.getItem('login-cache:' + id) || 'null')
    if (c && c.h === await sha(id + ':' + senha)) { setLoad(false); return onLogin({ ...c.u, offline: true }) }
    setErro(`Login indisponível: ${motivo}.` + (c ? ' A senha informada difere da usada no último acesso a este computador.' : ' Este usuário ainda não entrou neste computador, por isso não há login offline.'))
    setLoad(false)
  }

  return (
    <div className="login-bg">
      <form className="login-card" onSubmit={entrar}>
        <img src="/logo.png" alt="Fruteira Conventos" />
        <h2>Entrar no sistema</h2>
        <label>Usuário<input autoFocus value={login} onChange={e => setLogin(e.target.value)} autoComplete="username" /></label>
        <label>Senha<input type="password" value={senha} onChange={e => setSenha(e.target.value)} autoComplete="current-password" /></label>
        {erro && <div className="login-erro">{erro}</div>}
        <button className="btn" disabled={!login || !senha || load}>{load ? 'Entrando…' : 'Entrar'}</button>
        <small>Acesso inicial: usuário e senha iguais ao perfil — <b>gerente</b>, <b>supervisor</b> ou <b>operador</b>. Troque as senhas em Usuários e perfis.</small>
      </form>
    </div>)
}
