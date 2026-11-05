import React, { useEffect, useState } from 'react'
import { QRCodeSVG } from 'qrcode.react'
import { brl } from './lib.jsx'
import { mascaraCpf, statusCpf } from './cpf.js'
import { situacaoNota, emitente, danfeNfce, registrarImpressao, resultadoImpressao } from './nfce.js'
import { cfg } from './lib.jsx'

const grupos = ch => (ch || '').replace(/\s/g, '').replace(/(\d{4})(?=\d)/g, '$1 ')
const qtd = (q, un) => un === 'KG' ? Number(q).toFixed(3) : Number(q)

/** DANFE NFC-e (cupom 80 mm): itens, total, pagamento, CPF do consumidor, chave, protocolo e QR Code. */
export function Danfe({ nota, itens = [], pagamentos = [], produtos = [], empresa }) {
  const emi = empresa?.cnpj ? empresa : emitente(); const un = i => i.unidade ?? produtos.find(p => p.id === i.produtoId)?.unidade
  const cont = nota.status === 'CONTINGENCIA'; const sim = nota.tpAmb === 0 || nota.protocolo?.startsWith('SIM'); const homolog = nota.tpAmb === 2
  const total = Number(nota.valor ?? itens.reduce((t, i) => t + Number(i.subtotal), 0))
  return <div className="area-print danfe" data-testid="danfe">
    <b>{emi.nome}</b>{emi.cnpj && <div>CNPJ {emi.cnpj}</div>}
    <div className="danfe-t">DANFE NFC-e — Documento Auxiliar da Nota Fiscal de Consumidor Eletrônica</div>
    {(homolog || sim) && <div className="danfe-aviso">{sim ? 'SIMULADO — SEM VALOR FISCAL' : 'EMITIDA EM AMBIENTE DE HOMOLOGAÇÃO — SEM VALOR FISCAL'}</div>}
    {cont && <div className="danfe-aviso">EMITIDA EM CONTINGÊNCIA — PENDENTE DE AUTORIZAÇÃO</div>}
    <table><thead><tr><th>Item</th><th>Qtd</th><th>Unit.</th><th>Total</th></tr></thead>
      <tbody>{itens.map((i, k) => <tr key={k}><td>{i.nome}</td><td>{qtd(i.quantidade, un(i))}</td><td>{brl(i.precoUnit)}</td><td>{brl(i.subtotal)}</td></tr>)}</tbody></table>
    <div className="danfe-l"><span>Qtde. total de itens</span><span>{itens.length}</span></div>
    <div className="danfe-l"><b>VALOR TOTAL</b><b>{brl(total)}</b></div>
    {pagamentos.map((p, k) => <div className="danfe-l" key={k}><span>{p.meio?.replace('_', ' ')}</span><span>{brl(p.valor)}</span></div>)}
    <hr />
    <div>NFC-e nº {nota.numero} · Série 1 · {new Date(nota.data).toLocaleString('pt-BR')}</div>
    <div className="danfe-ch">Chave de acesso<br />{grupos(nota.chave)}</div>
    {nota.protocolo && !sim && <div>Protocolo de autorização: {nota.protocolo}{nota.dhAutorizacao ? ` · ${new Date(nota.dhAutorizacao).toLocaleString('pt-BR')}` : ''}</div>}
    <div><b>{nota.cpf ? `CONSUMIDOR — CPF ${mascaraCpf(nota.cpf)}` : 'CONSUMIDOR NÃO IDENTIFICADO'}</b></div>
    {nota.cpf && <div><small>Nota Fiscal Gaúcha: o CPF informado participa do programa.</small></div>}
    {nota.qrCode && <div className="danfe-qr"><QRCodeSVG value={nota.qrCode} size={150} /></div>}
    {nota.urlChave && <div><small>Consulte pela chave de acesso em {nota.urlChave}</small></div>}
  </div>
}

const MOTIVOS = ['Sem papel', 'Impressora desligada ou sem conexão', 'Papel atolado', 'Saiu cortado ou ilegível', 'Outro']
const hora = d => d ? new Date(d).toLocaleString('pt-BR') : '—'
const ROT = { ENVIADA: 'Enviada (sem confirmação)', CONFIRMADA: 'Confirmada', FALHA: 'Falhou' }

/**
 * Imprime o DANFE a partir da nota GUARDADA no servidor (retorno da SEFAZ + itens/pagamentos da emissão) e registra cada tentativa:
 * ENVIADA ao imprimir → CONFIRMADA ou FALHA conforme o operador. O navegador não sabe se o papel saiu: quem confirma é a pessoa.
 * Sem o servidor (ex.: contingência sem rede), imprime com o `fallback` local e avisa que a tentativa não ficou registrada.
 */
export function ImprimirDanfe({ notaId, origem = 'REIMPRESSAO', fallback, produtos = [], onFechar, onMudou }) {
  const [d, setD] = useState(null); const [erro, setErro] = useState(''); const [fase, setFase] = useState('previa')
  const [atual, setAtual] = useState(null); const [motivo, setMotivo] = useState(MOTIVOS[0]); const [aviso, setAviso] = useState('')
  const carregar = async () => {
    try { setD(await danfeNfce(notaId)); setErro('') }
    catch (e) { if (fallback) { setD({ ...fallback, empresa: undefined, tentativas: [], local: true }); setAviso('Sem acesso ao servidor: usando os dados desta tela. A impressão não ficará registrada no histórico.') } else setErro(String(e)) }
  }
  useEffect(() => { carregar() }, [notaId])
  const imprimir = async () => {
    setErro('')
    let t = null
    if (!d?.local) { try { t = await registrarImpressao(notaId, origem === 'EMISSAO' && (d?.tentativas?.length || 0) === 0 ? 'EMISSAO' : 'REIMPRESSAO', cfg().tefTerminal) } catch (e) { setAviso(`Não consegui registrar a tentativa no servidor (${String(e).slice(0, 120)}). Imprimindo mesmo assim.`) } }
    setAtual(t)
    setTimeout(() => { try { window.print() } finally { setFase('confirmar') } }, 50)
  }
  const responder = async saiu => {
    try {
      if (atual) await resultadoImpressao(atual.id, saiu ? 'CONFIRMADA' : 'FALHA', saiu ? null : motivo)
      if (!d.local) setD(await danfeNfce(notaId))
      onMudou?.(); setFase(saiu ? 'ok' : 'previa'); setAtual(null)
    } catch (e) { setErro(String(e)) }
  }
  if (!d) return <div className="modal" role="dialog" aria-label="DANFE"><div className="nfce-box">{erro ? <><p className="erro">{erro}</p><button className="btn sec" onClick={onFechar}>Fechar</button></> : <p>Carregando a nota…</p>}</div></div>
  const tent = d.tentativas || []
  return <div className="modal" role="dialog" aria-label="Imprimir DANFE"><div className="nfce-box">
    <h2>DANFE — NFC-e nº {d.nota.numero}</h2>
    <p>{d.nota.status}{d.nota.cStat ? ` · ${d.nota.cStat} - ${d.nota.xMotivo}` : ''}<br /><small>Os dados da nota ficam guardados: pode imprimir de novo quantas vezes precisar.</small></p>
    {aviso && <p className="erro">{aviso}</p>}{erro && <p className="erro">{erro}</p>}
    {fase === 'previa' && <div className="botoes"><button className="btn" onClick={imprimir}>{tent.length ? 'Imprimir de novo' : 'Imprimir DANFE'}</button><button className="btn sec" onClick={onFechar}>Fechar</button></div>}
    {fase === 'confirmar' && <><p><b>O papel saiu corretamente?</b></p>
      <div className="botoes"><button className="btn" onClick={() => responder(true)}>Sim, saiu</button></div>
      <label>Se não saiu, o que houve?<select aria-label="Motivo da falha" value={motivo} onChange={e => setMotivo(e.target.value)}>{MOTIVOS.map(m => <option key={m}>{m}</option>)}</select></label>
      <div className="botoes"><button className="btn sec" onClick={() => responder(false)}>Não saiu — registrar falha</button></div></>}
    {fase === 'ok' && <><p>✔ Impressão confirmada.</p><div className="botoes"><button className="btn sec" onClick={onFechar}>Fechar</button><button className="btn sec" onClick={() => setFase('previa')}>Imprimir outra via</button></div></>}
    {tent.length > 0 && <><h3 style={{ margin: '8px 0 0' }}>Tentativas de impressão</h3>
      <table className="tab"><thead><tr><th>#</th><th>Quando</th><th>Origem</th><th>Quem / caixa</th><th>Resultado</th></tr></thead>
        <tbody>{tent.map(t => <tr key={t.id}><td>{t.tentativa}</td><td>{hora(t.data)}</td><td>{t.origem === 'EMISSAO' ? 'Na venda' : 'Reimpressão'}</td><td>{t.usuario || '—'} / {t.terminal || '—'}</td>
          <td>{ROT[t.resultado] || t.resultado}{t.erro ? <small style={{ display: 'block' }}>{t.erro}</small> : null}</td></tr>)}</tbody></table></>}
    <Danfe nota={d.nota} itens={d.itens} pagamentos={d.pagamentos} produtos={produtos} empresa={d.empresa} />
  </div></div>
}

/** Resultado da NFC-e logo após a venda: confirma, imprime, e deixa corrigir o CPF rejeitado sem travar o caixa. */
export function NfceAviso({ nota, itens, pagamentos, produtos, onCorrigir, onFechar }) {
  const [cpf, setCpf] = useState(mascaraCpf(nota.cpf || '')); const [erro, setErro] = useState(''); const [env, setEnv] = useState(false); const [abrindo, setAbrindo] = useState(false)
  const sit = situacaoNota(nota); const st = statusCpf(cpf)
  const reenviar = async (semCpf) => {
    if (!semCpf && st === 'incompleto' || st === 'invalido') return setErro('CPF inválido: confira os números.')
    setEnv(true); setErro('')
    try { await onCorrigir(semCpf ? '' : cpf) } catch (e) { setErro(String(e)) } finally { setEnv(false) }
  }
  return <div className="modal" role="dialog" aria-label="Resultado da NFC-e"><div className="nfce-box">
    {sit === 'ok' && <><h2>✔ NFC-e autorizada</h2>
      <p>Nº {nota.numero} · {nota.cStat} - {nota.xMotivo}{nota.cpf ? <><br />CPF na nota: <b>{mascaraCpf(nota.cpf)}</b> (Nota Fiscal Gaúcha)</> : <><br />Consumidor não identificado.</>}</p></>}
    {sit === 'contingencia' && <><h2>⚠ NFC-e em contingência</h2>
      <p>Sem comunicação com a SEFAZ-RS. A nota foi emitida offline (tpEmis 9) e será transmitida depois, em NFC-e › Transmitir. Imprima o DANFE com o QR Code de contingência.</p></>}
    {sit === 'corrigir-cpf' && <><h2>✖ SEFAZ rejeitou o CPF</h2>
      <p><b>{nota.cStat}</b> — {nota.xMotivo}</p>
      <p>A venda está concluída. Corrija o CPF do cliente e reenvie, ou emita sem CPF.</p>
      <input autoFocus aria-label="CPF corrigido" inputMode="numeric" placeholder="000.000.000-00" value={cpf} onChange={e => { setCpf(mascaraCpf(e.target.value)); setErro('') }} className={st === 'invalido' ? 'cpf-invalido' : ''} />
      <div className="botoes"><button className="btn" disabled={env || st !== 'ok'} onClick={() => reenviar(false)}>Reenviar com este CPF</button>
        <button className="btn sec" disabled={env} onClick={() => reenviar(true)}>Emitir sem CPF</button></div></>}
    {sit === 'rejeitada' && <><h2>✖ NFC-e {nota.status === 'DENEGADA' ? 'denegada' : 'rejeitada'}</h2><p><b>{nota.cStat}</b> — {nota.xMotivo}</p><p>A venda está concluída. Veja o motivo em NFC-e e reemita depois.</p></>}
    {erro && <p className="erro">{erro}</p>}
    <div className="botoes">{(sit === 'ok' || sit === 'contingencia') && <button className="btn" onClick={() => setAbrindo(true)}>Imprimir DANFE</button>}
      <button className="btn sec" onClick={onFechar}>{sit === 'corrigir-cpf' ? 'Resolver depois' : 'Fechar'}</button></div>
    {abrindo && <ImprimirDanfe notaId={nota.id} origem="EMISSAO" fallback={{ nota, itens, pagamentos }} produtos={produtos} onFechar={() => setAbrindo(false)} />}
  </div></div>
}
