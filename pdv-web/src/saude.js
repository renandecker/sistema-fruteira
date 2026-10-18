// Rotina de saúde dos pagamentos (Pix e TEF): textos e nível do aviso. Função pura, testada em tests/saude.test.jsx.
export const ROTULO = { OK: 'Funcionando', ALERTA: 'Atenção', FALHA: 'Com problema', NAO_CONFIGURADO: 'Não definido' }
export const ICONE = { OK: '✅', ALERTA: '⚠️', FALHA: '❌', NAO_CONFIGURADO: '➖' }

/** 'ok' | 'alerta' | 'falha' | 'nenhum' (sem verificação ainda). */
export function nivelSaude(estado) {
  const g = estado?.ultima?.geral
  return g === 'FALHA' ? 'falha' : g === 'ALERTA' ? 'alerta' : g ? 'ok' : 'nenhum'
}

/** Frase curta para o banner do PDV: só os itens com problema (nada se estiver tudo bem). */
export function textoSaude(estado) {
  const itens = (estado?.ultima?.itens || []).filter(i => i.status === 'FALHA' || i.status === 'ALERTA')
  if (!itens.length) return ''
  return itens.map(i => `${i.nome}${i.status === 'FALHA' ? ' com problema' : ': atenção'}`).join(' · ')
}

export const horaCurta = iso => { try { return new Date(iso).toLocaleTimeString('pt-BR', { hour: '2-digit', minute: '2-digit' }) } catch { return '' } }

export const ICONE_MEIO = { PIX: '💠', TEF: '📟' }
/** Selos para o PDV: um por meio (Pix/TEF) que está fora do ar (FALHA) ou com atenção. Meio em ordem; nada se estiver tudo bem ou não definido. */
export function selosSaude(estado) {
  return (estado?.ultima?.itens || []).filter(i => i.status === 'FALHA' || i.status === 'ALERTA')
    .map(i => ({ nome: i.nome, icone: ICONE_MEIO[i.nome] || '⚠️', fora: i.status === 'FALHA', texto: `${i.nome} ${i.status === 'FALHA' ? 'fora' : 'com aviso'}`, detalhe: i.detalhe || '' }))
}
