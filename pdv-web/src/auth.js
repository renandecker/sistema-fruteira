// Autenticação por ambiente. O sistema tem login próprio (Login.jsx): o retaguarda confere usuário/senha e, em produção,
// devolve um token assinado que o front envia em todas as chamadas /api/*. Não há servidor de identidade externo.
// APP_ENV=producao: exige token (Authorization: Bearer). Desenvolvimento: sem token, só os cabeçalhos X-Usuario/X-Perfil (auditoria).
// O servidor web entrega /config.js (window.__FRUTEIRA__) com o ambiente do .env, então o front não precisa ser recompilado.
const R = typeof window !== 'undefined' ? window.__FRUTEIRA__ : null
export const PROD = R ? R.appEnv === 'producao' : __APP_ENV__ === 'producao'

const lerUsuario = () => { try { return JSON.parse(sessionStorage.getItem('user') || 'null') } catch { return null } }
export const guardarToken = t => { try { t ? sessionStorage.setItem('token', t) : sessionStorage.removeItem('token') } catch { /* sem storage */ } }
export const lerToken = () => { try { return sessionStorage.getItem('token') } catch { return null } }
export const limparSessao = () => { try { sessionStorage.removeItem('user'); sessionStorage.removeItem('token') } catch { /* nada */ } }

// Usuário da sessão atual (em produção só vale com token). O Login.jsx cuida de quem ainda não entrou.
export async function iniciarAuth() {
  if (!PROD) return null
  const original = window.fetch.bind(window)
  window.fetch = async (input, init = {}) => {
    const url = typeof input === 'string' ? input : input.url
    const api = url.startsWith('/api/'), login = url.startsWith('/api/retaguarda/usuarios/login')
    const t = lerToken()
    if (api && !login && t) init = { ...init, headers: { ...(init.headers || {}), Authorization: `Bearer ${t}` } }
    const r = await original(input, init)
    if (api && !login && r.status === 401 && t) { limparSessao(); window.location.reload() }   // token vencido: volta para o login
    return r
  }
  return lerToken() ? lerUsuario() : null
}

// DEV: sem token, o front informa quem está logado nos cabeçalhos X-Usuario/X-Perfil (usados na auditoria).
// Em produção a identidade vem do token e esses cabeçalhos são ignorados pelo backend.
export function instalarIdentidadeDev() {
  if (PROD) return
  const original = window.fetch.bind(window)
  window.fetch = (input, init = {}) => {
    const url = typeof input === 'string' ? input : input.url
    if (url.startsWith('/api/')) {
      const u = lerUsuario()
      if (u?.login) init = { ...init, headers: { ...(init.headers || {}), 'X-Usuario': u.login, 'X-Perfil': u.perfil } }
    }
    return original(input, init)
  }
}
