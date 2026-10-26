import React, { useState } from 'react'
import { api } from './lib.jsx'
import { mascaraDoc, docValido, tipoDoc, ieValida, enderecoFalta } from './fiscal.js'

const UFS = ['AC', 'AL', 'AP', 'AM', 'BA', 'CE', 'DF', 'ES', 'GO', 'MA', 'MT', 'MS', 'MG', 'PA', 'PB', 'PR', 'PE', 'PI', 'RJ', 'RN', 'RS', 'RO', 'RR', 'SC', 'SP', 'SE', 'TO']

/** Dados cadastrais que vão na nota: IE, e-mail e endereço completo (os campos mudam o objeto `f` por `set`). */
export function CamposFiscais({ f, set, comIm = false }) {
  const falta = enderecoFalta(f)
  return <fieldset className="fiscal-campos"><legend>Dados fiscais (vão na nota)</legend>
    <input aria-label="Inscrição Estadual" placeholder="Inscrição Estadual (ou ISENTO)" value={f.ie || ''} onChange={e => set('ie', e.target.value)} />
    {comIm && <input aria-label="Inscrição Municipal" placeholder="Inscrição Municipal" value={f.im || ''} onChange={e => set('im', e.target.value)} />}
    <input aria-label="E-mail" placeholder="E-mail" value={f.email || ''} onChange={e => set('email', e.target.value)} />
    <input aria-label="CEP" placeholder="CEP" inputMode="numeric" value={f.cep || ''} onChange={e => set('cep', e.target.value.replace(/[^\d-]/g, '').slice(0, 9))} />
    <input aria-label="Logradouro" placeholder="Rua / avenida" value={f.logradouro || ''} onChange={e => set('logradouro', e.target.value)} />
    <input aria-label="Número" placeholder="Nº (S/N)" style={{ width: 80 }} value={f.numero || ''} onChange={e => set('numero', e.target.value)} />
    <input aria-label="Bairro" placeholder="Bairro" value={f.bairro || ''} onChange={e => set('bairro', e.target.value)} />
    <input aria-label="Município" placeholder="Município" value={f.municipio || ''} onChange={e => set('municipio', e.target.value)} />
    <input aria-label="Código IBGE do município" placeholder="Cód. IBGE (7 dígitos)" inputMode="numeric" value={f.cMun || ''} onChange={e => set('cMun', e.target.value.replace(/\D/g, '').slice(0, 7))} />
    <select aria-label="UF" value={f.uf || ''} onChange={e => set('uf', e.target.value)}><option value="">UF</option>{UFS.map(u => <option key={u}>{u}</option>)}</select>
    {falta.length > 0 && <small className="erro" role="status">Endereço incompleto: falta {falta.join(', ')}</small>}
    {f.ie && f.ie.toUpperCase() !== 'ISENTO' && !ieValida(f.ie, f.uf || 'RS') && <small className="erro" role="status">Inscrição Estadual inválida{(f.uf || 'RS') === 'RS' ? ' (RS: 10 dígitos com dígito verificador)' : ''}</small>}
  </fieldset>
}

/** Completar/corrigir o cadastro fiscal de um cliente ou fornecedor já existente. */
export function ModalCadastro({ tipo, item, onSalvo, onFechar }) {
  const [f, setF] = useState({ ...item }); const [msg, setMsg] = useState('')
  const set = (k, v) => setF(x => ({ ...x, [k]: v }))
  const url = `/api/retaguarda/${tipo === 'cliente' ? 'clientes' : 'fornecedores'}/${item.id}`
  const doc = tipo === 'cliente' ? item.cpf : f.documento
  const salvar = () => api(url, { method: 'PUT', body: JSON.stringify(f) }).then(() => onSalvo()).catch(e => setMsg(String(e) || 'Não foi possível salvar'))
  return <div className="modal" role="dialog" aria-label="Cadastro fiscal"><div className="nfce-box" style={{ width: 'min(620px,96vw)' }}>
    <h2>{tipo === 'cliente' ? 'Cliente' : 'Fornecedor'}: {item.nome}</h2>
    <small>{tipoDoc(doc) || 'Documento'}: {mascaraDoc(doc)} {docValido(doc) ? '✔' : <b className="erro">inválido</b>}</small>
    {msg && <div className="msg" role="alert">{msg}</div>}
    <input aria-label="Nome" placeholder="Nome / razão social" value={f.nome || ''} onChange={e => set('nome', e.target.value)} />
    <input aria-label="Telefone" placeholder="Telefone" value={f.telefone || ''} onChange={e => set('telefone', e.target.value)} />
    <CamposFiscais f={f} set={set} comIm={tipo === 'fornecedor'} />
    <div className="botoes"><button className="btn" onClick={salvar}>Salvar</button><button className="btn sec" onClick={onFechar}>Fechar</button></div></div></div>
}

const CST_ICMS = ['', '102', '103', '300', '400', '500', '00', '40', '41', '50', '60']
const CST_PIS = ['', '01', '02', '04', '05', '06', '07', '08', '09', '49', '99']

/** Classificação fiscal do produto: vazio = vale o padrão da loja (.env). */
export function ModalFiscalProduto({ produto, onSalvo, onFechar }) {
  const [f, setF] = useState({ cfop: '', cstIcms: '', origemMercadoria: '', cstPis: '', cstCofins: '', aliqIcms: '', aliqPis: '', aliqCofins: '', cstIbsCbs: '', cClassTrib: '', pRedIbsCbs: '',
    ...Object.fromEntries(Object.entries(produto).filter(([k, v]) => v != null && ['cfop', 'cstIcms', 'origemMercadoria', 'cstPis', 'cstCofins', 'aliqIcms', 'aliqPis', 'aliqCofins', 'cstIbsCbs', 'cClassTrib', 'pRedIbsCbs'].includes(k))) })
  const [msg, setMsg] = useState('')
  const set = k => e => setF({ ...f, [k]: e.target.value })
  const num = v => v === '' || v == null ? null : Number(String(v).replace(',', '.'))
  const salvar = () => api(`/api/catalogo/produtos/${produto.id}/fiscal`, { method: 'PUT', body: JSON.stringify({ ...f, aliqIcms: num(f.aliqIcms), aliqPis: num(f.aliqPis), aliqCofins: num(f.aliqCofins), pRedIbsCbs: num(f.pRedIbsCbs) }) })
    .then(() => onSalvo()).catch(e => setMsg(String(e) || 'Não foi possível salvar'))
  const campo = (rot, k, props = {}) => <label>{rot}<input value={f[k]} onChange={set(k)} {...props} /></label>
  const sel = (rot, k, op) => <label>{rot}<select value={f[k]} onChange={set(k)}>{op.map(o => <option key={o} value={o}>{o || 'padrão da loja'}</option>)}</select></label>
  return <div className="modal" role="dialog" aria-label="Classificação fiscal"><div className="nfce-box" style={{ width: 'min(560px,96vw)' }}>
    <h2>Fiscal: {produto.nome}</h2>
    <small>NCM {produto.ncm || '— (obrigatório)'} · deixe em branco o que vale o padrão da loja (.env). Confirme os códigos com o seu contador.</small>
    {msg && <div className="msg" role="alert">{msg}</div>}
    <div className="fiscal-grade">
      {campo('CFOP', 'cfop', { maxLength: 4, placeholder: '5102' })}
      {sel('CSOSN (Simples) / CST (normal)', 'cstIcms', CST_ICMS)}
      {campo('Origem (0-8)', 'origemMercadoria', { maxLength: 1, placeholder: '0' })}
      {campo('Alíquota ICMS %', 'aliqIcms', { inputMode: 'decimal' })}
      {sel('CST PIS', 'cstPis', CST_PIS)}{campo('Alíquota PIS %', 'aliqPis', { inputMode: 'decimal' })}
      {sel('CST COFINS', 'cstCofins', CST_PIS)}{campo('Alíquota COFINS %', 'aliqCofins', { inputMode: 'decimal' })}
      {campo('IBS/CBS: CST (3 dígitos)', 'cstIbsCbs', { maxLength: 3 })}{campo('IBS/CBS: cClassTrib (6 dígitos)', 'cClassTrib', { maxLength: 6 })}
      {campo('Redução IBS/CBS %', 'pRedIbsCbs', { inputMode: 'decimal' })}
    </div>
    <div className="botoes"><button className="btn" onClick={salvar}>Salvar</button><button className="btn sec" onClick={onFechar}>Fechar</button></div></div></div>
}
