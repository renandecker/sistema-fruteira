import React from 'react'
import { render, screen, fireEvent, waitFor, within } from '@testing-library/react'
import PDV from '../src/PDV.jsx'
import RelClientes from '../src/RelClientes.jsx'
import Caderno from '../src/Caderno.jsx'
import { nivelAlerta, textoAlerta, motivoBloqueio, montarRelatorioClientes, csvClientes } from '../src/caderno.js'
import { montarEmissao } from '../src/nfce.js'

const brl = n => Number(n).toLocaleString('pt-BR', { style: 'currency', currency: 'BRL' })
const CPF = '52998224725'
const produtos = [{ id: 1, nome: 'Cebola Branca', unidade: 'UN', precoVarejo: 2, plu: 2, ncm: '07031019', ncmStatus: 'VALIDO', categoria: 'Verduras', ativo: true }]
const dev = (o = {}) => ({ cpf: CPF, nome: 'Dona Maria', saldo: 80, limite: 300, disponivel: 220, diasAtraso: 5, desde: '2026-10-10T10:00:00', devendo: true, lancamentos: [], ...o })

test('nivelAlerta e textoAlerta', () => {
  expect(nivelAlerta(null)).toBe('ok'); expect(nivelAlerta(dev({ saldo: 0, devendo: false }))).toBe('ok')
  expect(nivelAlerta(dev())).toBe('devendo'); expect(nivelAlerta(dev({ diasAtraso: 30 }))).toBe('atraso'); expect(nivelAlerta(dev({ disponivel: 0 }))).toBe('limite')
  expect(textoAlerta(dev(), brl)).toMatch(/Dona Maria está devendo.*80,00.*há 5 dias/)
  expect(textoAlerta(dev({ diasAtraso: 1 }), brl)).toMatch(/há 1 dia$/)
  expect(textoAlerta(dev({ saldo: 0 }), brl)).toBe('')
})

test('motivoBloqueio: sem cliente, valor zero, acima do limite e ok', () => {
  expect(motivoBloqueio(null, 10, brl)).toMatch(/CPF de um cliente cadastrado/)
  expect(motivoBloqueio(dev(), 0, brl)).toMatch(/valor/)
  expect(motivoBloqueio(dev(), 221, brl)).toMatch(/Limite do caderno/)
  expect(motivoBloqueio(dev(), 220, brl)).toBe('')
})

test('relatório: junta compras e dívidas, ranking e KPIs', () => {
  const compras = [{ cpf: 'A', compras: 4, total: 400, ticketMedio: 100, ultimaCompra: '2026-10-10T10:00:00', noCaderno: 50 }, { cpf: 'B', compras: 2, total: 600, ticketMedio: 300, ultimaCompra: '2026-10-12T10:00:00', noCaderno: 0 }]
  const devedores = { lista: [{ cpf: 'A', nome: 'Ana', saldo: 50, diasAtraso: 40 }, { cpf: 'C', nome: 'Caio', saldo: 120, diasAtraso: 3 }] }
  const r = montarRelatorioClientes(compras, devedores, { A: 'Ana', B: 'Beto' })
  expect(r.porCompra.map(l => l.cpf)).toEqual(['B', 'A'])
  expect(r.porDivida.map(l => l.cpf)).toEqual(['C', 'A'])
  expect(r.kpis).toMatchObject({ totalComprado: 1000, clientesQueCompraram: 2, totalAReceber: 170, devedores: 2, emAtraso: 1, pesoDoFiado: 17 })
  expect(r.kpis.maiorDevedor.nome).toBe('Caio'); expect(r.kpis.melhorCliente.nome).toBe('Beto')
  expect(r.linhas).toHaveLength(3)                          // C só deve (nunca comprou no período) e aparece mesmo assim
  const csv = csvClientes(r.linhas).split('\n'); expect(csv[0]).toMatch(/^CPF;Cliente/); expect(csv[1]).toMatch(/B;Beto;2;600,00;300,00/)
  expect(montarRelatorioClientes().kpis.pesoDoFiado).toBe(0)   // vazio não quebra
})

test('NFC-e: o fiado vai como tPag 05', () => {
  const v = { id: 5, total: 4, itens: [{ produtoId: 1, nome: 'Cebola Branca', quantidade: 2, precoUnit: 2, subtotal: 4 }], pagamentos: [{ meio: 'CADERNO', valor: 4 }] }
  expect(montarEmissao(v, produtos, CPF).pagamentos[0].tPag).toBe('05')
})

let chamadas, sit
beforeEach(() => {
  chamadas = []; sit = dev()
  Element.prototype.scrollIntoView = () => {}; window.print = () => {}
  globalThis.fetch = vi.fn(async (url, o) => {
    chamadas.push({ url, o })
    const r = j => ({ ok: true, status: 200, json: async () => j, text: async () => '' })
    if (url.includes('/caderno/cliente/')) return sit ? r(sit) : { ok: false, status: 404, json: async () => ({}), text: async () => 'não cadastrado' }
    if (url.includes('/caderno/devedores')) return r({ totalAReceber: 80, devedores: 1, atrasados30: 0, maiorDevedor: sit, lista: [sit] })
    if (url.includes('/caderno/pagamento')) return r(dev({ saldo: 30 }))
    if (url.includes('/caixa/suprimento')) return r({ id: 1 })
    if (url.includes('/relatorio/clientes')) return r([{ cpf: CPF, compras: 3, total: 90, ticketMedio: 30, ultimaCompra: '2026-10-10T10:00:00', noCaderno: 40 }])
    if (url.endsWith('/vendas') && o?.method === 'POST') return r({ id: 9, total: 2, itens: [], pagamentos: [] })
    if (url.includes('/produtos')) return r(produtos)
    if (url.includes('/clientes')) return r([{ cpf: CPF, nome: 'Dona Maria' }])
    return r([])
  })
})

const abrirPdv = async () => { const r = render(<PDV />); await screen.findAllByText(/Cebola Branca/); return r.container }

test('PDV: CPF de quem deve mostra o alerta e o painel do caderno', async () => {
  const c = await abrirPdv()
  fireEvent.change(c.querySelector('input[aria-label="CPF na nota"]'), { target: { value: CPF } })
  const alerta = await waitFor(() => { const a = c.querySelector('.alerta-fiado'); expect(a).not.toBeNull(); return a })
  expect(alerta.textContent).toMatch(/Dona Maria está devendo/)
  fireEvent.click(screen.getByText('Ver caderno'))
  expect(await screen.findByLabelText('Caderno do cliente')).toBeTruthy()
})

test('PDV: cliente sem dívida não recebe alerta; CPF sem cadastro também não', async () => {
  sit = dev({ saldo: 0, devendo: false, diasAtraso: 0, disponivel: 300 })
  let c = await abrirPdv()
  fireEvent.change(c.querySelector('input[aria-label="CPF na nota"]'), { target: { value: CPF } })
  await waitFor(() => expect(c.textContent).toMatch(/sem dívida no caderno/)); expect(c.querySelector('.alerta-fiado')).toBeNull()
})

test('PDV: pagar no caderno envia o meio CADERNO; bloqueia acima do limite e sem CPF', async () => {
  const c = await abrirPdv()
  fireEvent.click([...c.querySelectorAll('button.card')].find(b => b.textContent.includes('Cebola')))
  fireEvent.keyDown(window, { key: 'F2' })                                   // abre o pagamento (sem CPF)
  fireEvent.keyDown(window, { key: 'F10' })
  expect(await screen.findByText(/informe, na tela de venda, o CPF/)).toBeTruthy()
  fireEvent.keyDown(window, { key: 'Enter' })
  expect(await screen.findByText(/Informe o CPF de um cliente cadastrado/)).toBeTruthy()
  expect(chamadas.some(x => x.url.endsWith('/vendas') && x.o?.method === 'POST')).toBe(false)
})

test('PDV: com CPF e dentro do limite a venda sai com CADERNO', async () => {
  const c = await abrirPdv()
  fireEvent.change(c.querySelector('input[aria-label="CPF na nota"]'), { target: { value: CPF } })
  await waitFor(() => expect(c.querySelector('.alerta-fiado')).not.toBeNull())
  fireEvent.click([...c.querySelectorAll('button.card')].find(b => b.textContent.includes('Cebola')))
  fireEvent.keyDown(window, { key: 'F2' }); fireEvent.keyDown(window, { key: 'F10' })
  await screen.findByText(/Fica anotado no caderno de Dona Maria/)
  fireEvent.keyDown(window, { key: 'Enter' })
  await waitFor(() => expect(chamadas.some(x => x.url.endsWith('/vendas') && x.o?.method === 'POST')).toBe(true))
  const env = JSON.parse(chamadas.find(x => x.url.endsWith('/vendas') && x.o?.method === 'POST').o.body)
  expect(env.cpf).toBe(CPF); expect(env.pagamentos).toEqual([{ meio: 'CADERNO', valor: 2 }])
})

test('PDV: acima do limite não envia a venda', async () => {
  sit = dev({ saldo: 299, disponivel: 1 })
  const c = await abrirPdv()
  fireEvent.change(c.querySelector('input[aria-label="CPF na nota"]'), { target: { value: CPF } })
  await waitFor(() => expect(c.querySelector('.alerta-fiado')).not.toBeNull())
  fireEvent.click([...c.querySelectorAll('button.card')].find(b => b.textContent.includes('Cebola')))
  fireEvent.keyDown(window, { key: 'F2' }); fireEvent.keyDown(window, { key: 'F10' }); fireEvent.keyDown(window, { key: 'Enter' })
  expect(await screen.findByText(/Limite do caderno: Dona Maria pode dever até/)).toBeTruthy()
  expect(chamadas.some(x => x.url.endsWith('/vendas') && x.o?.method === 'POST')).toBe(false)
})

test('Receber em dinheiro registra o pagamento e o suprimento no caixa', async () => {
  const c = await abrirPdv()
  fireEvent.change(c.querySelector('input[aria-label="CPF na nota"]'), { target: { value: CPF } })
  await waitFor(() => expect(c.querySelector('.alerta-fiado')).not.toBeNull())
  fireEvent.click(screen.getByText('Ver caderno')); fireEvent.click(await screen.findByText(/Receber$/))
  fireEvent.change(screen.getByLabelText('Valor recebido'), { target: { value: '50' } })
  fireEvent.click(screen.getByText('Registrar recebimento'))
  await waitFor(() => expect(chamadas.some(x => x.url.includes('/caixa/suprimento'))).toBe(true))
  expect(JSON.parse(chamadas.find(x => x.url.includes('/caderno/pagamento')).o.body)).toMatchObject({ cpf: CPF, valor: 50 })
  expect(JSON.parse(chamadas.find(x => x.url.includes('/caixa/suprimento')).o.body).valor).toBe(50)
})

test('tela Caderno: lista devedores, KPIs e perdão só para gerente', async () => {
  const { container, rerender } = render(<Caderno perfil="supervisor" />)
  expect(await screen.findByText('Dona Maria')).toBeTruthy(); expect(container.textContent).toMatch(/Total a receber/)
  expect(screen.queryByText('Perdoar')).toBeNull()
  rerender(<Caderno perfil="gerente" />); expect(await screen.findByText('Perdoar')).toBeTruthy()
})

test('relatório de clientes: KPIs de compra e de dívida na tela', async () => {
  const { container } = render(<RelClientes />)
  await waitFor(() => expect(container.textContent).toMatch(/Dona Maria/))
  expect(container.textContent).toMatch(/Total a receber \(caderno\)/); expect(container.textContent).toMatch(/Quem mais compra/)
})

test('PDV: escolher Caderno põe tudo o que falta no caderno e fecha a venda sem receber nada', async () => {
  const c = await abrirPdv()
  fireEvent.change(c.querySelector('input[aria-label="CPF na nota"]'), { target: { value: CPF } })
  await waitFor(() => expect(c.querySelector('.alerta-fiado')).not.toBeNull())
  fireEvent.click([...c.querySelectorAll('button.card')].find(b => b.textContent.includes('Cebola')))
  fireEvent.keyDown(window, { key: 'F2' })
  const campo = within(await screen.findByRole('dialog', { name: 'Pagamento' })).getByRole('spinbutton'); fireEvent.change(campo, { target: { value: '' } })   // valor apagado/zerado antes de escolher
  fireEvent.keyDown(window, { key: 'F10' })
  expect(await screen.findByText(/No caderno, para pagar depois/)).toBeTruthy()
  expect(screen.getByText(/a venda fecha sem receber nada agora/)).toBeTruthy()
  expect(screen.getByRole('button', { name: /Finalizar e anotar .*no caderno/ })).toBeTruthy()
  expect(screen.queryByText(/Informe o valor a anotar/)).toBeNull()
  fireEvent.keyDown(window, { key: 'Enter' })
  await waitFor(() => expect(chamadas.some(x => x.url.endsWith('/vendas') && x.o?.method === 'POST')).toBe(true))
  expect(JSON.parse(chamadas.find(x => x.url.endsWith('/vendas') && x.o?.method === 'POST').o.body).pagamentos).toEqual([{ meio: 'CADERNO', valor: 2 }])
})
