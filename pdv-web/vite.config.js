import { defineConfig, loadEnv } from 'vite'
import react from '@vitejs/plugin-react'

// Lê o .env da raiz do projeto (APP_ENV, KEYCLOAK_*)
export default defineConfig(({ mode }) => {
  const env = loadEnv(mode, '..', '')
  const alvo = (porta, base) => ({ target: `http://localhost:${porta}`, rewrite: p => p.replace(base, '') })
  const proxy = {
    '/api/catalogo': alvo(8081, '/api/catalogo'), '/api/estoque': alvo(8082, '/api/estoque'),
    '/api/vendas': alvo(8083, '/api/vendas'), '/api/retaguarda': alvo(8084, '/api/retaguarda'),
  }
  return {
    plugins: [react()], server: { proxy }, preview: { proxy },
    define: {
      __APP_ENV__: JSON.stringify(env.APP_ENV || 'desenvolvimento'),
      __KC__: JSON.stringify({ url: env.KEYCLOAK_URL || 'http://localhost:8180', realm: env.KEYCLOAK_REALM || 'fruteira', client: env.KEYCLOAK_WEB_CLIENT || 'pdv-web' }),
    },
  }
})
