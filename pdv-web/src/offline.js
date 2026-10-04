// Fila offline: vendas ficam no IndexedDB/localStorage e sincronizam quando a rede volta.
const KEY = 'fila-vendas'
export const fila = () => JSON.parse(localStorage.getItem(KEY) || '[]')
export const enfileirar = v => localStorage.setItem(KEY, JSON.stringify([...fila(), v]))
export async function sincronizar(enviar) {
  const restantes = []
  for (const v of fila()) { try { await enviar(v) } catch { restantes.push(v) } }
  localStorage.setItem(KEY, JSON.stringify(restantes)); return restantes.length
}
