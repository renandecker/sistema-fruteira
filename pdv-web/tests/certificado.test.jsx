import React from 'react'
import { render, screen, fireEvent, waitFor } from '@testing-library/react'
import CertificadoSefaz from '../src/CertificadoSefaz.jsx'

const base = { real: true, certificado: { ok: true, titular: 'FRUTEIRA LTDA:12345678000195', cnpj: '12345678000195', validoAte: '2027-10-24', diasRestantes: 365, arquivo: 'certificado.pfx', problemas: [], avisos: [] } }

afterEach(() => { vi.restoreAllMocks() })

test('mostra titular, validade e arquivo; não aparece no simulador', () => {
  const { container, rerender } = render(<CertificadoSefaz st={base} base="/api/retaguarda/nfce" />)
  expect(screen.getByLabelText('Certificado A1').textContent).toMatch(/24\/10\/2027 \(365 dias\).*certificado\.pfx/)
  rerender(<CertificadoSefaz st={{ real: false, certificado: { ok: true, simulado: true } }} base="/x" />); expect(container.textContent).toBe('')
})

test('certificado com problema e aviso de vencimento', () => {
  render(<CertificadoSefaz st={{ real: true, certificado: { ...base.certificado, ok: false, problemas: ['certificado A1 VENCIDO'], avisos: ['vence em 5 dia(s)'] } }} base="/x" />)
  expect(screen.getByText(/com problema/)).toBeTruthy(); expect(screen.getByText('certificado A1 VENCIDO')).toBeTruthy(); expect(screen.getByText('vence em 5 dia(s)')).toBeTruthy()
})

test('erro de leitura (senha errada) é exibido', () => {
  render(<CertificadoSefaz st={{ real: true, certificado: { ok: false, erro: 'Senha do certificado A1 incorreta (FISCAL_CERT_SENHA)' } }} base="/x" />)
  expect(screen.getByText(/Senha do certificado A1 incorreta/)).toBeTruthy()
})

test('testar a SEFAZ chama /sefaz-status e mostra o cStat', async () => {
  const f = vi.spyOn(globalThis, 'fetch').mockResolvedValue({ ok: true, json: async () => ({ ok: true, cStat: 107, mensagem: 'Servico em Operacao' }) })
  render(<CertificadoSefaz st={base} base="/api/retaguarda/nfe" />)
  fireEvent.click(screen.getByText('Testar comunicação com a SEFAZ'))
  await waitFor(() => expect(screen.getByText(/107 - Servico em Operacao/)).toBeTruthy())
  expect(f.mock.calls[0][0]).toBe('/api/retaguarda/nfe/sefaz-status'); expect(f.mock.calls[0][1].method).toBe('POST')
})

test('auth: /config.js do servidor tem prioridade sobre o valor embutido no build', async () => {
  vi.resetModules()
  window.__FRUTEIRA__ = { appEnv: 'producao' }
  const a = await import('../src/auth.js')
  expect(a.PROD).toBe(true)
  delete window.__FRUTEIRA__; vi.resetModules()
  const b = await import('../src/auth.js')
  expect(b.PROD).toBe(false)   // sem /config.js vale o valor embutido no build (no teste, desenvolvimento)
})

test('auth: em produção envia o token do login nas chamadas /api/ e volta ao login quando vence (401)', async () => {
  vi.resetModules(); sessionStorage.clear()
  window.__FRUTEIRA__ = { appEnv: 'producao' }
  const original = window.fetch
  const f = vi.fn().mockResolvedValue({ status: 200, ok: true }); window.fetch = f
  const a = await import('../src/auth.js')
  sessionStorage.setItem('user', JSON.stringify({ login: 'ana', perfil: 'operador' })); a.guardarToken('abc.def.ghi')
  expect(await a.iniciarAuth()).toEqual({ login: 'ana', perfil: 'operador' })
  await window.fetch('/api/vendas/x')
  expect(f.mock.calls[0][1].headers.Authorization).toBe('Bearer abc.def.ghi')
  await window.fetch('/api/retaguarda/usuarios/login', { method: 'POST' })
  expect(f.mock.calls[1][1]?.headers?.Authorization).toBeUndefined()   // o login não leva token
  window.fetch = original; delete window.__FRUTEIRA__; sessionStorage.clear()
})
