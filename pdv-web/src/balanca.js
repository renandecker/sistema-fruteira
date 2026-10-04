import { useEffect, useRef, useState } from 'react'
// Lê a balança via Web Serial (cabo). Protocolo típico Toledo/Filizola: linhas tipo "  0,350" ou "P 00350".
// Sem balança (dev), use window.__simulaPeso(0.35).
export function useBalanca() {
  const [peso, setPeso] = useState(0); const [conectada, setConectada] = useState(false); const port = useRef(null)
  useEffect(() => { window.__simulaPeso = p => { setPeso(p); setConectada(true) } }, [])
  async function conectar() {
    port.current = await navigator.serial.requestPort(); let baud = 9600; try { baud = +(JSON.parse(localStorage.getItem('cfg') || '{}').baud || 9600) } catch {}
    await port.current.open({ baudRate: baud })
    setConectada(true)
    const reader = port.current.readable.pipeThrough(new TextDecoderStream()).getReader(); let buf = ''
    for (;;) { const { value, done } = await reader.read(); if (done) break
      buf += value; const m = buf.match(/(\d+[.,]\d{3})/g)
      if (m) { setPeso(parseFloat(m[m.length - 1].replace(',', '.'))); buf = '' } }
  }
  return { peso, conectada, conectar }
}
