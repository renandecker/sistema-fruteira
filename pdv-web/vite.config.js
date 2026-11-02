import { defineConfig, loadEnv } from 'vite'
import react from '@vitejs/plugin-react'

// Lê o .env da raiz do projeto (APP_ENV, portas)
export default defineConfig(({ mode }) => {
  const env = loadEnv(mode, '..', '')
  const alvo = (porta, base) => ({ target: `http://127.0.0.1:${porta}`, rewrite: p => p.replace(base, '') })
  const proxy = {
    '/api/catalogo': alvo(8081, '/api/catalogo'), '/api/estoque': alvo(8082, '/api/estoque'),
    '/api/vendas': alvo(8083, '/api/vendas'), '/api/retaguarda': alvo(8084, '/api/retaguarda'),
  }
  const obs = { servico: 'pdv-web', versao: env.OBS_VERSAO || '', ambiente: env.OBS_AMBIENTE || env.APP_ENV || 'desenvolvimento', amostragem: env.OBS_AMOSTRAGEM, otelEndpoint: env.OBS_OTEL_WEB_ENDPOINT, faroUrl: env.OBS_FARO_URL,
    sentryDsn: env.OBS_SENTRY_DSN, bugsnagKey: env.OBS_BUGSNAG_KEY, bugsnagNotify: env.OBS_BUGSNAG_NOTIFY_URL, bugsnagSessions: env.OBS_BUGSNAG_SESSIONS_URL, rollbarEndpoint: env.OBS_ROLLBAR_ENDPOINT, rollbarToken: env.OBS_ROLLBAR_TOKEN, posthogKey: env.OBS_POSTHOG_KEY, posthogHost: env.OBS_POSTHOG_HOST,
    dynatraceScript: env.OBS_DYNATRACE_SCRIPT, reactScan: env.OBS_REACT_SCAN, profiler: env.OBS_PROFILER, profilerLimiarMs: env.OBS_PROFILER_LIMIAR_MS, reactDevtools: env.OBS_REACT_DEVTOOLS }   // só dev: em produção vale o /config.js
  return {
    plugins: [react()], server: { proxy }, preview: { proxy },
    define: {
      __OBS__: JSON.stringify(obs),
      __APP_ENV__: JSON.stringify(env.APP_ENV || 'desenvolvimento'),
    },
  }
})
