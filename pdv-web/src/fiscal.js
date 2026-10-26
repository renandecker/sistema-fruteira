// Dados obrigatórios da nota fiscal: espelho (para dar o aviso na hora) das regras que o servidor confere de novo antes de emitir (Validador.java / CadastroFiscal.java).
import { cpfValido, somenteDigitos } from './cpf.js'

export function cnpjValido(s) {
  const c = somenteDigitos(s)
  if (c.length !== 14 || /^(\d)\1{13}$/.test(c)) return false
  const dv = n => { let t = 0, p = n - 7; for (let i = 0; i < n; i++) { t += +c[i] * p; p = p === 2 ? 9 : p - 1 } const r = t % 11; return r < 2 ? 0 : 11 - r }
  return dv(12) === +c[12] && dv(13) === +c[13]
}
export const docValido = s => { const c = somenteDigitos(s); return c.length === 11 ? cpfValido(c) : c.length === 14 && cnpjValido(c) }
export const tipoDoc = s => { const n = somenteDigitos(s).length; return n === 11 ? 'CPF' : n === 14 ? 'CNPJ' : '' }
export function mascaraDoc(s) {
  const c = somenteDigitos(s).slice(0, 14)
  if (c.length <= 11) return c.replace(/^(\d{3})(\d)/, '$1.$2').replace(/^(\d{3})\.(\d{3})(\d)/, '$1.$2.$3').replace(/\.(\d{3})(\d)/, '.$1-$2')
  return c.replace(/^(\d{2})(\d)/, '$1.$2').replace(/^(\d{2})\.(\d{3})(\d)/, '$1.$2.$3').replace(/\.(\d{3})(\d)/, '.$1/$2').replace(/(\d{4})(\d)/, '$1-$2')
}

/** IE do RS: 10 dígitos, o último é o dígito verificador (módulo 11, pesos 2-9-8-7-6-5-4-3-2). Outras UFs: 2 a 14 dígitos. "ISENTO" vale no cadastro de cliente/fornecedor. */
export function ieValida(ie, uf = 'RS') {
  const d = somenteDigitos(ie)
  if (d.length < 2 || d.length > 14) return false
  if (String(uf).toUpperCase() !== 'RS') return true
  if (d.length !== 10) return false
  const peso = [2, 9, 8, 7, 6, 5, 4, 3, 2]; let t = 0
  for (let i = 0; i < 9; i++) t += +d[i] * peso[i]
  let dv = 11 - t % 11; if (dv >= 10) dv = 0
  return dv === +d[9]
}

/** Campos de endereço que faltam (vazio = completo ou nem começou). */
export function enderecoFalta(e = {}) {
  const v = k => String(e[k] ?? '').trim()
  if (!['logradouro', 'numero', 'bairro', 'municipio', 'cMun', 'uf', 'cep'].some(v)) return []
  const f = []
  if (!v('logradouro')) f.push('logradouro'); if (!v('numero')) f.push('número'); if (!v('bairro')) f.push('bairro'); if (!v('municipio')) f.push('município')
  if (!/^[A-Za-z]{2}$/.test(v('uf'))) f.push('UF'); if (somenteDigitos(e.cMun).length !== 7) f.push('código IBGE'); if (somenteDigitos(e.cep).length !== 8) f.push('CEP')
  return f
}
const semEndereco = e => ['logradouro', 'numero', 'bairro', 'municipio', 'cMun', 'uf', 'cep'].every(k => !String(e?.[k] ?? '').trim())

/** Produto que vai em NFC-e: NCM de 8 dígitos e válido; classificação própria (se houver) coerente. */
export function problemasProduto(p) {
  const l = []
  if (!/^\d{8}$/.test(String(p.ncm || ''))) l.push('sem NCM de 8 dígitos')
  else if (p.ncmStatus === 'INVALIDO' || p.ncmStatus === 'VENCIDO') l.push(`NCM ${p.ncmStatus === 'VENCIDO' ? 'vencido' : 'inválido'}`)
  if (p.cfop && !/^5\d{3}$/.test(p.cfop)) l.push('CFOP inválido')
  if (p.cstIbsCbs && !p.cClassTrib || !p.cstIbsCbs && p.cClassTrib) l.push('IBS/CBS: informe CST e cClassTrib juntos')
  return l
}
export function problemasCliente(c) {
  const l = []; const pj = somenteDigitos(c.cpf).length === 14
  if (!docValido(c.cpf)) l.push('documento inválido')
  const f = enderecoFalta(c); if (f.length) l.push('endereço incompleto: falta ' + f.join(', '))
  if (pj && semEndereco(c)) l.push('empresa sem endereço')
  if (pj && !c.ie) l.push('empresa sem Inscrição Estadual (ou ISENTO)')
  return l
}
export function problemasFornecedor(f) {
  const l = []
  if (!docValido(f.documento)) l.push(f.documento ? 'CNPJ/CPF inválido' : 'sem CNPJ/CPF')
  if (!f.ie) l.push('sem Inscrição Estadual (ou ISENTO)')
  if (semEndereco(f)) l.push('sem endereço'); else { const x = enderecoFalta(f); if (x.length) l.push('endereço incompleto: falta ' + x.join(', ')) }
  return l
}
/** Lista { quem, problemas } só de quem tem problema, para o painel "Conferência fiscal". */
export const listarProblemas = (itens, nome, fn) => (itens || []).filter(x => x.ativo !== false).map(x => ({ quem: nome(x), problemas: fn(x) })).filter(x => x.problemas.length)
