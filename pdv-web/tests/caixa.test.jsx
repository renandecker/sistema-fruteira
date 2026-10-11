import React from 'react'
import { render, screen, fireEvent, waitFor } from '@testing-library/react'
import { Caixa, CaixaGerente } from '../src/Caixa.jsx'
import PDV from '../src/PDV.jsx'

let chamadas, turno
const resp = j => ({ ok: true, json: async () => j, text: async () => '' })
beforeEach(() => {
  chamadas = []; turno = { aberto: true, id: 1, abertura: '2026-10-10T08:00:00', sangriaRecomendada: false, movimentos: [] }
  Element.prototype.scrollIntoView = () => {}; window.print = vi.fn()
  globalThis.fetch = vi.fn(async (url, o) => {
    chamadas.push({ url, o })
    if (url.endsWith('/caixa/turno')) return resp(turno)
    if (url.endsWith('/caixa/sangria')) return resp({ id: 5, valor: 200, comprovante: 'SANGRIA' })
    if (url.endsWith('/caixa/turnos')) return resp([{ id: 1, operador: 'op', status: 'FECHADO', abertura: '2026-10-10T08:00:00', fechamento: '2026-10-10T18:00:00', fundoTroco: 100, sangrias: 200, suprimentos: 0, esperado: 650, contado: 640, diferenca: -10, sangriasAcimaDoSaldo: 1 }])
    if (url.includes('/produtos')) return resp([{ id: 1, nome: 'Alface', unidade: 'UN', precoVarejo: 3.5, plu: 1, categoria: 'V', ativo: true }])
    return resp([])
  })
})
const posts = fim => chamadas.filter(c => c.o?.method === 'POST' && c.url.endsWith(fim))

test('sem turno: pede o fundo de troco e abre o turno', async () => {
  turno = { aberto: false }
  render(<Caixa />)
  fireEvent.change(await screen.findByLabelText(/Fundo de troco/), { target: { value: '100' } })
  fireEvent.click(screen.getByText('Abrir turno'))
  await waitFor(() => expect(posts('/caixa/abrir').length).toBe(1))
  expect(JSON.parse(posts('/caixa/abrir')[0].o.body)).toEqual({ fundoTroco: 100 })
})
test('sangria é cega: envia o valor contado e a tela nunca mostra saldo do sistema', async () => {
  const { container } = render(<Caixa />)
  fireEvent.click(await screen.findByText(/💰 Sangria/))
  fireEvent.change(screen.getByLabelText(/Valor contado/), { target: { value: '200' } })
  fireEvent.change(screen.getByPlaceholderText(/levar ao cofre/), { target: { value: 'cofre' } })
  fireEvent.click(screen.getByText(/Confirmar/))
  await waitFor(() => expect(posts('/caixa/sangria').length).toBe(1))
  expect(JSON.parse(posts('/caixa/sangria')[0].o.body)).toEqual({ valor: 200, motivo: 'cofre' })
  await waitFor(() => expect(window.print).toHaveBeenCalled())          // comprovante da sangria
  expect(container.textContent).not.toMatch(/esperado|saldo do sistema:/i)
})
test('sangria com valor zero não é enviada', async () => {
  render(<Caixa />)
  fireEvent.click(await screen.findByText(/💰 Sangria/))
  expect(screen.getByText(/Confirmar/).closest('button').disabled).toBe(true)
})
test('PDV: aviso de gaveta cheia (sem valores) e F8 abre a sangria', async () => {
  turno.sangriaRecomendada = true
  render(<PDV />)
  expect(await screen.findByText(/Gaveta cheia/)).toBeTruthy()
  fireEvent.keyDown(window, { key: 'F8' })
  expect(await screen.findByRole('dialog', { name: /Sangria de caixa/ })).toBeTruthy()
  fireEvent.keyDown(window, { key: 'Escape' })
  await waitFor(() => expect(screen.queryByRole('dialog', { name: /Sangria de caixa/ })).toBeNull())
})
test('gerente vê esperado, contado, diferença e o alerta de sangria acima do saldo', async () => {
  const { container } = render(<CaixaGerente />)
  await screen.findByText('#1')
  expect(container.textContent).toMatch(/650,00/); expect(container.textContent).toMatch(/640,00/); expect(container.textContent).toMatch(/-R\$\s?10,00|R\$\s?-10,00|-10,00/)
  expect(container.querySelector('[title*="maior que o saldo"]')).not.toBeNull()
})
