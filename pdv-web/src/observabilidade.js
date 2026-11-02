// Observabilidade do front. TUDO é opcional e desligado por padrão: cada ferramenta só carrega (import dinâmico,
// arquivo separado) quando a sua chave/URL está configurada no .env (OBS_*). Sem configuração = custo zero.
//
//  OpenTelemetry (traces do navegador → coletor OTLP, com `traceparent` nas chamadas /api: liga o clique ao backend)
//  Dynatrace (script RUM) · Grafana Faro · Sentry · Bugsnag · Rollbar · PostHog · React Scan (dev) · React Profiler
//
// PRIVACIDADE (LGPD): nada de CPF, e-mail, cartão, token ou PIN sai do navegador. Todo texto passa por `limpar()`,
// URLs perdem a query string, o usuário vai como id opaco (hash) + perfil, PostHog sem autocapture e sem gravação de tela.
const R = typeof window !== 'undefined' ? window.__FRUTEIRA__ : null
const CFG = { ...(typeof __OBS__ !== 'undefined' ? __OBS__ : {}), ...(R?.obs ?? {}) }
const nao = v => v == null || v === '' || v === 'false' || v === '0'
export const config = CFG
// O Dynatrace não deve receber OTLP direto do navegador (exigiria expor o token e não libera CORS): no front use OBS_DYNATRACE_SCRIPT (RUM) ou um Collector seu.
const otelDireto = /dynatrace\.com|\/api\/v2\/otlp/i.test(String(CFG.otelEndpoint ?? ''))
if (otelDireto && typeof console !== 'undefined') console.warn('[obs] OBS_OTEL_WEB_ENDPOINT aponta para o Dynatrace: ignorado. Use OBS_DYNATRACE_SCRIPT (RUM) ou um OpenTelemetry Collector.')
export const ativo = { otel: !nao(CFG.otelEndpoint) && !otelDireto, faro: !nao(CFG.faroUrl), sentry: !nao(CFG.sentryDsn), bugsnag: !nao(CFG.bugsnagKey),
  rollbar: !nao(CFG.rollbarToken), posthog: !nao(CFG.posthogKey), dynatrace: !nao(CFG.dynatraceScript), reactScan: !nao(CFG.reactScan), profiler: !nao(CFG.profiler) }
export const algumAtivo = () => Object.values(ativo).some(Boolean)

// ---------- limpeza de dados pessoais ----------
const PADROES = [
  [/\b\d{3}\.?\d{3}\.?\d{3}-?\d{2}\b/g, '[cpf]'], [/\b\d{2}\.?\d{3}\.?\d{3}\/?\d{4}-?\d{2}\b/g, '[cnpj]'],
  [/[\w.+-]+@[\w-]+\.[\w.-]+/g, '[email]'], [/\bBearer\s+[\w.~+/=-]+/gi, 'Bearer [token]'], [/\beyJ[\w-]{8,}\.[\w-]{8,}\.[\w-]*/g, '[jwt]'],
  [/\b(?:\d[ -]?){13,19}\b/g, '[numero]'], [/(pin|senha|password|token|secret)(["'\s:=]+)[^\s"',&}]+/gi, '$1$2[removido]'],
]
export function limpar(v, prof = 0) {
  if (v == null || prof > 6) return v
  if (typeof v === 'string') { let s = v; for (const [re, sub] of PADROES) s = s.replace(re, sub); return s.length > 2000 ? s.slice(0, 2000) + '…' : s }
  if (Array.isArray(v)) return v.map(x => limpar(x, prof + 1))
  if (v instanceof Error) { const e = new Error(limpar(v.message)); e.name = v.name; e.stack = limpar(v.stack); return e }
  if (typeof v === 'object') { const o = {}; for (const k of Object.keys(v)) o[k] = /cpf|senha|pin|token|password|secret|authorization/i.test(k) ? '[removido]' : limpar(v[k], prof + 1); return o }
  return v
}
export const semQuery = u => { try { const x = new URL(u, location.origin); return x.origin + x.pathname } catch { return String(u).split('?')[0] } }
const hash = async s => { try { const b = await crypto.subtle.digest('SHA-256', new TextEncoder().encode('fruteira:' + s)); return [...new Uint8Array(b)].slice(0, 8).map(x => x.toString(16).padStart(2, '0')).join('') } catch { return 'anon' } }

// ---------- estado e API pública ----------
const S = { sentry: null, bugsnag: null, rollbar: null, posthog: null, faro: null, otel: null, user: null, pronto: false }
const amostra = () => { const n = parseFloat(CFG.amostragem); return Number.isFinite(n) ? Math.min(1, Math.max(0, n)) : 0.1 }
const ctxBase = () => ({ servico: CFG.servico || 'pdv-web', versao: CFG.versao || 'dev', ambiente: CFG.ambiente || (R?.appEnv ?? 'desenvolvimento') })
const seguro = fn => { try { return fn() } catch { /* telemetria nunca derruba o PDV */ } }

export function erro(e, extra = {}) {
  const err = limpar(e instanceof Error ? e : new Error(String(e))), ctx = limpar({ ...ctxBase(), ...extra })
  seguro(() => S.sentry?.captureException(err, { extra: ctx }))
  seguro(() => S.bugsnag?.notify(err, ev => { ev.addMetadata('fruteira', ctx) }))
  seguro(() => S.rollbar?.error(err, ctx))
  seguro(() => S.faro?.api.pushError(err, { context: Object.fromEntries(Object.entries(ctx).map(([k, v]) => [k, String(v)])) }))
  seguro(() => S.posthog?.capture('erro_front', { mensagem: err.message, ...ctx }))
  seguro(() => { const sp = S.otel?.trace.getActiveSpan(); sp?.recordException(err) })
  if (CFG.consoleErros && typeof console !== 'undefined') console.error('[obs]', err, ctx)
}
export function evento(nome, props = {}) {
  const p = limpar(props)
  seguro(() => S.posthog?.capture(nome, p))
  seguro(() => S.faro?.api.pushEvent(nome, Object.fromEntries(Object.entries(p).map(([k, v]) => [k, String(v)]))))
  seguro(() => S.sentry?.addBreadcrumb({ category: 'app', message: nome, data: p }))
  seguro(() => S.bugsnag?.leaveBreadcrumb(nome, p))
  seguro(() => S.rollbar?.captureEvent({ message: nome, ...p }, 'info'))
}
export const pagina = id => { evento('pagina', { id }); seguro(() => S.posthog?.capture('$pageview', { $current_url: location.origin + '/' + id })) }
export async function identificar(u) {
  if (!u) return
  const id = await hash(String(u.usuario ?? u.nome ?? u.login ?? u.perfil ?? 'anon')), perfil = u.perfil ?? 'sem-perfil'
  S.user = { id, perfil }
  seguro(() => S.sentry?.setUser({ id })); seguro(() => S.sentry?.setTag('perfil', perfil))
  seguro(() => S.bugsnag?.setUser(id)); seguro(() => S.rollbar?.configure({ payload: { person: { id } } }))
  seguro(() => S.posthog?.identify(id, { perfil })); seguro(() => S.faro?.api.setUser({ id, attributes: { perfil } }))
  seguro(() => window.dtrum?.identifyUser?.(id))
}
// Mede um trecho (ex.: finalizar venda) como span OpenTelemetry; sem OTel ativo só executa.
export async function medir(nome, fn, atributos = {}) {
  const t = S.otel?.trace.getTracer('pdv-web'); if (!t) return fn()
  return t.startActiveSpan(nome, async span => {
    try { for (const [k, v] of Object.entries(limpar(atributos))) span.setAttribute(k, String(v)); return await fn() }
    catch (e) { span.recordException(limpar(e)); span.setStatus({ code: 2 }); throw e } finally { span.end() }
  })
}
// React Profiler: commits lentos viram evento (limiar em ms) — só com OBS_PROFILER=true.
export function aoRenderizar(id, fase, duracaoReal) {
  if (!ativo.profiler) return
  const limiar = parseFloat(CFG.profilerLimiarMs) || 50
  if (duracaoReal >= limiar) evento('render_lento', { componente: id, fase, ms: Math.round(duracaoReal) })
}

// ---------- inicialização (cada ferramenta em import dinâmico) ----------
const carregarScript = (src, attrs = {}) => new Promise(ok => { const s = document.createElement('script'); s.src = src; s.async = true; s.crossOrigin = 'anonymous'; Object.entries(attrs).forEach(([k, v]) => s.setAttribute(k, v)); s.onload = s.onerror = ok; document.head.appendChild(s) })

async function iniciarOtel() {
  const [{ WebTracerProvider, BatchSpanProcessor, TraceIdRatioBasedSampler, ParentBasedSampler }, { OTLPTraceExporter }, { resourceFromAttributes }, { registerInstrumentations }, { FetchInstrumentation }, { DocumentLoadInstrumentation }, { ZoneContextManager }, api] = await Promise.all([
    import('@opentelemetry/sdk-trace-web'), import('@opentelemetry/exporter-trace-otlp-http'), import('@opentelemetry/resources'), import('@opentelemetry/instrumentation'),
    import('@opentelemetry/instrumentation-fetch'), import('@opentelemetry/instrumentation-document-load'), import('@opentelemetry/context-zone'), import('@opentelemetry/api')])
  const c = ctxBase()
  const provider = new WebTracerProvider({
    resource: resourceFromAttributes({ 'service.name': c.servico, 'service.version': c.versao, 'deployment.environment.name': c.ambiente }),
    sampler: new ParentBasedSampler({ root: new TraceIdRatioBasedSampler(amostra()) }),
    spanProcessors: [new BatchSpanProcessor(new OTLPTraceExporter({ url: CFG.otelEndpoint, headers: {} }))],
  })
  provider.register({ contextManager: new ZoneContextManager() })
  registerInstrumentations({ tracerProvider: provider, instrumentations: [
    new DocumentLoadInstrumentation(),
    // `traceparent` só para a própria origem (/api/...): o backend continua o mesmo trace. Nada vai para terceiros.
    new FetchInstrumentation({ propagateTraceHeaderCorsUrls: [], ignoreUrls: [new RegExp(CFG.otelEndpoint.replace(/[.*+?^${}()|[\]\\]/g, '\\$&'))],
      applyCustomAttributesOnSpan: (span, req) => { try { const u = typeof req.url === 'string' ? req.url : String(req.url); span.setAttribute('http.url', semQuery(u)) } catch { /* ignora */ } } }),
  ] })
  S.otel = api
}
async function iniciarFaro() {
  const { initializeFaro, getWebInstrumentations } = await import('@grafana/faro-web-sdk'); const c = ctxBase()
  const instrumentations = getWebInstrumentations({ captureConsole: false })
  if (!ativo.otel) { const { TracingInstrumentation } = await import('@grafana/faro-web-tracing'); instrumentations.push(new TracingInstrumentation()) }   // com OTel próprio ativo, evita dois SDKs de trace
  S.faro = initializeFaro({ url: CFG.faroUrl, app: { name: c.servico, version: c.versao, environment: c.ambiente }, instrumentations, sessionTracking: { samplingRate: amostra() },
    beforeSend: item => { try { return JSON.parse(JSON.stringify(item), (k, v) => typeof v === 'string' ? limpar(v) : v) } catch { return null } } })
}
async function iniciarSentry() {
  const Sentry = await import('@sentry/react'); const c = ctxBase()
  Sentry.init({ dsn: CFG.sentryDsn, release: c.versao, environment: c.ambiente, sendDefaultPii: false, tracesSampleRate: ativo.otel ? 0 : amostra(),
    integrations: [Sentry.browserTracingIntegration()], replaysSessionSampleRate: 0, replaysOnErrorSampleRate: 0,
    beforeSend: ev => limpar(ev), beforeBreadcrumb: b => limpar(b) })
  S.sentry = Sentry
}
async function iniciarBugsnag() {
  const Bugsnag = (await import('@bugsnag/js')).default; const c = ctxBase()
  Bugsnag.start({ apiKey: CFG.bugsnagKey, ...(CFG.bugsnagNotify ? { endpoints: { notify: CFG.bugsnagNotify, sessions: CFG.bugsnagSessions || CFG.bugsnagNotify } } : {}), appVersion: c.versao, releaseStage: c.ambiente, collectUserIp: false, onError: ev => { ev.errors.forEach(x => { x.errorMessage = limpar(x.errorMessage) }); ev.request = { url: semQuery(location.href) } } })
  S.bugsnag = Bugsnag
}
async function iniciarRollbar() {
  const Rollbar = (await import('rollbar')).default; const c = ctxBase()
  S.rollbar = new Rollbar({ accessToken: CFG.rollbarToken, ...(CFG.rollbarEndpoint ? { endpoint: CFG.rollbarEndpoint } : {}), captureUncaught: true, captureUnhandledRejections: true, captureIp: false, environment: c.ambiente,
    payload: { client: { javascript: { code_version: c.versao } } }, transform: p => { try { Object.assign(p, limpar(p)) } catch { /* mantém */ } } })
}
async function iniciarPostHog() {
  const posthog = (await import('posthog-js')).default
  posthog.init(CFG.posthogKey, { api_host: CFG.posthogHost || 'https://us.i.posthog.com', autocapture: false, capture_pageview: false, disable_session_recording: true,
    persistence: 'localStorage', person_profiles: 'identified_only', mask_all_text: true, mask_all_element_attributes: true,
    sanitize_properties: (props) => limpar(props) })
  S.posthog = posthog
}
async function iniciarReactScan() {
  const dev = typeof import.meta !== 'undefined' && import.meta.env?.DEV
  if (!dev && CFG.reactScanProducao !== 'true') return            // em produção só com autorização explícita (custa CPU)
  const { scan } = await import('react-scan'); scan({ enabled: true, log: false })
}
function devtools() {
  const dev = typeof import.meta !== 'undefined' && import.meta.env?.DEV
  if (dev && !nao(CFG.reactDevtools)) { const s = document.createElement('script'); s.src = 'http://localhost:8097'; document.head.appendChild(s) }     // `npm run devtools` abre o React DevTools independente
}

let iniciou = null
export function iniciar() {
  if (iniciou) return iniciou
  iniciou = (async () => {
    if (typeof window === 'undefined') return
    const tarefas = []
    const quando = (flag, fn, nome) => { if (flag) tarefas.push(fn().catch(e => console.warn('[obs] falhou ao iniciar ' + nome, e?.message))) }
    quando(ativo.otel, iniciarOtel, 'OpenTelemetry'); quando(ativo.faro, iniciarFaro, 'Grafana Faro'); quando(ativo.sentry, iniciarSentry, 'Sentry')
    quando(ativo.bugsnag, iniciarBugsnag, 'Bugsnag'); quando(ativo.rollbar, iniciarRollbar, 'Rollbar'); quando(ativo.posthog, iniciarPostHog, 'PostHog')
    quando(ativo.reactScan, iniciarReactScan, 'React Scan')
    if (ativo.dynatrace) tarefas.push(carregarScript(CFG.dynatraceScript))
    devtools(); await Promise.all(tarefas); S.pronto = true
    // erros que ninguém tratou (Sentry/Bugsnag/Rollbar/Faro já ligam os seus; isto cobre o OTel e o PostHog)
    window.addEventListener('error', e => { if (!S.sentry && !S.bugsnag && !S.rollbar && !S.faro) erro(e.error || e.message, { origem: 'window.onerror' }) })
    window.addEventListener('unhandledrejection', e => { if (!S.sentry && !S.bugsnag && !S.rollbar && !S.faro) erro(e.reason, { origem: 'unhandledrejection' }) })
  })()
  return iniciou
}
export const _estado = S      // só para testes
