import React from 'react'
import { render, screen, fireEvent, act, waitFor } from '@testing-library/react'
import PDV from '../src/PDV.jsx'
import { Entrada } from '../src/paginas.jsx'

const produtos = [
  { id: 1, nome: 'Alface Crespa', unidade: 'UN', precoVarejo: 3.5, plu: 1, categoria: 'Verduras', ativo: true },
  { id: 2, nome: 'Cebola Branca', unidade: 'UN', precoVarejo: 2, plu: 2, categoria: 'Verduras', ativo: true },
  { id: 3, nome: 'Banana Prata', unidade: 'KG', precoVarejo: 8, plu: 3, categoria: 'Frutas', ativo: true },
]
let chamadas
beforeEach(() => {
  chamadas = []
  Element.prototype.scrollIntoView = () => {}
  globalThis.fetch = vi.fn(async (url, o) => {
    chamadas.push({ url, o })
    const j = o?.method === 'POST' && url.endsWith('/vendas') ? { id: 9, total: 5.5 } : o?.method === 'POST' ? { quantidade: 100, custoMedio: 5.5 }
      : url.includes('/produtos') ? produtos : url.includes('/categorias') ? [{ id: 1, nome: 'Verduras' }, { id: 2, nome: 'Frutas' }]
      : url.includes('/fornecedores') ? [{ id: 7, nome: 'CEASA Central', tipo: 'CEASA' }, { id: 8, nome: 'Sítio Boa Vista', tipo: 'PRODUTOR_RURAL' }]
      : url.includes('/entradas') ? [{ id: 1, produtoId: 1, data: '2026-10-05T10:00:00', quantidade: 100, custoUnitario: 5.5, documento: 'NF 10', fornecedor: 'CEASA Central' }] : []
    return { ok: true, json: async () => j, text: async () => '' }
  })
})
const itens = c => c.querySelectorAll('aside ul li:not([style])').length
const card = (c, nome) => [...c.querySelectorAll('button.card')].find(b => b.textContent.includes(nome))
const abrir = async () => { const r = render(<PDV />); await screen.findAllByText(/Alface Crespa/); return r.container }
const tecla = key => fireEvent.keyDown(window, { key })
const valorCampo = () => document.querySelector('.pag input[type=number]')

test('vários itens por clique e cliques seguidos', async () => {
  const c = await abrir()
  for (let i = 1; i <= 5; i++) { fireEvent.click(card(c, i % 2 ? 'Alface' : 'Cebola')); expect(itens(c)).toBe(i) }
})
test('produto por kg sem peso: aviso visível; com peso adiciona', async () => {
  const c = await abrir()
  fireEvent.click(card(c, 'Banana')); expect(itens(c)).toBe(0)
  expect(c.querySelector('.msg').textContent).toMatch(/vendido por kg/); expect(c.querySelector('.peso.alerta')).not.toBeNull()
  fireEvent.change(c.querySelector('.sim-peso input'), { target: { value: '0.5' } })
  fireEvent.click(card(c, 'Banana')); expect(itens(c)).toBe(1)
  fireEvent.click(card(c, 'Alface')); expect(itens(c)).toBe(2)
})
test('pagamento em duas formas: PIX parcial + dinheiro com troco', async () => {
  const c = await abrir()
  fireEvent.click(card(c, 'Alface')); fireEvent.click(card(c, 'Cebola'))           // total 5,50
  tecla('F2')
  expect(document.querySelector('.pag h2').textContent).toMatch(/5,50/)
  fireEvent.change(valorCampo(), { target: { value: '2' } })                       // PIX de 2,00
  expect(document.querySelector('.pag .btn').textContent).toMatch(/Adicionar pagamento/)
  tecla('Enter')
  expect(document.querySelector('.pags').textContent).toMatch(/PIX.*2,00/); expect(document.querySelector('.falta').textContent).toMatch(/Falta: R\$\s?3,50/)
  tecla('F2')                                                                       // dinheiro
  fireEvent.change(valorCampo(), { target: { value: '10' } })
  expect(document.querySelector('.pag').textContent).toMatch(/Troco:\s*R\$\s?6,50/)
  expect(document.querySelector('.pag .btn').textContent).toMatch(/Finalizar venda/)
  tecla('Enter')
  await waitFor(() => expect(chamadas.some(x => x.o?.method === 'POST' && x.url.endsWith('/vendas'))).toBe(true))
  const body = JSON.parse(chamadas.find(x => x.o?.method === 'POST' && x.url.endsWith('/vendas')).o.body)
  expect(body.pagamentos).toEqual([{ meio: 'PIX', valor: 2 }, { meio: 'DINHEIRO', valor: 3.5 }])   // dinheiro grava o valor APLICADO, sem o troco
  expect(body.itens.length).toBe(2)
})
test('pagamento: cartão não pode passar do que falta; Delete remove o último; Esc volta', async () => {
  const c = await abrir(); fireEvent.click(card(c, 'Alface')); tecla('F2')          // total 3,50
  tecla('F3'); fireEvent.change(valorCampo(), { target: { value: '9' } })
  expect(document.querySelector('.pag .btn').disabled).toBe(true)
  fireEvent.change(valorCampo(), { target: { value: '1' } }); tecla('Enter')
  expect(document.querySelectorAll('.pags li').length).toBe(1)
  fireEvent.keyDown(document.body, { key: 'Delete' }); expect(document.querySelector('.pags')).toBeNull()
  tecla('Escape'); expect(document.querySelector('.pag')).toBeNull()
})
test('entrada de mercadoria envia o fornecedor e registra a cotação', async () => {
  render(<Entrada />)
  await screen.findByText(/CEASA Central · ceasa/)
  const [selForn, selProd] = document.querySelectorAll('select')
  fireEvent.change(selForn, { target: { value: '7' } }); fireEvent.change(selProd, { target: { value: '1' } })
  fireEvent.change(document.querySelectorAll('input[type=number]')[2], { target: { value: '550' } })   // custo total (5 embalagens x 20 = 100 → 5,50/un.)
  fireEvent.change(document.querySelectorAll('input[type=number]')[0], { target: { value: '5' } })
  fireEvent.click([...document.querySelectorAll('button')].find(b => b.textContent === 'Registrar entrada'))
  await waitFor(() => expect(chamadas.some(x => x.url.includes('/cotacoes'))).toBe(true))
  const ent = JSON.parse(chamadas.find(x => x.url.endsWith('/entrada')).o.body)
  expect(ent.fornecedorId).toBe(7); expect(ent.fornecedor).toBe('CEASA Central')
  expect(JSON.parse(chamadas.find(x => x.url.includes('/cotacoes')).o.body)).toMatchObject({ produtoNome: 'Alface Crespa', fornecedor: 'CEASA Central', preco: 5.5 })
  expect(document.body.textContent).toMatch(/NF 10/)   // histórico exibe o fornecedor da entrada anterior
})

// ---------- atalhos e ordem (NCM / PLU / multiplicar) ----------
const alt = (code, key = '') => fireEvent.keyDown(window, { altKey: true, code, key })
const abaAtiva = c => c.querySelector('.abas .ativa, .abas .on, .abas [aria-selected=true]')?.textContent
test('produtos em ordem alfabética', async () => {
  const c = await abrir()
  const nomes = [...c.querySelectorAll('button.card')].map(b => /(Alface|Banana|Cebola)\s\w+/.exec(b.textContent)[0])
  const ordenado = [...nomes].sort((a, b) => a.localeCompare(b, 'pt-BR'))
  expect(nomes.length).toBeGreaterThan(1); expect(nomes).toEqual(ordenado)
})
test('Alt + PLU + Enter adiciona o produto e mostra o PLU digitado no campo do topo', async () => {
  const c = await abrir()
  alt('Digit2', '2'); expect(c.querySelector('.plu-campo input').value).toMatch(/2/); expect(c.querySelector('.plu-campo').textContent).toMatch(/Cebola/)
  fireEvent.keyDown(window, { key: 'Enter' })
  expect(itens(c)).toBe(1); expect(c.querySelector('aside ul').textContent).toMatch(/Cebola/)
  expect(c.querySelector('img[alt*="ruteira" i]')).toBeNull()
})
test('Alt + * + número multiplica o último item (só por unidade)', async () => {
  const c = await abrir()
  fireEvent.click(card(c, 'Alface'))
  alt('NumpadMultiply', '*'); fireEvent.keyDown(window, { key: '5' }); fireEvent.keyDown(window, { key: 'Enter' })
  expect(itens(c)).toBe(1); expect(c.querySelector('aside ul').textContent).toMatch(/5\s*×/)
  expect(c.querySelector('aside').textContent).toMatch(/17,50/)                      // 5 × 3,50
})
test('Alt + * não multiplica item vendido por kg', async () => {
  const c = await abrir()
  fireEvent.change(c.querySelector('.sim-peso input'), { target: { value: '0.5' } })
  fireEvent.click(card(c, 'Banana'))
  alt('NumpadMultiply', '*'); fireEvent.keyDown(window, { key: '4' }); fireEvent.keyDown(window, { key: 'Enter' })
  expect(c.querySelector('aside ul').textContent).toMatch(/0[,.]?500/)
  expect(c.querySelector('aside').textContent).not.toMatch(/32,00/)
})
test('Ctrl + setas trocam de aba', async () => {
  const c = await abrir()
  const antes = [...c.querySelectorAll('button.card')].map(b => b.textContent).join()
  fireEvent.keyDown(window, { key: 'ArrowRight', ctrlKey: true })
  const depois = [...c.querySelectorAll('button.card')].map(b => b.textContent).join()
  expect(depois).not.toBe(antes)
  fireEvent.keyDown(window, { key: 'ArrowLeft', ctrlKey: true })
  expect([...c.querySelectorAll('button.card')].map(b => b.textContent).join()).toBe(antes)
})
