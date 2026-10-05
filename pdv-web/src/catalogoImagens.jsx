import React, { useState } from 'react'

// Catálogo de imagens de frutas, legumes e verduras. A chave (key) é a mesma usada no pré-cadastro SQL (coluna "imagem").
export const CATALOGO = [{"key": "banana", "nome": "Banana Prata", "grupo": "Frutas", "aliases": ["banana prata", "banana"], "emoji": "🍌"}, {"key": "tomate", "nome": "Tomate Longa Vida", "grupo": "Legumes", "aliases": ["tomate"], "emoji": "🍅"}, {"key": "maca", "nome": "Maçã Fuji", "grupo": "Frutas", "aliases": ["maca fuji", "maca gala", "maca"], "emoji": "🍎"}, {"key": "laranja", "nome": "Laranja Pera", "grupo": "Frutas", "aliases": ["laranja"], "emoji": "🍊"}, {"key": "batata", "nome": "Batata Inglesa", "grupo": "Raízes e Tubérculos", "aliases": ["batata inglesa", "batata"], "emoji": "🥔"}, {"key": "cebola", "nome": "Cebola", "grupo": "Raízes e Tubérculos", "aliases": ["cebola"], "emoji": "🧅"}, {"key": "cenoura", "nome": "Cenoura", "grupo": "Raízes e Tubérculos", "aliases": ["cenoura"], "emoji": "🥕"}, {"key": "alface-crespa", "nome": "Alface Crespa", "grupo": "Verduras", "aliases": ["alface crespa", "alface"], "emoji": "🥬"}, {"key": "limao", "nome": "Limão Taiti", "grupo": "Frutas", "aliases": ["limao taiti", "limao"], "emoji": "🍋"}, {"key": "melancia", "nome": "Melancia", "grupo": "Frutas", "aliases": ["melancia"], "emoji": "🍉"}, {"key": "mamao", "nome": "Mamão Papaya", "grupo": "Frutas", "aliases": ["mamao papaya", "mamao"], "forma": "alongado", "cor": "#fb8c00"}, {"key": "uva", "nome": "Uva Thompson", "grupo": "Frutas", "aliases": ["uva"], "emoji": "🍇"}, {"key": "morango", "nome": "Morango", "grupo": "Frutas", "aliases": ["morango"], "emoji": "🍓"}, {"key": "abacaxi", "nome": "Abacaxi Pérola", "grupo": "Frutas", "aliases": ["abacaxi"], "emoji": "🍍"}, {"key": "manga", "nome": "Manga Palmer", "grupo": "Frutas", "aliases": ["manga"], "emoji": "🥭"}, {"key": "pera", "nome": "Pera Williams", "grupo": "Frutas", "aliases": ["pera"], "emoji": "🍐"}, {"key": "pepino", "nome": "Pepino", "grupo": "Legumes", "aliases": ["pepino"], "emoji": "🥒"}, {"key": "pimentao", "nome": "Pimentão Verde", "grupo": "Legumes", "aliases": ["pimentao verde", "pimentao"], "emoji": "🫑"}, {"key": "berinjela", "nome": "Berinjela", "grupo": "Legumes", "aliases": ["berinjela"], "emoji": "🍆"}, {"key": "abobrinha", "nome": "Abobrinha Italiana", "grupo": "Legumes", "aliases": ["abobrinha italiana", "abobrinha"], "forma": "alongado", "cor": "#43a047"}, {"key": "brocolis", "nome": "Brócolis", "grupo": "Legumes", "aliases": ["brocolis"], "emoji": "🥦"}, {"key": "couve-flor", "nome": "Couve-flor", "grupo": "Legumes", "aliases": ["couve flor", "couveflor"], "forma": "cacho", "cor": "#f3ecd0"}, {"key": "repolho", "nome": "Repolho Verde", "grupo": "Verduras", "aliases": ["repolho verde", "repolho"], "emoji": "🥬"}, {"key": "couve", "nome": "Couve Manteiga", "grupo": "Verduras", "aliases": ["couve manteiga", "couve"], "emoji": "🥬"}, {"key": "rucula", "nome": "Rúcula", "grupo": "Verduras", "aliases": ["rucula"], "emoji": "🥬"}, {"key": "agriao", "nome": "Agrião", "grupo": "Verduras", "aliases": ["agriao"], "emoji": "🌿"}, {"key": "espinafre", "nome": "Espinafre", "grupo": "Verduras", "aliases": ["espinafre"], "emoji": "🥬"}, {"key": "acelga", "nome": "Acelga", "grupo": "Verduras", "aliases": ["acelga"], "emoji": "🥬"}, {"key": "almeirao", "nome": "Almeirão", "grupo": "Verduras", "aliases": ["almeirao"], "emoji": "🥬"}, {"key": "alface-americana", "nome": "Alface Americana", "grupo": "Verduras", "aliases": ["alface americana"], "emoji": "🥬"}, {"key": "batata-doce", "nome": "Batata-doce", "grupo": "Raízes e Tubérculos", "aliases": ["batata doce"], "emoji": "🍠"}, {"key": "beterraba", "nome": "Beterraba", "grupo": "Raízes e Tubérculos", "aliases": ["beterraba"], "forma": "raiz", "cor": "#8e1b4a"}, {"key": "mandioca", "nome": "Mandioca (Aipim)", "grupo": "Raízes e Tubérculos", "aliases": ["mandioca", "aipim", "macaxeira"], "forma": "raiz", "cor": "#a1887f"}, {"key": "inhame", "nome": "Inhame", "grupo": "Raízes e Tubérculos", "aliases": ["inhame"], "forma": "raiz", "cor": "#8d6e63"}, {"key": "alho", "nome": "Alho", "grupo": "Raízes e Tubérculos", "aliases": ["alho"], "emoji": "🧄"}, {"key": "gengibre", "nome": "Gengibre", "grupo": "Raízes e Tubérculos", "aliases": ["gengibre"], "forma": "raiz", "cor": "#d7b98a"}, {"key": "rabanete", "nome": "Rabanete", "grupo": "Raízes e Tubérculos", "aliases": ["rabanete"], "forma": "raiz", "cor": "#d81b60"}, {"key": "cebola-roxa", "nome": "Cebola Roxa", "grupo": "Raízes e Tubérculos", "aliases": ["cebola roxa"], "emoji": "🧅"}, {"key": "milho", "nome": "Milho Verde", "grupo": "Legumes", "aliases": ["milho verde", "milho"], "emoji": "🌽"}, {"key": "abobora", "nome": "Abóbora Cabotiá", "grupo": "Legumes", "aliases": ["abobora cabotia", "abobora"], "emoji": "🎃"}, {"key": "chuchu", "nome": "Chuchu", "grupo": "Legumes", "aliases": ["chuchu"], "forma": "pera", "cor": "#9ccc65"}, {"key": "vagem", "nome": "Vagem", "grupo": "Legumes", "aliases": ["vagem"], "forma": "alongado", "cor": "#66bb6a"}, {"key": "quiabo", "nome": "Quiabo", "grupo": "Legumes", "aliases": ["quiabo"], "forma": "alongado", "cor": "#7cb342"}, {"key": "jilo", "nome": "Jiló", "grupo": "Legumes", "aliases": ["jilo"], "forma": "redondo", "cor": "#aed581"}, {"key": "pimenta", "nome": "Pimenta Dedo-de-moça", "grupo": "Legumes", "aliases": ["pimenta dedo de moca", "pimenta"], "emoji": "🌶️"}, {"key": "pimentao-vermelho", "nome": "Pimentão Vermelho", "grupo": "Legumes", "aliases": ["pimentao vermelho"], "forma": "redondo", "cor": "#e53935"}, {"key": "pimentao-amarelo", "nome": "Pimentão Amarelo", "grupo": "Legumes", "aliases": ["pimentao amarelo"], "forma": "redondo", "cor": "#fdd835"}, {"key": "cogumelo", "nome": "Champignon", "grupo": "Legumes", "aliases": ["champignon", "cogumelo"], "emoji": "🍄"}, {"key": "tomate-cereja", "nome": "Tomate Cereja", "grupo": "Legumes", "aliases": ["tomate cereja"], "emoji": "🍅"}, {"key": "goiaba", "nome": "Goiaba", "grupo": "Frutas", "aliases": ["goiaba"], "forma": "redondo", "cor": "#b5d56a"}, {"key": "maracuja", "nome": "Maracujá Azedo", "grupo": "Frutas", "aliases": ["maracuja"], "forma": "redondo", "cor": "#7b1fa2"}, {"key": "kiwi", "nome": "Kiwi", "grupo": "Frutas", "aliases": ["kiwi"], "emoji": "🥝"}, {"key": "abacate", "nome": "Abacate", "grupo": "Frutas", "aliases": ["abacate"], "emoji": "🥑"}, {"key": "coco", "nome": "Coco Verde", "grupo": "Frutas", "aliases": ["coco verde", "coco"], "emoji": "🥥"}, {"key": "pessego", "nome": "Pêssego", "grupo": "Frutas", "aliases": ["pessego"], "emoji": "🍑"}, {"key": "ameixa", "nome": "Ameixa", "grupo": "Frutas", "aliases": ["ameixa"], "forma": "redondo", "cor": "#6a1b9a"}, {"key": "caqui", "nome": "Caqui", "grupo": "Frutas", "aliases": ["caqui"], "forma": "redondo", "cor": "#ff8f00"}, {"key": "figo", "nome": "Figo", "grupo": "Frutas", "aliases": ["figo"], "forma": "pera", "cor": "#6a1b9a"}, {"key": "acerola", "nome": "Acerola", "grupo": "Frutas", "aliases": ["acerola"], "forma": "redondo", "cor": "#d32f2f"}, {"key": "tangerina", "nome": "Tangerina Ponkan", "grupo": "Frutas", "aliases": ["tangerina ponkan", "tangerina", "mexerica", "bergamota", "ponkan"], "emoji": "🍊"}, {"key": "melao", "nome": "Melão Amarelo", "grupo": "Frutas", "aliases": ["melao amarelo", "melao"], "emoji": "🍈"}, {"key": "maca-verde", "nome": "Maçã Verde", "grupo": "Frutas", "aliases": ["maca verde"], "emoji": "🍏"}, {"key": "banana-nanica", "nome": "Banana Nanica", "grupo": "Frutas", "aliases": ["banana nanica"], "emoji": "🍌"}, {"key": "cheiro-verde", "nome": "Cheiro-verde", "grupo": "Temperos e Ervas", "aliases": ["cheiro verde"], "emoji": "🌿"}, {"key": "salsa", "nome": "Salsinha", "grupo": "Temperos e Ervas", "aliases": ["salsinha", "salsa"], "emoji": "🌿"}, {"key": "coentro", "nome": "Coentro", "grupo": "Temperos e Ervas", "aliases": ["coentro"], "emoji": "🌿"}, {"key": "cebolinha", "nome": "Cebolinha", "grupo": "Temperos e Ervas", "aliases": ["cebolinha"], "emoji": "🌿"}, {"key": "hortela", "nome": "Hortelã", "grupo": "Temperos e Ervas", "aliases": ["hortela"], "emoji": "🌿"}, {"key": "manjericao", "nome": "Manjericão", "grupo": "Temperos e Ervas", "aliases": ["manjericao"], "emoji": "🌿"}]

export const normalizar = s => (s || '').toLowerCase().normalize('NFD').replace(/[\u0300-\u036f]/g, '').replace(/[^a-z0-9]+/g, ' ').trim()

/** Descobre a imagem pelo nome do produto (ex.: "Banana Prata" → banana). Usa o apelido mais longo que aparecer no nome. */
export function sugerirImagem(nome) {
  const n = ' ' + normalizar(nome) + ' '; let melhor = null, tam = 0
  for (const i of CATALOGO) for (const a of i.aliases) {
    const t = normalizar(a)
    if (t && (n.includes(' ' + t + ' ') || n.includes(' ' + t + 's ')) && t.length > tam) { melhor = i; tam = t.length }
  }
  return melhor
}

// Ilustrações vetoriais para o que não tem emoji
const FORMAS = {
  redondo: i => <><circle cx="50" cy="57" r="34" fill={i.cor} /><ellipse cx="38" cy="45" rx="11" ry="6" fill="#fff" opacity=".32" /><path d="M50 24 q3 -14 17 -15 q-1 12 -17 15z" fill="#43a047" /></>,
  alongado: i => <g transform="rotate(32 50 52)"><ellipse cx="50" cy="54" rx="17" ry="40" fill={i.cor} /><ellipse cx="44" cy="38" rx="5" ry="14" fill="#fff" opacity=".28" /><rect x="47" y="8" width="6" height="9" rx="2" fill="#6d4c41" /></g>,
  pera: i => <><path d="M50 16 C58 16 60 28 64 38 C82 50 82 80 50 86 C18 80 18 50 36 38 C40 28 42 16 50 16Z" fill={i.cor} /><ellipse cx="38" cy="58" rx="7" ry="12" fill="#fff" opacity=".25" /><path d="M50 16 q4 -10 14 -10 q-1 9 -14 10z" fill="#43a047" /></>,
  raiz: i => <><path d="M50 92 C32 72 22 52 30 38 C38 26 62 26 70 38 C78 52 68 72 50 92Z" fill={i.cor} /><ellipse cx="40" cy="48" rx="5" ry="10" fill="#fff" opacity=".22" /><path d="M50 30 q-6 -18 -18 -22 q0 16 18 22z" fill="#2e7d32" /><path d="M50 30 q6 -18 18 -22 q0 16 -18 22z" fill="#43a047" /></>,
  cacho: i => <><rect x="40" y="70" width="20" height="18" rx="6" fill="#7cb342" /><circle cx="34" cy="58" r="16" fill={i.cor} /><circle cx="66" cy="58" r="16" fill={i.cor} /><circle cx="50" cy="40" r="19" fill={i.cor} /><circle cx="50" cy="62" r="17" fill="#fff8e1" opacity=".8" /><path d="M24 70 q8 -4 16 6 M76 70 q-8 -4 -16 6" stroke="#7cb342" strokeWidth="6" fill="none" strokeLinecap="round" /></>,
}

export function IconeFruta({ item, size = 64 }) {
  if (!item) return <span style={{ fontSize: size, lineHeight: 1 }}>🥬</span>
  if (item.emoji) return <span style={{ fontSize: size, lineHeight: 1 }} role="img" aria-label={item.nome}>{item.emoji}</span>
  return <svg viewBox="0 0 100 100" width={size} height={size} role="img" aria-label={item.nome}>{(FORMAS[item.forma] || FORMAS.redondo)(item)}</svg>
}

/** Imagem do produto: 1) escolhida no catálogo  2) foto (URL)  3) sugerida pelo nome  4) padrão */
export function ImagemProduto({ p, size = 64 }) {
  const escolhida = p.imagem && CATALOGO.find(i => i.key === p.imagem)
  if (escolhida) return <IconeFruta item={escolhida} size={size} />
  if (p.fotoUrl) return <img src={p.fotoUrl} alt={p.nome} width={size} height={size} style={{ objectFit: 'cover', borderRadius: 8 }} />
  return <IconeFruta item={sugerirImagem(p.nome)} size={size} />
}

/** Janela para escolher a imagem no catálogo (ou informar a URL de uma foto). Chama onEscolher({ imagem, fotoUrl }). */
export function SeletorImagem({ atual, onEscolher, onFechar }) {
  const [q, setQ] = useState(''); const [url, setUrl] = useState('')
  const lista = CATALOGO.filter(i => !q || normalizar(i.nome + ' ' + i.aliases.join(' ')).includes(normalizar(q)))
  const grupos = [...new Set(lista.map(i => i.grupo))]
  return (
    <div className="modal" onClick={onFechar}>
      <div className="seletor" onClick={e => e.stopPropagation()}>
        <h3 style={{ marginTop: 0 }}>Catálogo de imagens</h3>
        <input autoFocus placeholder="Buscar fruta ou verdura…" value={q} onChange={e => setQ(e.target.value)} style={{ width: '100%' }} />
        <div className="seletor-grid">
          {grupos.map(g => <div key={g}><h4>{g}</h4><div className="tiles">
            {lista.filter(i => i.grupo === g).map(i => <button key={i.key} className={`tile ${atual === i.key ? 'on' : ''}`} onClick={() => onEscolher({ imagem: i.key, fotoUrl: null })}>
              <IconeFruta item={i} size={44} /><span>{i.nome}</span></button>)}</div></div>)}
          {!lista.length && <p>Nada encontrado.</p>}
        </div>
        <div className="seletor-rodape">
          <input placeholder="ou cole a URL de uma foto" value={url} onChange={e => setUrl(e.target.value)} style={{ flex: 1 }} />
          <button className="btn sec" disabled={!url.trim()} onClick={() => onEscolher({ imagem: null, fotoUrl: url.trim() })}>Usar foto</button>
          <button className="btn sec" onClick={() => onEscolher({ imagem: null, fotoUrl: null })}>Sem imagem</button>
          <button className="btn" onClick={onFechar}>Fechar</button>
        </div>
      </div>
    </div>)
}
