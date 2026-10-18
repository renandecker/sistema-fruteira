// Caderno (fiado): funções puras e chamadas à API. Testadas em tests/caderno.test.jsx.
import { somenteDigitos } from './cpf.js'

export const DIAS_ATRASO = 30            // a partir daqui a dívida é destacada como "em atraso"

const j = r => r.ok ? r.json().catch(() => ({})) : r.text().then(t => Promise.reject(t))

/** Situação do cliente no caderno; null se o CPF não tem cadastro (o caderno só vale para cliente cadastrado). */
export async function situacaoCaderno(cpf) {
  const d = somenteDigitos(cpf)
  if (d.length !== 11) return null
  const r = await fetch(`/api/retaguarda/caderno/cliente/${d}?limite=15`)
  if (r.status === 404) return null
  return j(r)
}
export const receberCaderno = (cpf, valor, obs) => fetch('/api/retaguarda/caderno/pagamento', { method: 'POST', headers: { 'Content-Type': 'application/json' }, body: JSON.stringify({ cpf: somenteDigitos(cpf), valor, obs }) }).then(j)
export const perdoarCaderno = (cpf, valor, obs) => fetch('/api/retaguarda/caderno/perdao', { method: 'POST', headers: { 'Content-Type': 'application/json' }, body: JSON.stringify({ cpf: somenteDigitos(cpf), valor, obs }) }).then(j)
export const cancelarLancamento = id => fetch(`/api/retaguarda/caderno/${id}/cancelar`, { method: 'POST' }).then(j)

/** 'ok' sem dívida · 'devendo' · 'atraso' (dívida mais velha há DIAS_ATRASO dias ou mais) · 'limite' (já no teto) */
export function nivelAlerta(sit) {
  if (!sit || !(sit.saldo > 0)) return 'ok'
  if ((sit.diasAtraso ?? 0) >= DIAS_ATRASO) return 'atraso'
  if (sit.disponivel != null && sit.disponivel <= 0) return 'limite'
  return 'devendo'
}

/** Mensagem curta do alerta do PDV (vazio = sem alerta). */
export function textoAlerta(sit, brl) {
  if (!sit) return ''
  const n = nivelAlerta(sit)
  if (n === 'ok') return ''
  const dias = sit.diasAtraso > 0 ? ` · há ${sit.diasAtraso} dia${sit.diasAtraso === 1 ? '' : 's'}` : ''
  const lim = n === 'limite' ? ' · limite do caderno atingido' : ''
  return `${sit.nome} está devendo ${brl(sit.saldo)}${dias}${lim}`
}

/** Confere se dá para anotar o valor no caderno. Devolve o motivo do bloqueio ou '' se pode. */
export function motivoBloqueio(sit, valor, brl) {
  if (!sit) return 'Informe o CPF de um cliente cadastrado para usar o caderno.'
  if (!(valor > 0)) return 'Informe o valor a anotar.'
  if (valor > sit.disponivel + 0.0001) return `Limite do caderno: ${sit.nome} pode dever até ${brl(sit.limite)} (já deve ${brl(sit.saldo)}; cabem ${brl(sit.disponivel)}).`
  return ''
}

/**
 * Relatório de clientes: junta quem COMPRA (vendas) com quem DEVE (caderno), por CPF.
 *  compras: [{ cpf, compras, total, ticketMedio, ultimaCompra, noCaderno }] · devedores: { lista: [{ cpf, nome, saldo, diasAtraso, ... }] }
 *  nomes: { [cpf]: nome } (cadastro de clientes).
 */
export function montarRelatorioClientes(compras = [], devedores = { lista: [] }, nomes = {}) {
  const dev = new Map((devedores.lista || []).map(d => [d.cpf, d]))
  const linhas = new Map()
  for (const c of compras) linhas.set(c.cpf, { cpf: c.cpf, nome: nomes[c.cpf] || dev.get(c.cpf)?.nome || '', compras: c.compras, total: c.total, ticketMedio: c.ticketMedio, ultimaCompra: c.ultimaCompra, fiadoNoPeriodo: c.noCaderno || 0, deve: 0, diasAtraso: 0 })
  for (const d of dev.values()) {
    const l = linhas.get(d.cpf) || { cpf: d.cpf, nome: d.nome, compras: 0, total: 0, ticketMedio: 0, ultimaCompra: null, fiadoNoPeriodo: 0 }
    l.deve = d.saldo; l.diasAtraso = d.diasAtraso; l.nome = l.nome || d.nome
    linhas.set(d.cpf, l)
  }
  const todos = [...linhas.values()].sort((a, b) => b.total - a.total || b.deve - a.deve)
  const porCompra = todos.filter(l => l.compras > 0).sort((a, b) => b.total - a.total)
  const porDivida = todos.filter(l => l.deve > 0).sort((a, b) => b.deve - a.deve)
  const totalComprado = porCompra.reduce((s, l) => s + l.total, 0)
  const aReceber = porDivida.reduce((s, l) => s + l.deve, 0)
  return {
    linhas: todos, porCompra, porDivida,
    kpis: {
      totalComprado: Math.round(totalComprado * 100) / 100,
      clientesQueCompraram: porCompra.length,
      totalAReceber: Math.round(aReceber * 100) / 100,
      devedores: porDivida.length,
      emAtraso: porDivida.filter(l => l.diasAtraso >= DIAS_ATRASO).length,
      maiorDevedor: porDivida[0] || null,
      melhorCliente: porCompra[0] || null,
      pesoDoFiado: totalComprado > 0 ? Math.round((aReceber / totalComprado) * 1000) / 10 : 0,   // % do que foi comprado que ainda está no caderno
    },
  }
}

/** CSV (; separador, vírgula decimal: abre direto no Excel pt-BR). */
export function csvClientes(linhas) {
  const n = v => String(Number(v ?? 0).toFixed(2)).replace('.', ',')
  const cab = ['CPF', 'Cliente', 'Compras', 'Total comprado', 'Ticket médio', 'Última compra', 'Deve no caderno', 'Dias em aberto']
  const rows = linhas.map(l => [l.cpf, (l.nome || '').replace(/;/g, ','), l.compras, n(l.total), n(l.ticketMedio), l.ultimaCompra ? String(l.ultimaCompra).slice(0, 10) : '', n(l.deve), l.diasAtraso || 0])
  return [cab, ...rows].map(r => r.join(';')).join('\n')
}
