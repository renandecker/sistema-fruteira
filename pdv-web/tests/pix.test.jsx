import React from 'react'
import { render, screen, fireEvent, waitFor } from '@testing-library/react'
import PDV from '../src/PDV.jsx'

const produtos = [{ id: 1, nome: 'Alface Crespa', unidade: 'UN', precoVarejo: 3.5, plu: 1, categoria: 'Verduras', ativo: true, ncm: '07051100', ncmStatus: 'VALIDO' }]
const PAYLOAD = '00020101021226540014br.gov.bcb.pix2532pix.simulador.local/qr/v2/abc5204000053039865405' + '3.505802BR5918FRUTEIRA CONVENTOS6006CIDADE62070503***6304ABCD'
const cobranca = (status, extra = {}) => ({ id: 3, txid: 'a'.repeat(32), status, valor: 3.5, valorPago: status === 'CONCLUIDA' ? 3.5 : null, pixCopiaECola: PAYLOAD,
  segundosRestantes: status === 'ATIVA' ? 300 : 0, provedor: 'psp', endToEndId: status === 'CONCLUIDA' ? 'E00000000202610061200ABCDEFGHIJK' : null, ...extra })
let chamadas, cenario, consultas
const ok = j => ({ ok: true, json: async () => j, text: async () => '' })

beforeEach(() => {
  chamadas = []; consultas = 0; cenario = { pago: true, venda: 'ok', pagarNaConsulta: 2 }
  localStorage.setItem('cfg', JSON.stringify({ pix: 'psp', pixIntervaloMs: 30 }))
  Element.prototype.scrollIntoView = () => {}
  navigator.clipboard = { writeText: vi.fn(async () => {}) }
  globalThis.fetch = vi.fn(async (url, o = {}) => {
    const m = o.method || 'GET'; chamadas.push({ url, m, body: o.body })
    if (url.endsWith('/pix/cobrancas') && m === 'POST') return ok(cobranca('ATIVA'))
    if (url.endsWith('/pix/cobrancas/3') && m === 'GET') {
      consultas++
      if (cenario.expira) return ok(cobranca('EXPIRADA'))
      return ok(consultas >= cenario.pagarNaConsulta ? cobranca('CONCLUIDA') : cobranca('ATIVA'))
    }
    if (url.endsWith('/pix/cobrancas/3/cancelar')) return ok(cobranca('CANCELADA'))
    if (url.endsWith('/pix/pendencias')) return ok(cenario.pendencias || [])
    if (url.endsWith('/vendas/vendas') && m === 'POST') {
      if (cenario.venda === 'recusada' && !cenario.liberada) return { ok: false, status: 422, json: async () => ({}), text: async () => 'Estoque indisponível' }
      return ok({ id: 9, total: 3.5, pagamentos: [{ meio: 'PIX', valor: 3.5, pixTxid: 'a'.repeat(32), pixE2e: 'E00000000202610061200ABCDEFGHIJK' }] })
    }
    return ok(url.includes('/produtos') ? produtos : [])
  })
})
const foi = (parte, m = 'POST') => chamadas.filter(c => c.url.includes(parte) && c.m === m).length
const corpo = (parte, m = 'POST') => JSON.parse(chamadas.find(c => c.url.includes(parte) && c.m === m).body)
const tecla = key => fireEvent.keyDown(window, { key })
async function gerarPix() {
  const { container } = render(<PDV />); await screen.findAllByText(/Alface Crespa/)
  fireEvent.click([...container.querySelectorAll('button.card')][0])
  tecla('F2'); tecla('Enter')                                                   // pagar → PIX (forma padrão) → gerar
  return container
}

test('Pix integrado: gera a cobrança, mostra QR + Copia e Cola, confirma por polling e grava a venda com pixId', async () => {
  await gerarPix()
  await screen.findByLabelText('Pix Copia e Cola')
  expect(corpo('/pix/cobrancas')).toMatchObject({ valor: 3.5 })
  expect(screen.getByLabelText('Pix Copia e Cola').value).toBe(PAYLOAD)
  expect(document.querySelector('.tef-box svg')).not.toBeNull()                 // QR Code renderizado
  await waitFor(() => expect(foi('/vendas/vendas')).toBe(1), { timeout: 4000 })
  expect(corpo('/vendas/vendas').pagamentos).toEqual([{ meio: 'PIX', valor: 3.5, pixId: 3 }])
  expect(foi('/pix/cobrancas/3', 'GET')).toBeGreaterThanOrEqual(2)             // polling até a confirmação
  const nf = corpo('/nfce/emitir'); expect(JSON.parse(nf.cartoes)[0]).toMatchObject({ tPag: '17', bandeira: 'PIX', nsu: 'E00000000202610061200ABCDEFGHIJK' })
})

test('botão Copiar Pix Copia e Cola usa a área de transferência e dá retorno visual', async () => {
  cenario.pagarNaConsulta = 999; await gerarPix()
  fireEvent.click(await screen.findByText('Copiar Pix Copia e Cola'))
  await screen.findByText('✔ Copiado!')
  expect(navigator.clipboard.writeText).toHaveBeenCalledWith(PAYLOAD)
})

test('Pix expirado: avisa e oferece gerar um novo; não grava venda', async () => {
  cenario.expira = true; await gerarPix()
  await screen.findByText('O Pix expirou sem pagamento.', {}, { timeout: 4000 })
  expect(screen.getByText('Gerar novo Pix')).toBeTruthy()
  expect(foi('/vendas/vendas')).toBe(0)
})

test('cancelar a cobrança ativa avisa o servidor', async () => {
  cenario.pagarNaConsulta = 999; await gerarPix()
  fireEvent.click(await screen.findByText('Cancelar cobrança'))
  await waitFor(() => expect(foi('/pix/cobrancas/3/cancelar')).toBe(1))
})

test('venda recusada depois do Pix recebido: o Pix fica reservado e dá para finalizar de novo SEM gerar outro QR', async () => {
  cenario.venda = 'recusada'; await gerarPix()
  await waitFor(() => expect(foi('/vendas/vendas')).toBe(1), { timeout: 4000 })
  await waitFor(() => expect(document.querySelector('.pags')?.textContent).toMatch(/Pix recebido/))
  expect(document.querySelector('.msg').textContent).toMatch(/Pix já foi recebido e continua reservado/)
  expect(foi('/pix/cobrancas')).toBe(1)                                          // só UMA cobrança criada
  const botao = [...document.querySelectorAll('.pag .btn')].find(b => b.textContent.includes('Finalizar venda')); expect(botao.disabled).toBe(false)
  fireEvent.click(screen.getByTitle('Remover este pagamento'))                  // Pix recebido não pode ser removido
  expect(document.querySelector('.pag [role=alert]').textContent).toMatch(/já foi recebido/)
  cenario.liberada = true; fireEvent.click(botao)                                // nova tentativa
  await waitFor(() => expect(foi('/vendas/vendas')).toBe(2), { timeout: 4000 })
  expect(corpo('/vendas/vendas').pagamentos).toEqual([{ meio: 'PIX', valor: 3.5, pixId: 3 }])
  expect(foi('/pix/cobrancas')).toBe(1)
})

test('aviso no caixa quando há Pix recebido sem venda', async () => {
  cenario.pendencias = [{ id: 8, status: 'CONCLUIDA' }]
  const { container } = render(<PDV />); await screen.findAllByText(/Alface Crespa/)
  await waitFor(() => expect(container.querySelector('.msg')?.textContent).toMatch(/1 Pix recebido\(s\) sem venda/))
})

test('sem integração (demonstração) o PIX continua com QR local e sem chamar o PSP', async () => {
  localStorage.setItem('cfg', JSON.stringify({}))
  const { container } = render(<PDV />); await screen.findAllByText(/Alface Crespa/)
  fireEvent.click([...container.querySelectorAll('button.card')][0]); tecla('F2')
  expect(document.querySelector('.pag svg')).not.toBeNull(); expect(foi('/pix/cobrancas')).toBe(0)
})
