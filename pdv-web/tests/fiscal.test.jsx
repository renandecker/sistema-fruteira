import React from 'react'
import { render, screen, fireEvent, waitFor } from '@testing-library/react'
import { cnpjValido, docValido, tipoDoc, mascaraDoc, ieValida, enderecoFalta, problemasProduto, problemasCliente, problemasFornecedor } from '../src/fiscal.js'
import { montarEmissao } from '../src/nfce.js'
import { Nfce, Clientes } from '../src/paginas2.jsx'
import { ModalFiscalProduto } from '../src/CadastroFiscal.jsx'

const END = { logradouro: 'Rua A', numero: '10', bairro: 'Centro', municipio: 'Conventos', cMun: '4321105', uf: 'RS', cep: '95865000' }

test('CNPJ, documento e máscara', () => {
  expect(cnpjValido('12.345.678/0001-95')).toBe(true); expect(cnpjValido('12345678000196')).toBe(false); expect(cnpjValido('11111111111111')).toBe(false)
  expect(docValido('529.982.247-25')).toBe(true); expect(docValido('12345678000195')).toBe(true); expect(docValido('123')).toBe(false)
  expect(tipoDoc('52998224725')).toBe('CPF'); expect(tipoDoc('12345678000195')).toBe('CNPJ'); expect(tipoDoc('1')).toBe('')
  expect(mascaraDoc('52998224725')).toBe('529.982.247-25'); expect(mascaraDoc('12345678000195')).toBe('12.345.678/0001-95')
})

test('IE do RS e endereço', () => {
  expect(ieValida('017/0123456', 'RS')).toBe(true); expect(ieValida('0170123457', 'RS')).toBe(false); expect(ieValida('12345', 'RS')).toBe(false); expect(ieValida('123456', 'SP')).toBe(true)
  expect(enderecoFalta({})).toEqual([]); expect(enderecoFalta(END)).toEqual([])
  expect(enderecoFalta({ ...END, bairro: '', cep: '123' })).toEqual(['bairro', 'CEP'])
})

test('o que falta em produto, cliente e fornecedor', () => {
  expect(problemasProduto({ ncm: '08039000', ncmStatus: 'VALIDO' })).toEqual([])
  expect(problemasProduto({ ncm: '', ncmStatus: 'AUSENTE' })).toEqual(['sem NCM de 8 dígitos'])
  expect(problemasProduto({ ncm: '08039000', ncmStatus: 'VENCIDO', cfop: '6102' })).toEqual(['NCM vencido', 'CFOP inválido'])
  expect(problemasProduto({ ncm: '08039000', cstIbsCbs: '000' })).toEqual(['IBS/CBS: informe CST e cClassTrib juntos'])
  expect(problemasCliente({ cpf: '52998224725' })).toEqual([])
  expect(problemasCliente({ cpf: '12345678000195' })).toEqual(['empresa sem endereço', 'empresa sem Inscrição Estadual (ou ISENTO)'])
  expect(problemasCliente({ cpf: '12345678000195', ie: 'ISENTO', ...END })).toEqual([])
  expect(problemasFornecedor({ documento: '12345678000195', ie: '0170123456', ...END })).toEqual([])
  expect(problemasFornecedor({ documento: '', ie: '' })).toEqual(['sem CNPJ/CPF', 'sem Inscrição Estadual (ou ISENTO)', 'sem endereço'])
})

test('montarEmissao leva a classificação fiscal do produto e as parcelas do cartão', () => {
  const venda = { id: 9, total: 12, itens: [{ produtoId: 1, nome: 'Maçã', quantidade: 1, precoUnit: 12, subtotal: 12 }], pagamentos: [{ meio: 'CREDITO', valor: 12, parcelas: 3, nsu: '1', tefId: 5, cnpjCredenciadora: '12345678000195', bandeira: 'visa', autorizacao: 'A1' }] }
  const e = montarEmissao(venda, [{ id: 1, plu: 5, ncm: '08081000', unidade: 'KG', cfop: '5405', cstIcms: '500', origemMercadoria: '1', cstPis: '49', cstCofins: '49', cstIbsCbs: '000', cClassTrib: '000001' }], '12345678000195')
  expect(e.itens[0]).toMatchObject({ cfop: '5405', cstIcms: '500', origem: '1', cstPis: '49', cstCofins: '49', cstIbsCbs: '000', cClassTrib: '000001' })
  expect(e.pagamentos[0]).toMatchObject({ tPag: '03', parcelas: 3, cnpjCredenciadora: '12345678000195', cAut: 'A1' })
  expect(e.cpf).toBe('12345678000195')
  const sem = montarEmissao(venda, [{ id: 1, plu: 5, ncm: '08081000' }], '')
  expect(sem.itens[0].cfop).toBeNull(); expect(sem.itens[0].cstIcms).toBeNull()
})

const resp = mapa => vi.fn(url => { const k = Object.keys(mapa).find(x => String(url).includes(x)); return Promise.resolve({ ok: true, json: () => Promise.resolve(k ? mapa[k] : []) }) })

test('NFC-e: painel de conferência mostra emitente, produtos, clientes e fornecedores com problema', async () => {
  global.fetch = resp({
    '/nfce/status': { real: true, ambiente: 'HOMOLOGACAO', pendencias: [], problemasEmitente: ['FISCAL_CEP: CEP com 8 dígitos obrigatório'], ibsCbs: true, exigeTef: true },
    '/catalogo/produtos': [{ id: 1, plu: 3, nome: 'Alface', ncm: '', ncmStatus: 'AUSENTE', ativo: true }],
    '/clientes': [{ id: 1, nome: 'Mercado do Zé', cpf: '12345678000195', ativo: true }],
    '/fornecedores': [{ id: 1, nome: 'CEASA Sul', documento: '', ativo: true }],
  })
  render(<Nfce />)
  const painel = await screen.findByRole('status', { name: 'Conferência fiscal' })
  await waitFor(() => expect(painel.textContent).toMatch(/Alface: sem NCM/))
  expect(painel.textContent).toMatch(/corrija antes de emitir/); expect(painel.textContent).toMatch(/FISCAL_CEP/)
  expect(painel.textContent).toMatch(/Mercado do Zé: empresa sem endereço/); expect(painel.textContent).toMatch(/CEASA Sul: sem CNPJ\/CPF/)
  expect(painel.textContent).toMatch(/IBS\/CBS \(reforma tributária\) ligado/); expect(painel.textContent).toMatch(/dados do TEF/)
})

test('NFC-e: tudo completo mostra a conferência em verde', async () => {
  global.fetch = resp({ '/nfce/status': { real: true, ambiente: 'HOMOLOGACAO', pendencias: [], problemasEmitente: [] }, '/catalogo/produtos': [{ id: 1, plu: 3, nome: 'Alface', ncm: '07051100', ncmStatus: 'VALIDO', ativo: true }] })
  render(<Nfce />)
  const painel = await screen.findByRole('status', { name: 'Conferência fiscal' })
  expect(painel.textContent).toMatch(/dados obrigatórios completos/)
})

test('Clientes: só habilita Cadastrar com CPF ou CNPJ válido', async () => {
  global.fetch = resp({})
  render(<Clientes />)
  fireEvent.change(screen.getByPlaceholderText('Nome'), { target: { value: 'Empresa X' } })
  const doc = screen.getByLabelText('CPF ou CNPJ do cliente'); const btn = screen.getByRole('button', { name: 'Cadastrar' })
  fireEvent.change(doc, { target: { value: '12345678000196' } }); expect(btn.disabled).toBe(true)
  fireEvent.change(doc, { target: { value: '12345678000195' } }); expect(btn.disabled).toBe(false); expect(doc.value).toBe('12.345.678/0001-95')
})

test('Produto: salva a classificação fiscal (PUT /fiscal) e mostra o erro do servidor', async () => {
  const chamadas = []
  global.fetch = vi.fn((url, o) => { chamadas.push([o?.method, url, o?.body]); return Promise.resolve({ ok: false, text: () => Promise.resolve('CFOP deve ter 4 dígitos') }) })
  render(<ModalFiscalProduto produto={{ id: 7, nome: 'Banana', ncm: '08039000' }} onSalvo={() => {}} onFechar={() => {}} />)
  fireEvent.change(screen.getByLabelText(/CFOP/), { target: { value: '5102' } })
  fireEvent.change(screen.getByLabelText(/Alíquota ICMS/), { target: { value: '12,5' } })
  fireEvent.click(screen.getByRole('button', { name: 'Salvar' }))
  expect((await screen.findByRole('alert')).textContent).toMatch(/CFOP deve ter 4 dígitos/)
  expect(chamadas[0][0]).toBe('PUT'); expect(chamadas[0][1]).toBe('/api/catalogo/produtos/7/fiscal')
  expect(JSON.parse(chamadas[0][2])).toMatchObject({ cfop: '5102', aliqIcms: 12.5, aliqPis: null })
})
