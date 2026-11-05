import React from 'react'
import { render, screen, fireEvent, waitFor } from '@testing-library/react'
import { test, expect, vi, beforeEach } from 'vitest'
import { ImprimirDanfe } from '../src/Danfe.jsx'
import { situacaoImpressao } from '../src/nfce.js'

const dados = (tentativas = []) => ({ nota: { id: 7, numero: 12, status: 'AUTORIZADA', cStat: 100, xMotivo: 'Autorizado', chave: '4' + '1'.repeat(43), protocolo: '1234', valor: 10, data: '2026-11-03T10:00:00', tpAmb: 2 },
  itens: [{ nome: 'Banana', quantidade: 2, unidade: 'KG', precoUnit: 5, subtotal: 10 }], pagamentos: [{ meio: 'DINHEIRO', valor: 10 }], empresa: { nome: 'Fruteira', cnpj: '12345678000190' }, tentativas })
let chamadas, tent
beforeEach(() => {
  chamadas = []; tent = []; localStorage.setItem('cfg', JSON.stringify({ tefTerminal: 'PDV2' })); window.print = vi.fn()
  vi.stubGlobal('fetch', vi.fn(async (url, o = {}) => {
    chamadas.push({ url, method: o.method || 'GET', body: o.body ? JSON.parse(o.body) : null })
    const ok = b => ({ ok: true, status: 200, json: async () => b, text: async () => JSON.stringify(b) })
    if (url.endsWith('/danfe')) return ok(dados(tent))
    if (url.endsWith('/impressoes') && o.method === 'POST') { const t = { id: 90 + tent.length, tentativa: tent.length + 1, origem: JSON.parse(o.body).origem, resultado: 'ENVIADA', usuario: 'ana', terminal: 'PDV2', data: '2026-11-03T10:01:00' }; tent.push(t); return ok(t) }
    if (url.includes('/impressoes/') && url.endsWith('/resultado')) { const b = JSON.parse(o.body); const t = tent[tent.length - 1]; t.resultado = b.resultado; t.erro = b.erro; return ok(t) }
    return { ok: false, status: 404, text: async () => 'nada', json: async () => ({}) }
  }))
})

test('imprime a nota guardada, registra a tentativa e confirma que saiu', async () => {
  render(<ImprimirDanfe notaId={7} origem="EMISSAO" onFechar={() => {}} />)
  fireEvent.click(await screen.findByText('Imprimir DANFE'))
  await waitFor(() => expect(window.print).toHaveBeenCalled())
  const reg = chamadas.find(c => c.method === 'POST' && c.url.endsWith('/nfce/7/impressoes'))
  expect(reg.body).toEqual({ origem: 'EMISSAO', terminal: 'PDV2' })
  fireEvent.click(await screen.findByText('Sim, saiu'))
  await waitFor(() => expect(screen.getByText(/Impressão confirmada/)).toBeTruthy())
  expect(chamadas.find(c => c.url.endsWith('/resultado')).body).toEqual({ resultado: 'CONFIRMADA', erro: null })
  expect(screen.getByText('Confirmada')).toBeTruthy()
})

test('falha de impressão: registra o motivo e deixa tentar de novo como reimpressão', async () => {
  render(<ImprimirDanfe notaId={7} origem="EMISSAO" onFechar={() => {}} />)
  fireEvent.click(await screen.findByText('Imprimir DANFE'))
  await screen.findByText('Não saiu — registrar falha')
  fireEvent.change(screen.getByLabelText('Motivo da falha'), { target: { value: 'Sem papel' } })
  fireEvent.click(screen.getByText('Não saiu — registrar falha'))
  await waitFor(() => expect(chamadas.find(c => c.url.endsWith('/resultado')).body).toEqual({ resultado: 'FALHA', erro: 'Sem papel' }))
  fireEvent.click(await screen.findByText('Imprimir de novo'))
  await waitFor(() => expect(window.print).toHaveBeenCalledTimes(2))
  const regs = chamadas.filter(c => c.method === 'POST' && c.url.endsWith('/impressoes'))
  expect(regs.map(r => r.body.origem)).toEqual(['EMISSAO', 'REIMPRESSAO'])
  expect(screen.getAllByText('Sem papel').length).toBeGreaterThanOrEqual(2)   // opção do seletor + linha do histórico
})

test('sem servidor: imprime com os dados da tela e avisa que não ficou registrado', async () => {
  vi.stubGlobal('fetch', vi.fn(async () => { throw new Error('offline') }))
  const fb = { nota: dados().nota, itens: dados().itens, pagamentos: dados().pagamentos }
  render(<ImprimirDanfe notaId={7} origem="EMISSAO" fallback={fb} onFechar={() => {}} />)
  expect(await screen.findByText(/não ficará registrada/)).toBeTruthy()
  fireEvent.click(screen.getByText('Imprimir DANFE'))
  await waitFor(() => expect(window.print).toHaveBeenCalled())
})

test('situacaoImpressao resume a nota para a lista', () => {
  expect(situacaoImpressao({})).toBe('nunca')
  expect(situacaoImpressao({ tentativasImpressao: 2, ultimaImpressao: 'FALHA' })).toBe('falhou')
  expect(situacaoImpressao({ tentativasImpressao: 1, ultimaImpressao: 'ENVIADA' })).toBe('sem-confirmacao')
  expect(situacaoImpressao({ tentativasImpressao: 3, ultimaImpressao: 'FALHA', impressa: true })).toBe('impressa')
})
