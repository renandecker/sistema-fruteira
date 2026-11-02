import { describe, test, expect, vi, beforeEach } from 'vitest'
import React from 'react'
import { render, screen } from '@testing-library/react'

beforeEach(() => { vi.resetModules(); delete window.__FRUTEIRA__ })

describe('observabilidade: privacidade', async () => {
  const { limpar, semQuery } = await import('../src/observabilidade.js')
  test('remove CPF, CNPJ, e-mail, token, JWT, cartão e PIN', () => {
    const t = limpar('cliente 123.456.789-09 e 12345678909, cnpj 12.345.678/0001-95, a@b.com, Bearer abc.def.ghi, cartao 4111 1111 1111 1111, pin=9876')
    expect(t).not.toMatch(/123\.456|12345678909|12\.345|a@b\.com|abc\.def|4111|9876/)
    expect(t).toContain('[cpf]'); expect(t).toContain('[email]'); expect(t).toContain('[numero]')
  })
  test('objetos: chaves sensíveis viram [removido] e aninhados são limpos', () => {
    const o = limpar({ cpf: '12345678909', senha: 'x', ok: 'valor', n: { token: 't', msg: 'fulano@x.com' } })
    expect(o).toEqual({ cpf: '[removido]', senha: '[removido]', ok: 'valor', n: { token: '[removido]', msg: '[email]' } })
  })
  test('Error: mensagem e pilha limpas', () => { const e = limpar(new Error('falha para 123.456.789-09')); expect(e.message).toBe('falha para [cpf]') })
  test('URL perde a query string', () => { expect(semQuery('http://x/api/v?cpf=123&a=1')).toBe('http://x/api/v') })
})

describe('observabilidade: desligada por padrão', () => {
  test('sem configuração nada é carregado e as chamadas não quebram', async () => {
    const o = await import('../src/observabilidade.js')
    expect(o.algumAtivo()).toBe(false)
    await o.iniciar()
    expect(() => { o.erro(new Error('x')); o.evento('e', { cpf: '1' }); o.pagina('pdv') }).not.toThrow()
    await expect(o.identificar({ usuario: 'u', perfil: 'gerente' })).resolves.toBeUndefined()
    expect(await o.medir('venda', async () => 42)).toBe(42)
    await expect(o.medir('venda', async () => { throw new Error('boom') })).rejects.toThrow('boom')
  })
  test('config de runtime (/config.js) liga só o que tem chave', async () => {
    window.__FRUTEIRA__ = { appEnv: 'producao', obs: { sentryDsn: 'https://k@o.ingest.sentry.io/1', posthogKey: 'phc_x', profiler: 'true' } }
    const o = await import('../src/observabilidade.js')
    expect(o.ativo).toMatchObject({ sentry: true, posthog: true, profiler: true, otel: false, faro: false, bugsnag: false, rollbar: false, dynatrace: false })
  })
  test('Profiler: só reporta render lento quando ligado', async () => {
    window.__FRUTEIRA__ = { obs: { profiler: 'true', profilerLimiarMs: '10' } }
    const o = await import('../src/observabilidade.js'); const ph = { capture: vi.fn() }; o._estado.posthog = ph
    o.aoRenderizar('app', 'update', 5); expect(ph.capture).not.toHaveBeenCalled()
    o.aoRenderizar('app', 'update', 30); expect(ph.capture).toHaveBeenCalledWith('render_lento', { componente: 'app', fase: 'update', ms: 30 })
  })
  test('erro vai para as ferramentas ativas, já limpo', async () => {
    const o = await import('../src/observabilidade.js'); const s = { captureException: vi.fn() }, r = { error: vi.fn() }
    o._estado.sentry = s; o._estado.rollbar = r
    o.erro(new Error('CPF 123.456.789-09'), { cpf: '123.456.789-09', rota: '/venda' })
    expect(s.captureException.mock.calls[0][0].message).toBe('CPF [cpf]'); expect(s.captureException.mock.calls[0][1].extra.cpf).toBe('[removido]')
    expect(r.error).toHaveBeenCalled()
  })
  test('uma ferramenta que lança exceção não derruba o PDV', async () => {
    const o = await import('../src/observabilidade.js'); o._estado.sentry = { captureException() { throw new Error('sdk quebrou') } }
    expect(() => o.erro(new Error('x'))).not.toThrow()
  })
})

describe('ObsRaiz', () => {
  test('mostra tela de recuperação e reporta o erro', async () => {
    const { ObsRaiz } = await import('../src/ObsRaiz.jsx'); const o = await import('../src/observabilidade.js')
    const s = { captureException: vi.fn() }; o._estado.sentry = s
    const Quebra = () => { throw new Error('render falhou') }
    const spy = vi.spyOn(console, 'error').mockImplementation(() => {})
    render(<ObsRaiz><Quebra /></ObsRaiz>)
    expect(await screen.findByRole('alert')).toBeTruthy(); expect(s.captureException).toHaveBeenCalled(); spy.mockRestore()
  })
  test('sem erro renderiza os filhos', async () => {
    const { ObsRaiz } = await import('../src/ObsRaiz.jsx'); render(<ObsRaiz><p>oi</p></ObsRaiz>); expect(screen.getByText('oi')).toBeTruthy()
  })
})

describe('Rollbar + Bugsnag juntos e Dynatrace', () => {
  test('os dois recebem o mesmo erro, já sem dados pessoais', async () => {
    window.__FRUTEIRA__ = { obs: { rollbarToken: 'rt', bugsnagKey: 'bk' } }
    const o = await import('../src/observabilidade.js')
    const bug = { notify: vi.fn((e, cb) => cb?.({ addMetadata: vi.fn() })), leaveBreadcrumb: vi.fn() }, roll = { error: vi.fn(), captureEvent: vi.fn() }
    o._estado.bugsnag = bug; o._estado.rollbar = roll
    o.erro(new Error('falha cpf 123.456.789-09'), { origem: 't' }); o.evento('venda', { cpf: '12345678909' })
    expect(bug.notify.mock.calls[0][0].message).toBe('falha cpf [cpf]'); expect(roll.error.mock.calls[0][0].message).toBe('falha cpf [cpf]')
    expect(JSON.stringify(roll.captureEvent.mock.calls)).not.toMatch(/12345678909/)
  })
  test('OTLP do front apontando direto ao Dynatrace é ignorado (use o script RUM)', async () => {
    const av = vi.spyOn(console, 'warn').mockImplementation(() => {})
    window.__FRUTEIRA__ = { obs: { otelEndpoint: 'https://abc12345.live.dynatrace.com/api/v2/otlp/v1/traces', dynatraceScript: 'https://js-cdn.dynatrace.com/jstag/x/bf1/abc_complete.js' } }
    const o = await import('../src/observabilidade.js')
    expect(o.ativo.otel).toBe(false); expect(o.ativo.dynatrace).toBe(true); expect(av).toHaveBeenCalled(); av.mockRestore()
  })
})
