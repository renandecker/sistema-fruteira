import { gerarDuplicatas, ajustarPagamentos, montarNfe } from '../src/nfe.js'

const venda = { id: 7, itens: [{ produtoId: 1, nome: 'Banana', quantidade: 2, precoUnit: 5, subtotal: 10 }, { produtoId: 2, nome: 'Maçã', quantidade: 1, precoUnit: 3.33, subtotal: 3.33 }], pagamentos: [{ meio: 'DINHEIRO', valor: 20 }] }
const prods = [{ id: 1, nome: 'Banana', ncm: '08039000' }, { id: 2, nome: 'Maçã', ncm: '08081000' }]

test('duplicatas somam o total exato e vencem a cada 30 dias', () => {
  const d = gerarDuplicatas(100, 3, { hoje: new Date(2026, 0, 1) })
  expect(d).toHaveLength(3); expect(d.reduce((s, p) => s + Math.round(p.valor * 100), 0)).toBe(10000)
  expect(d[0].venc).toBe('2026-01-31'); expect(d[1].venc).toBe('2026-03-02')
})

test('pagamentos cortados no total (sem troco)', () => {
  expect(ajustarPagamentos([{ tPag: '01', valor: 20 }], 13.33)).toEqual([{ tPag: '01', valor: 13.33 }])
  expect(ajustarPagamentos([{ tPag: '01', valor: 5 }, { tPag: '03', valor: 20 }], 10)).toEqual([{ tPag: '01', valor: 5 }, { tPag: '03', valor: 5 }])
})

test('montarNfe: frete, desconto e prazo', () => {
  const c = montarNfe(venda, prods, { empresaDoc: '12.345.678/0001-95', aPrazo: true, parcelas: 2, modFrete: 0, vFrete: '10', vDesc: '3.33', hoje: new Date(2026, 0, 1) })
  expect(c.vendaId).toBe(7); expect(c.clienteDoc).toBe('12345678000195'); expect(c.modFrete).toBe(0)
  expect(c.duplicatas.reduce((s, p) => s + p.valor, 0)).toBeCloseTo(20, 2)
  expect(c.pagamentos).toEqual([{ tPag: '15', valor: 20 }])
  const v = montarNfe(venda, prods, { empresaDoc: '12345678000195' })
  expect(v.modFrete).toBe(9); expect(v.vFrete).toBeNull(); expect(v.duplicatas).toEqual([])
})
