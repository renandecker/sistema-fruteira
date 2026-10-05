import { useEffect, useRef, useState } from 'react'
import { cfg } from './lib.jsx'

// Leitura de balança por cabo serial (Web Serial: Chrome/Edge, em localhost ou HTTPS). Parâmetros em Configurações → Balança e periféricos.
//  protocolo "continuo": a balança envia o peso sozinha (ex.: "  0,350"); lê o último número decimal.
//  protocolo "enq":      o sistema envia ENQ (0x05) a cada ~400 ms e a balança responde <STX>00350<ETX> (gramas ÷ divisor).
const ENQ = 0x05
const NOMES = { 2: '<STX>', 3: '<ETX>', 5: '<ENQ>', 6: '<ACK>', 10: '<LF>', 13: '<CR>' }
export const visivel = bytes => [...bytes].map(b => NOMES[b] ?? (b >= 32 && b < 127 ? String.fromCharCode(b) : `<${b.toString(16).padStart(2, '0')}>`)).join('')

/** Abre a porta e chama onPeso(kg) a cada leitura. onRaw recebe os bytes recebidos (diagnóstico). Retorna { fechar() }. */
export async function abrirBalanca(c, { onPeso, onRaw, onErro } = {}, portaExistente) {
  if (!navigator.serial) throw new Error('Este navegador não suporta Web Serial. Use Chrome ou Edge, em localhost ou HTTPS.')
  const port = portaExistente || await navigator.serial.requestPort()
  await port.open({ baudRate: +(c.baud || 9600), dataBits: +(c.dataBits || 8), parity: c.parity || 'none', stopBits: +(c.stopBits || 1) })
  const protocolo = c.protocolo || 'continuo', divisor = +(c.divisor || 1000)
  const reader = port.readable.getReader(); const writer = protocolo === 'enq' ? port.writable.getWriter() : null
  let ativo = true, buf = '', timer = null
  if (writer) { const pedir = () => writer.write(new Uint8Array([ENQ])).catch(() => {}); pedir(); timer = setInterval(pedir, +(c.intervalo || 400)) }

  const tratar = () => {
    if (protocolo === 'enq') {
      const m = buf.match(/\x02\D*(\d{3,7})\D*\x03/g)
      if (m) { onPeso?.(+(parseInt(m[m.length - 1].match(/(\d{3,7})/)[1], 10) / divisor).toFixed(3)); buf = '' }
    } else {
      const m = buf.match(/\d+[.,]\d{1,3}/g)
      if (m) { onPeso?.(parseFloat(m[m.length - 1].replace(',', '.'))); buf = '' }
    }
    if (buf.length > 200) buf = ''
  }
  ;(async () => {
    try {
      while (ativo) {
        const { value, done } = await reader.read(); if (done) break
        if (value) { onRaw?.(visivel(value)); buf += String.fromCharCode(...value); tratar() }
      }
    } catch (e) { if (ativo) onErro?.(e) }
  })()
  return {
    async fechar() {
      ativo = false; clearInterval(timer)
      try { await reader.cancel() } catch { /* já fechado */ }
      try { reader.releaseLock() } catch { /* */ }
      try { writer?.releaseLock() } catch { /* */ }
      try { await port.close() } catch { /* */ }
    },
  }
}

export function useBalanca() {
  const [peso, setPeso] = useState(0); const [conectada, setConectada] = useState(false); const ctl = useRef(null)
  const ligar = async porta => {
    ctl.current = await abrirBalanca(cfg(), { onPeso: setPeso, onErro: () => setConectada(false) }, porta)
    setConectada(true)
  }
  useEffect(() => {
    window.__simulaPeso = p => { setPeso(p); setConectada(true) }
    navigator.serial?.getPorts().then(ps => ps[0] && ligar(ps[0])).catch(() => {})   // reconecta sozinho a balança já autorizada
    return () => { ctl.current?.fechar() }
  }, [])
  return { peso, conectada, conectar: () => ligar() }
}
