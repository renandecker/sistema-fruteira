import React from 'react'
import { render, screen, fireEvent, waitFor } from '@testing-library/react'
import PDV from '../src/PDV.jsx'
import { Nfce } from '../src/paginas2.jsx'
import { cpfValido, mascaraCpf, statusCpf } from '../src/cpf.js'
import { montarEmissao, situacaoNota } from '../src/nfce.js'

const produtos = [{ id: 1, nome: 'Cebola Branca', unidade: 'UN', precoVarejo: 2, plu: 2, ncm: '07031019', ncmStatus: 'VALIDO', categoria: 'Verduras', ativo: true }]
const CPF = '52998224725'

test('CPF: dígitos verificadores, repetidos, máscara e status', () => {
  expect(cpfValido('529.982.247-25')).toBe(true)
  expect(cpfValido('52998224724')).toBe(false)
  expect(cpfValido('11111111111')).toBe(false)
  expect(mascaraCpf('52998224725')).toBe('529.982.247-25')
  expect(statusCpf('')).toBe(''); expect(statusCpf('529')).toBe('incompleto'); expect(statusCpf('52998224724')).toBe('invalido'); expect(statusCpf(CPF)).toBe('ok')
})

test('montarEmissao: itens com NCM, CPF só dígitos e pagamentos com tPag', () => {
  const v = { id: 5, total: 4, itens: [{ produtoId: 1, nome: 'Cebola Branca', quantidade: 2, precoUnit: 2, subtotal: 4 }], pagamentos: [{ meio: 'PIX', valor: 4 }] }
  const e = montarEmissao(v, produtos, '529.982.247-25')
  expect(e.cpf).toBe(CPF); expect(e.itens[0]).toMatchObject({ ncm: '07031019', valor: 4 }); expect(e.pagamentos[0].tPag).toBe('17')
  expect(montarEmissao(v, produtos, '').cpf).toBeNull()
})

test('situacaoNota: 100, contingência, rejeição de CPF e outras', () => {
  expect(situacaoNota({ status: 'AUTORIZADA' })).toBe('ok')
  expect(situacaoNota({ status: 'CONTINGENCIA' })).toBe('contingencia')
  expect(situacaoNota({ status: 'REJEITADA', cStat: 237, xMotivo: 'CPF do destinatario invalido' })).toBe('corrigir-cpf')
  expect(situacaoNota({ status: 'REJEITADA', cStat: 999, xMotivo: 'Inconsistência no cadastro do destinatário' })).toBe('corrigir-cpf')
  expect(situacaoNota({ status: 'REJEITADA', cStat: 528, xMotivo: 'Total' })).toBe('rejeitada')
})

let chamadas, resposta
beforeEach(() => {
  chamadas = []; resposta = { id: 1, numero: 1, status: 'AUTORIZADA', cStat: 100, xMotivo: 'Autorizado o uso da NF-e', cpf: CPF, chave: '4'.repeat(44), qrCode: 'https://x?p=1' }
  Element.prototype.scrollIntoView = () => {}; window.print = () => {}
  globalThis.fetch = vi.fn(async (url, o) => {
    chamadas.push({ url, o })
    let j = []
    if (url.includes('/produtos')) j = produtos; else if (url.includes('/categorias')) j = [{ id: 1, nome: 'Verduras' }]
    else if (url.endsWith('/vendas') && o?.method === 'POST') j = { id: 9, total: 2, itens: [{ produtoId: 1, nome: 'Cebola Branca', quantidade: 1, precoUnit: 2, subtotal: 2 }], pagamentos: [{ meio: 'DINHEIRO', valor: 2 }] }
    else if (url.endsWith('/nfce/emitir')) j = resposta
    else if (url.includes('/corrigir-cpf/')) j = { ...resposta, status: 'AUTORIZADA', cStat: 100, cpf: JSON.parse(o.body).cpf }
    return { ok: true, json: async () => j, text: async () => '' }
  })
})

async function vender(cpf) {
  render(<PDV />); await screen.findAllByText(/Cebola Branca/)
  fireEvent.click(document.querySelector('button.card'))
  if (cpf) fireEvent.change(screen.getByLabelText('CPF na nota'), { target: { value: cpf } })
  fireEvent.click(screen.getByText(/Pagar \(F2\)/))
}

test('CPF incompleto ou inválido bloqueia o pagamento; vazio deixa vender anônimo', async () => {
  await vender('123')
  expect(screen.getByText(/CPF inválido: corrija ou apague/)).toBeTruthy()
  expect(screen.queryByText(/Voltar/)).toBeNull()
  fireEvent.change(screen.getByLabelText('CPF na nota'), { target: { value: '' } })
  fireEvent.click(screen.getByText(/Pagar \(F2\)/))
  expect(await screen.findByText(/Voltar/)).toBeTruthy()
})

test('máscara no campo e venda com CPF envia só dígitos, emite a NFC-e e mostra a autorização', async () => {
  await vender('529.982.247-25')
  expect(screen.getByLabelText('CPF na nota').value).toBe('529.982.247-25')
  fireEvent.click(await screen.findByText(/Dinheiro/)); fireEvent.keyDown(window, { key: 'Enter' })
  const aviso = await screen.findByRole('dialog', { name: 'Resultado da NFC-e' })
  expect(aviso.textContent).toMatch(/NFC-e autorizada/); expect(aviso.textContent).toMatch(/529\.982\.247-25/)
  const venda = JSON.parse(chamadas.find(c => c.url.endsWith('/vendas') && c.o?.method === 'POST').o.body); expect(venda.cpf).toBe(CPF)
  const emissao = JSON.parse(chamadas.find(c => c.url.endsWith('/nfce/emitir')).o.body); expect(emissao.cpf).toBe(CPF); expect(emissao.itens[0].ncm).toBe('07031019')
})

test('rejeição de CPF pela SEFAZ: corrige no caixa e reenvia sem refazer a venda', async () => {
  resposta = { ...resposta, status: 'REJEITADA', cStat: 237, xMotivo: 'Rejeicao: CPF do destinatario invalido' }
  await vender('529.982.247-25')
  fireEvent.click(await screen.findByText(/Dinheiro/)); fireEvent.keyDown(window, { key: 'Enter' })
  await screen.findByText(/SEFAZ rejeitou o CPF/)
  fireEvent.change(screen.getByLabelText('CPF corrigido'), { target: { value: '111.444.777-35' } })
  fireEvent.click(screen.getByText('Reenviar com este CPF'))
  await waitFor(() => expect(chamadas.some(c => c.url.includes('/corrigir-cpf/1'))).toBe(true))
  expect(JSON.parse(chamadas.find(c => c.url.includes('/corrigir-cpf/1')).o.body).cpf).toBe('11144477735')
  expect(await screen.findByText(/NFC-e autorizada/)).toBeTruthy()
  expect(chamadas.filter(c => c.url.endsWith('/vendas') && c.o?.method === 'POST').length).toBe(1)
})

test('rejeição de CPF: emitir sem CPF', async () => {
  resposta = { ...resposta, status: 'REJEITADA', cStat: 237, xMotivo: 'CPF do destinatario invalido' }
  await vender('529.982.247-25')
  fireEvent.click(await screen.findByText(/Dinheiro/)); fireEvent.keyDown(window, { key: 'Enter' })
  fireEvent.click(await screen.findByText('Emitir sem CPF'))
  await waitFor(() => expect(JSON.parse(chamadas.find(c => c.url.includes('/corrigir-cpf/')).o.body).cpf).toBeNull())
})

test('contingência: aviso e DANFE com QR Code', async () => {
  resposta = { ...resposta, status: 'CONTINGENCIA', cStat: null, tpEmis: 9 }
  await vender('')
  fireEvent.click(await screen.findByText(/Dinheiro/)); fireEvent.keyDown(window, { key: 'Enter' })
  expect((await screen.findByRole('dialog', { name: 'Resultado da NFC-e' })).textContent).toMatch(/contingência/)
})
