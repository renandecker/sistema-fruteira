// Autenticação por ambiente. APP_ENV=producao -> Keycloak (OIDC/PKCE); caso contrário, login local (Login.jsx).
export const PROD = __APP_ENV__ === 'producao'
export const KC = __KC__
let kc = null

export async function iniciarAuth() {
  if (!PROD) return null
  const { default: Keycloak } = await import('keycloak-js')
  kc = new Keycloak({ url: KC.url, realm: KC.realm, clientId: KC.client })
  await kc.init({ onLoad: 'login-required', pkceMethod: 'S256', checkLoginIframe: false })

  // anexa o Bearer token em todas as chamadas /api/* (renova antes de expirar)
  const original = window.fetch.bind(window)
  window.fetch = async (input, init = {}) => {
    const url = typeof input === 'string' ? input : input.url
    if (url.startsWith('/api/')) {
      try { await kc.updateToken(30) } catch { kc.login() }
      init = { ...init, headers: { ...(init.headers || {}), Authorization: `Bearer ${kc.token}` } }
    }
    return original(input, init)
  }
  const t = kc.tokenParsed, roles = t.realm_access?.roles || []
  return { nome: t.name || t.preferred_username, login: t.preferred_username, perfil: ['gerente', 'supervisor', 'operador'].find(r => roles.includes(r)) }
}
export const sairKeycloak = () => kc.logout({ redirectUri: window.location.origin })
