// CPF do consumidor (Nota Fiscal Gaúcha) — mesma regra do validador Java (Cpf.java): 11 dígitos, não repetidos, dígitos verificadores.
export const somenteDigitos = s => String(s ?? '').replace(/\D/g, '')

export function cpfValido(s) {
  const c = somenteDigitos(s)
  if (c.length !== 11 || /^(\d)\1{10}$/.test(c)) return false
  const dv = n => { let t = 0; for (let i = 0; i < n; i++) t += +c[i] * (n + 1 - i); const r = t % 11; return r < 2 ? 0 : 11 - r }
  return dv(9) === +c[9] && dv(10) === +c[10]
}

/** 123.456.789-09 enquanto digita (aceita colar com ou sem pontuação). */
export function mascaraCpf(s) {
  const c = somenteDigitos(s).slice(0, 11)
  return c.replace(/^(\d{3})(\d)/, '$1.$2').replace(/^(\d{3})\.(\d{3})(\d)/, '$1.$2.$3').replace(/\.(\d{3})(\d)/, '.$1-$2')
}

/** '' (vazio = venda sem CPF) | 'incompleto' | 'invalido' | 'ok' */
export function statusCpf(s) {
  const c = somenteDigitos(s)
  if (!c) return ''
  if (c.length < 11) return 'incompleto'
  return cpfValido(c) ? 'ok' : 'invalido'
}
