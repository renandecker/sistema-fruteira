import { cfg, api, post } from './lib.jsx'

// Pix integrado ao PSP: o React só fala com o servidor (/api/vendas/pix); chaves, certificados e segredos do PSP ficam no backend.
export const pixAtivo = () => cfg().pix === 'psp'
const R = '/api/vendas/pix'
export const intervaloPix = () => +cfg().pixIntervaloMs || 3000          // polling a cada 3 s (o webhook do PSP atualiza o servidor em tempo real)

export const criarCobranca = (valor, descricao) => post(`${R}/cobrancas`, { valor, descricao, terminal: cfg().tefTerminal || 'PDV1' })
export const consultarCobranca = id => api(`${R}/cobrancas/${id}`)
export const cancelarCobranca = id => post(`${R}/cobrancas/${id}/cancelar`)
export const simularPagamento = id => post(`${R}/simulador/cobrancas/${id}/pagar`)       // só com PIX_PROVIDER=simulador
/** Quantos Pix já foram recebidos mas não viraram venda (ex.: o caixa travou). */
export async function pixPendencias() {
  if (!pixAtivo()) return 0
  try { return (await api(`${R}/pendencias`)).length } catch { return 0 }
}
