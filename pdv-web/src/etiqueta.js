import { cfg } from './lib.jsx'

// EAN-13 de peso/preço variável (balanças computadoras, ex.: Toledo Prix): 2 + código do item + valor + dígito verificador.
// O número de dígitos do código (4, 5 ou 6) e se o valor é PREÇO TOTAL (centavos) ou PESO (gramas) são configuráveis.
export const dv13 = s => (10 - [...s].reduce((t, c, i) => t + +c * (i % 2 ? 3 : 1), 0) % 10) % 10

export function formatoEtiqueta() {
  const c = cfg(); const nCod = [4, 5, 6].includes(+c.etiquetaCodigo) ? +c.etiquetaCodigo : 6
  return { nCod, nVal: 11 - nCod, tipo: c.etiquetaValor === 'peso' ? 'peso' : 'preco' }
}
export function codificarEtiqueta(plu, valorTotal, pesoKg) {
  const { nCod, nVal, tipo } = formatoEtiqueta()
  const v = tipo === 'peso' ? Math.round(pesoKg * 1000) : Math.round(valorTotal * 100)
  if (!plu || String(plu).length > nCod || !(v > 0) || String(v).length > nVal) return null
  const base = '2' + String(plu).padStart(nCod, '0') + String(v).padStart(nVal, '0')
  return base + dv13(base)
}
export function decodificarEtiqueta(t) {
  if (!/^2\d{12}$/.test(t) || dv13(t.slice(0, 12)) !== +t[12]) return null
  const { nCod, tipo } = formatoEtiqueta(); const v = +t.slice(1 + nCod, 12)
  return { plu: +t.slice(1, 1 + nCod), valor: tipo === 'preco' ? v / 100 : null, peso: tipo === 'peso' ? v / 1000 : null }
}
