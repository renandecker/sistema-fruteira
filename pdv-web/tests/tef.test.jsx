import React from 'react'
import { render, screen, fireEvent, waitFor } from '@testing-library/react'
import PDV from '../src/PDV.jsx'
import { reconciliar } from '../src/tef.js'

const produtos = [{ id: 1, nome: 'Alface Crespa', unidade: 'UN', precoVarejo: 3.5, plu: 1, categoria: 'Verduras', ativo: true }]
const REQ = 'TEF20261005000005'
const APROVADA = { status: 'APROVADA', mensagem: 'Transação aprovada', nsu: '001001', autorizacao: '123456', bandeira: 'VISA', adquirente: 'SIMULADOR', cnpjCredenciadora: '00000000000191', cartao: '**** 1234', comprovanteCliente: 'VIA CLIENTE', comprovanteLoja: 'VIA LOJA' }
let chamadas, cenario
const ok = j => ({ ok: true, json: async () => j, text: async () => '' })
const erroHttp = (status, texto) => ({ ok: false, status, json: async () => ({}), text: async () => texto })

beforeEach(() => {
  chamadas = []; cenario = { agente: 'aprovada', venda: 'ok', pendencias: [] }
  localStorage.setItem('cfg', JSON.stringify({ tef: 'agente' }))
  window.print = vi.fn(); Element.prototype.scrollIntoView = () => {}
  globalThis.fetch = vi.fn(async (url, o = {}) => {
    const m = o.method || 'GET'; chamadas.push({ url, m, body: o.body })
    if (url.includes(':8090')) {                                                     // agente local
      if (cenario.agente === 'fora') throw new TypeError('Failed to fetch')
      if (url.endsWith('/transacoes') && m === 'POST') return ok({ estado: 'AGUARDANDO_CARTAO' })
      if (m === 'GET' && url.includes('/transacoes/')) {
        if (cenario.agente === 'negada') return ok({ estado: 'NEGADA', mensagem: 'Transação negada: saldo insuficiente', resultado: { status: 'NEGADA', mensagem: 'Transação negada: saldo insuficiente' } })
        return ok({ estado: 'APROVADA', mensagem: 'Transação aprovada', resultado: APROVADA })
      }
      return ok({})
    }
    if (url.endsWith('/tef/iniciar')) return ok({ id: 5, requisicao: REQ, tipo: 'CREDITO_A_VISTA', parcelas: 1 })
    if (url.endsWith('/tef/pendencias')) return ok(cenario.pendencias)
    if (url.includes('/api/vendas/tef/')) return ok({})
    if (url.endsWith('/vendas/vendas') && m === 'POST') {
      if (cenario.venda === 'recusada') return erroHttp(422, 'Produto desativado: Alface')
      return ok({ id: 9, total: 3.5, pagamentos: [{ meio: 'CREDITO', valor: 3.5, tefId: 5, nsu: '001001', autorizacao: '123456', bandeira: 'VISA', cnpjCredenciadora: '00000000000191' }] })
    }
    return ok(url.includes('/produtos') ? produtos : [])
  })
})
const foi = (parte, m = 'POST') => chamadas.some(c => c.url.includes(parte) && c.m === m)
const corpo = (parte, m = 'POST') => JSON.parse(chamadas.find(c => c.url.includes(parte) && c.m === m).body)
const tecla = key => fireEvent.keyDown(window, { key })
async function cobrarNoCartao() {
  const { container } = render(<PDV />); await screen.findAllByText(/Alface Crespa/)
  fireEvent.click([...container.querySelectorAll('button.card')][0])
  tecla('F2'); tecla('F3'); tecla('Enter')                                          // pagar → crédito → cobrar
  return container
}

test('cartão aprovado no TEF: grava a venda com o tefId, imprime e confirma', async () => {
  await cobrarNoCartao()
  await waitFor(() => expect(foi('/api/vendas/vendas')).toBe(true), { timeout: 4000 })
  expect(corpo('/tef/iniciar')).toMatchObject({ valor: 3.5, tipo: 'CREDITO_A_VISTA', parcelas: 1 })
  expect(corpo('/tef/5/resultado')).toMatchObject({ status: 'APROVADA', nsu: '001001', autorizacao: '123456', bandeira: 'VISA' })   // resultado gravado no servidor
  expect(corpo('/api/vendas/vendas').pagamentos).toEqual([{ meio: 'CREDITO', valor: 3.5, tefId: 5 }])
  await waitFor(() => expect(foi(`${REQ}/confirmar`)).toBe(true))                    // confirma no agente…
  await waitFor(() => expect(foi('/tef/5/confirmar')).toBe(true))                    // …e no servidor
  expect(window.print).toHaveBeenCalled()                                            // vias do cliente e da loja
  const nf = corpo('/nfce/emitir'); expect(JSON.parse(nf.cartoes)[0]).toMatchObject({ tPag: '03', tBand: '01', cAut: '123456', nsu: '001001', cnpjCredenciadora: '00000000000191' })
  expect(foi('/desfazer')).toBe(false)
})

test('cartão negado: mostra o motivo, não grava venda e oferece nova tentativa', async () => {
  cenario.agente = 'negada'; await cobrarNoCartao()
  await screen.findByText(/saldo insuficiente/, {}, { timeout: 4000 })
  expect(screen.getByText('Tentar novamente')).toBeTruthy()
  expect(corpo('/tef/5/resultado')).toMatchObject({ status: 'NEGADA' })
  expect(foi('/api/vendas/vendas')).toBe(false)
})

test('agente fora do ar: avisa e permite usar a maquininha manual (sem tefId)', async () => {
  cenario.agente = 'fora'; await cobrarNoCartao()
  await screen.findByText(/agente TEF não responde/, {}, { timeout: 4000 })
  fireEvent.click(screen.getByText('Usar maquininha manual'))
  await waitFor(() => expect(foi('/api/vendas/vendas')).toBe(true), { timeout: 4000 })
  expect(corpo('/api/vendas/vendas').pagamentos).toEqual([{ meio: 'CREDITO', valor: 3.5 }])
})

test('venda recusada depois da aprovação: o cartão é DESFEITO no agente e no servidor', async () => {
  cenario.venda = 'recusada'; await cobrarNoCartao()
  await waitFor(() => expect(foi(`${REQ}/desfazer`)).toBe(true), { timeout: 4000 })
  await waitFor(() => expect(foi('/tef/5/desfazer')).toBe(true))
  expect(foi('/tef/5/confirmar')).toBe(false); expect(window.print).not.toHaveBeenCalled()
})

test('reconciliação: com venda confirma; sem venda desfaz; sem retorno marca ERRO', async () => {
  cenario.pendencias = [
    { id: 5, requisicao: 'R5', status: 'APROVADA', vendaId: 9 },
    { id: 6, requisicao: 'R6', status: 'APROVADA', vendaId: null },
    { id: 7, requisicao: 'R7', status: 'PENDENTE', vendaId: null }]
  const orig = globalThis.fetch
  globalThis.fetch = vi.fn(async (url, o = {}) => (url.includes(':8090/tef/transacoes/R7') && (o.method || 'GET') === 'GET') ? erroHttp(404, 'Transação desconhecida') : orig(url, o))
  const r = await reconciliar()
  expect(r.resolvidas).toBe(3)
  expect(foi('R5/confirmar')).toBe(true); expect(foi('/tef/5/confirmar')).toBe(true)
  expect(foi('R6/desfazer')).toBe(true); expect(foi('/tef/6/desfazer')).toBe(true)
  expect(JSON.parse(chamadas.find(c => c.url.includes('/tef/7/resultado')).body)).toMatchObject({ status: 'ERRO' })
})
