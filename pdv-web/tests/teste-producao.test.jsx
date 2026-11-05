import React from 'react'
import { render, screen, fireEvent, waitFor } from '@testing-library/react'
import { test, expect, vi, beforeEach } from 'vitest'
import TesteProducao, { avaliarTef, avaliarPix, textoTeste } from '../src/TesteProducao.jsx'

beforeEach(() => { localStorage.clear(); sessionStorage.clear() })

test('avaliarTef: simulador é aviso; Stone/Cielo/BB sem integração é erro; faltando variável é erro', () => {
  expect(avaliarTef({ provedor: 'simulador', implementado: true, pinpad: true, problemas: [], pendencias: 0 }).nivel).toBe('aviso')
  for (const p of ['stone', 'cielo', 'bb']) {
    const r = avaliarTef({ provedor: p, implementado: false, pinpad: false, problemas: [p === 'stone' ? 'STONE_CODIGO não definido' : 'CIELO_EC não definido'], pendencias: 0 })
    expect(r.nivel).toBe('erro'); expect(r.linhas.join(' ')).toMatch(/NÃO conversa com a maquininha/); expect(r.linhas.join(' ')).toMatch(/Falta configurar/)
  }
  expect(avaliarTef({ provedor: 'clisitef', implementado: true, pinpad: true, problemas: [], pendencias: 0 }).nivel).toBe('ok')
})
test('avaliarPix: simulador aviso, problemas erro, tudo certo ok', () => {
  expect(avaliarPix({ provedor: 'SIMULADOR', simulador: true, webhookConfigurado: true, problemas: [] }).nivel).toBe('aviso')
  expect(avaliarPix({ provedor: 'EFI', problemas: ['PIX_CHAVE'], webhookConfigurado: true }).nivel).toBe('erro')
  expect(avaliarPix({ provedor: 'EFI', problemas: [], webhookConfigurado: true }).nivel).toBe('ok')
})
test('painel: TEF consulta o agente do caixa, Pix consulta o servidor e a impressora imprime e pede confirmação', async () => {
  const f = vi.fn(async url => ({ ok: true, status: 200, text: async () => '', json: async () => url.includes('/tef/status') ? { provedor: 'cielo', implementado: false, pinpad: false, problemas: ['CIELO_EC não definido no .env deste computador'], pendencias: 0, descricao: 'Cielo' } : { provedor: 'EFI', problemas: [], webhookConfigurado: true } }))
  vi.stubGlobal('fetch', f)
  const imprimir = vi.fn()
  render(<TesteProducao f={{ tef: 'agente', tefUrl: 'http://127.0.0.1:8090', tefToken: 't', tefTerminal: 'PDV2', pix: 'psp', impressora: 'Térmica 80mm' }} imprimir={imprimir} />)
  fireEvent.click(screen.getByText('Testar TEF (maquininha)'))
  await waitFor(() => expect(screen.getByText(/NÃO conversa com a maquininha/)).toBeTruthy())
  expect(f.mock.calls[0][0]).toBe('http://127.0.0.1:8090/tef/status')
  fireEvent.click(screen.getByText('Testar Pix'))
  await waitFor(() => expect(screen.getByText(/Provedor: EFI/)).toBeTruthy())
  fireEvent.click(screen.getByText('Imprimir página de teste'))
  expect(imprimir).toHaveBeenCalledTimes(1); expect(imprimir.mock.calls[0][0]).toContain('PDV2')
  fireEvent.click(screen.getByText('Sim, saiu'))
  expect(screen.getByText(/papel de teste impresso/)).toBeTruthy()
  vi.unstubAllGlobals()
})
test('painel: caixa em modo Manual/Demonstração não finge testar', () => {
  render(<TesteProducao f={{ tef: 'nenhum', pix: 'demo', impressora: 'x' }} imprimir={() => {}} />)
  fireEvent.click(screen.getByText('Testar TEF (maquininha)')); fireEvent.click(screen.getByText('Testar Pix'))
  expect(screen.getByText(/modo Manual/)).toBeTruthy(); expect(screen.getByText(/modo Demonstração/)).toBeTruthy()
  expect(textoTeste({ impressora: 'x' }, 'ana')).toContain('ana')
})
