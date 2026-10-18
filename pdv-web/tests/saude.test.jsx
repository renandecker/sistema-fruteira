import React from 'react'
import { render, screen, fireEvent, waitFor } from '@testing-library/react'
import Saude from '../src/Saude.jsx'
import { nivelSaude, textoSaude, selosSaude } from '../src/saude.js'

const item = (nome, status, detalhe = '', provedor = 'x') => ({ nome, provedor, status, detalhe, ms: 12 })
const est = (itens, geral, extra = {}) => ({ intervalo: '1h', ultima: { quando: '2026-10-18T15:00:00', origem: 'AGENDADA', geral, itens }, historico: [], ...extra })

test('nivelSaude e textoSaude: só falam de problemas', () => {
  expect(nivelSaude(null)).toBe('nenhum'); expect(nivelSaude({ ultima: null })).toBe('nenhum')
  expect(nivelSaude(est([], 'OK'))).toBe('ok'); expect(nivelSaude(est([], 'ALERTA'))).toBe('alerta'); expect(nivelSaude(est([], 'FALHA'))).toBe('falha')
  expect(textoSaude(est([item('PIX', 'OK'), item('TEF', 'NAO_CONFIGURADO')], 'OK'))).toBe('')
  expect(textoSaude(est([item('PIX', 'OK'), item('TEF', 'FALHA')], 'FALHA'))).toBe('TEF com problema')
  expect(textoSaude(est([item('PIX', 'ALERTA'), item('TEF', 'FALHA')], 'FALHA'))).toBe('PIX: atenção · TEF com problema')
})

test('tela mostra intervalo, itens e o TEF não definido; "Verificar agora" chama o POST e recarrega', async () => {
  const antes = est([item('PIX', 'OK', 'provedor respondeu', 'ASAAS'), item('TEF', 'NAO_CONFIGURADO', 'TEF não definido')], 'OK')
  const depois = est([item('PIX', 'FALHA', 'HTTP 401', 'ASAAS'), item('TEF', 'NAO_CONFIGURADO', 'TEF não definido')], 'FALHA')
  let verificou = false; const chamadas = []
  global.fetch = vi.fn((url, o) => { chamadas.push(`${o?.method || 'GET'} ${url}`); if (o?.method === 'POST') verificou = true; return Promise.resolve({ ok: true, json: () => Promise.resolve(verificou ? depois : antes) }) })
  render(<Saude />)
  expect(await screen.findByText(/a cada 1h/)).toBeTruthy()
  expect(screen.getByText('provedor respondeu')).toBeTruthy(); expect(screen.getByText(/Não definido/)).toBeTruthy()
  fireEvent.click(screen.getByRole('button', { name: 'Verificar agora' }))
  expect(await screen.findByText('HTTP 401')).toBeTruthy()
  expect(chamadas).toContain('POST /api/vendas/saude/pagamentos/verificar')
})

test('PDV avisa só quando o Pix/TEF tem problema', async () => {
  window.HTMLElement.prototype.scrollIntoView = () => {}
  const { default: PDV } = await import('../src/PDV.jsx')
  const resp = e => vi.fn(url => Promise.resolve({ ok: true, json: () => Promise.resolve(String(url).includes('/saude/pagamentos') ? e : []) }))
  global.fetch = resp(est([item('PIX', 'OK'), item('TEF', 'FALHA', 'agente não respondeu')], 'FALHA'))
  const a = render(<PDV />)
  expect((await screen.findByTestId('alerta-saude')).textContent).toMatch(/📟 TEF fora/)
  expect(screen.queryByText(/PIX/)).toBeNull()
  a.unmount()
  global.fetch = resp(est([item('PIX', 'OK'), item('TEF', 'OK')], 'OK'))
  render(<PDV />)
  await waitFor(() => expect(global.fetch).toHaveBeenCalled())
  await new Promise(r => setTimeout(r, 50))
  expect(screen.queryByTestId('alerta-saude')).toBeNull()
})

test('selosSaude: ícone do meio, fora x aviso, nada se estiver bem', () => {
  expect(selosSaude(est([item('PIX', 'OK'), item('TEF', 'NAO_CONFIGURADO')], 'OK'))).toEqual([])
  const l = selosSaude(est([item('PIX', 'ALERTA', 'sem webhook'), item('TEF', 'FALHA', 'sem agente')], 'FALHA'))
  expect(l.map(x => [x.icone, x.texto, x.fora])).toEqual([['💠', 'PIX com aviso', false], ['📟', 'TEF fora', true]])
})
