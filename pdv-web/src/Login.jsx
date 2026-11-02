import React, { useState } from 'react'
import { PROD, guardarToken } from './auth.js'

const sha = async s => { try { const b = await crypto.subtle.digest('SHA-256', new TextEncoder().encode(s)); return [...new Uint8Array(b)].map(x => x.toString(16).padStart(2, '0')).join('') } catch { return s } }

export default function Login({ onLogin }) {
  const [login, setLogin] = useState(''); const [senha, setSenha] = useState(''); const [erro, setErro] = useState(''); const [load, setLoad] = useState(false); const [troca, setTroca] = useState(null); const [nova, setNova] = useState(''); const [nova2, setNova2] = useState('')

  const entrar = async e => {
    e.preventDefault(); setLoad(true); setErro('')
    const id = login.trim().toLowerCase(); let motivo = ''
    try {
      const r = await fetch('/api/retaguarda/usuarios/login', { method: 'POST', headers: { 'Content-Type': 'application/json' }, body: JSON.stringify({ login: id, senha }) })
      if (r.status === 401 || r.status === 403) { setLoad(false); return setErro('Usuário ou senha inválidos') }
      if (r.ok) {
        const { token, ...u } = await r.json()
        if (token) guardarToken(token)
        if (!PROD) localStorage.setItem('login-cache:' + id, JSON.stringify({ h: await sha(id + ':' + senha), u })) // login offline só em desenvolvimento
        setLoad(false)
        if (u.provisoria) return setTroca(u)   // senha criada/redefinida pelo gerente: troca obrigatória no 1º acesso
        return onLogin(u)
      }
      motivo = r.status >= 500
        ? `o retaguarda-service (porta 8084) não respondeu (HTTP ${r.status}) — confira se ele está rodando e veja logs/retaguarda-service.log`
        : `o serviço de login respondeu HTTP ${r.status}`
    } catch { motivo = 'não foi possível conectar ao servidor' }

    if (PROD) { setLoad(false); return setErro(`Login indisponível: ${motivo}.`) }
    // sem o serviço de login (desenvolvimento): tenta o cache deste computador
    const c = JSON.parse(localStorage.getItem('login-cache:' + id) || 'null')
    if (c && c.h === await sha(id + ':' + senha)) { setLoad(false); return onLogin({ ...c.u, offline: true }) }
    setErro(`Login indisponível: ${motivo}.` + (c ? ' A senha informada difere da usada no último acesso a este computador.' : ' Este usuário ainda não entrou neste computador, por isso não há login offline.'))
    setLoad(false)
  }

  const trocar = async e => {
    e.preventDefault(); setErro('')
    if (nova !== nova2) return setErro('A confirmação não confere com a nova senha')
    setLoad(true)
    try {
      const r = await fetch('/api/retaguarda/usuarios/minha-senha', { method: 'POST', headers: { 'Content-Type': 'application/json', Authorization: `Bearer ${sessionStorage.getItem('token')}` }, body: JSON.stringify({ atual: senha, nova }) })
      if (!r.ok) { let m = ''; try { m = (await r.json()).message || '' } catch { /* sem corpo */ } setLoad(false); return setErro(m || `Não foi possível trocar a senha (HTTP ${r.status})`) }
      setLoad(false); onLogin({ ...troca, provisoria: false })
    } catch { setLoad(false); setErro('Não foi possível conectar ao servidor') }
  }

  if (troca) return (
    <div className="login-bg">
      <form className="login-card" onSubmit={trocar}>
        <img src="/logo.png" alt="Fruteira Conventos" />
        <h2>Defina sua senha</h2>
        <p>Olá, {troca.nome}. Sua senha é provisória: crie uma nova para continuar (mínimo de 8 caracteres).</p>
        <label>Nova senha<input autoFocus type="password" value={nova} onChange={e => setNova(e.target.value)} autoComplete="new-password" /></label>
        <label>Repita a nova senha<input type="password" value={nova2} onChange={e => setNova2(e.target.value)} autoComplete="new-password" /></label>
        {erro && <div className="login-erro">{erro}</div>}
        <button className="btn" disabled={!nova || !nova2 || load}>{load ? 'Salvando…' : 'Salvar e entrar'}</button>
      </form>
    </div>)

  return (
    <div className="login-bg">
      <form className="login-card" onSubmit={entrar}>
        <img src="/logo.png" alt="Fruteira Conventos" />
        <h2>Entrar no sistema</h2>
        <label>Usuário<input autoFocus value={login} onChange={e => setLogin(e.target.value)} autoComplete="username" /></label>
        <label>Senha<input type="password" value={senha} onChange={e => setSenha(e.target.value)} autoComplete="current-password" /></label>
        {erro && <div className="login-erro">{erro}</div>}
        <button className="btn" disabled={!login || !senha || load}>{load ? 'Entrando…' : 'Entrar'}</button>
        {!PROD && <small>Acesso de desenvolvimento: usuário e senha iguais ao perfil — <b>gerente</b>, <b>supervisor</b> ou <b>operador</b>.</small>}
        {PROD && <small>Não tem acesso? Peça ao gerente para criar seu usuário em Usuários e perfis.</small>}
      </form>
    </div>)
}
