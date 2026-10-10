package br.com.fruteira.catalogo;

import io.quarkus.runtime.StartupEvent;
import jakarta.annotation.Priority;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Observes;
import jakarta.transaction.Transactional;
import java.math.BigDecimal;
import java.util.*;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.jboss.logging.Logger;

/**
 * Pré-cadastro de frutas, legumes, verduras e temperos mais comuns (mesma lista de database/pre_cadastro_frutas_verduras.sql).
 * Funciona em QUALQUER banco (H2 em desenvolvimento, PostgreSQL em produção) e é idempotente: ignora categoria/produto cujo nome já existe.
 * Roda sozinho ao subir quando fruteira.pre-cadastro=true (ligado em desenvolvimento) e sob demanda em POST /produtos/pre-cadastro.
 * Preços são apenas referência e o NCM é sugerido (valide com o contador).
 */
@ApplicationScoped
public class PreCadastro {
    private static final Logger LOG = Logger.getLogger(PreCadastro.class);
    @ConfigProperty(name = "fruteira.pre-cadastro", defaultValue = "false") boolean automatico;

    public record Resultado(int categoriasNovas, int produtosNovos, int produtosJaExistiam, int ncmPreenchidos) {}
    private record Linha(int ordem, String nome, String unidade, String categoria, String preco, String perda, String ncm, String imagem) {}

    private static final String[][] CATEGORIAS = {
        {"Frutas", "1"},
        {"Legumes", "2"},
        {"Verduras", "3"},
        {"Raízes e Tubérculos", "4"},
        {"Temperos e Ervas", "5"}
    };
    private static final Linha[] PRODUTOS = {
        new Linha(1, "Banana Prata", "KG", "Frutas", "7.90", "8", "08039000", "banana"),
        new Linha(2, "Tomate Longa Vida", "KG", "Legumes", "9.90", "10", "07020000", "tomate"),
        new Linha(3, "Maçã Fuji", "KG", "Frutas", "11.90", "6", "08081000", "maca"),
        new Linha(4, "Laranja Pera", "KG", "Frutas", "5.90", "6", "08051000", "laranja"),
        new Linha(5, "Batata Inglesa", "KG", "Raízes e Tubérculos", "6.90", "4", "07019000", "batata"),
        new Linha(6, "Cebola", "KG", "Raízes e Tubérculos", "5.90", "4", "07031019", "cebola"),
        new Linha(7, "Cenoura", "KG", "Raízes e Tubérculos", "6.50", "6", "07061000", "cenoura"),
        new Linha(8, "Alface Crespa", "UN", "Verduras", "3.50", "18", "07051900", "alface-crespa"),
        new Linha(9, "Limão Taiti", "KG", "Frutas", "6.90", "5", "08055000", "limao"),
        new Linha(10, "Melancia", "KG", "Frutas", "4.99", "5", "08071100", "melancia"),
        new Linha(11, "Mamão Papaya", "KG", "Frutas", "8.90", "10", "08072000", "mamao"),
        new Linha(12, "Uva Thompson", "KG", "Frutas", "14.90", "8", "08061000", "uva"),
        new Linha(13, "Morango", "KG", "Frutas", "22.90", "15", "08101000", "morango"),
        new Linha(14, "Abacaxi Pérola", "UN", "Frutas", "7.90", "8", "08043000", "abacaxi"),
        new Linha(15, "Manga Palmer", "KG", "Frutas", "8.90", "8", "08045020", "manga"),
        new Linha(16, "Pera Williams", "KG", "Frutas", "12.90", "8", "08083000", "pera"),
        new Linha(17, "Pepino", "KG", "Legumes", "5.90", "8", "07070000", "pepino"),
        new Linha(18, "Pimentão Verde", "KG", "Legumes", "9.90", "8", "07096000", "pimentao"),
        new Linha(19, "Berinjela", "KG", "Legumes", "7.90", "8", "07093000", "berinjela"),
        new Linha(20, "Abobrinha Italiana", "KG", "Legumes", "6.90", "8", "07099300", "abobrinha"),
        new Linha(21, "Brócolis", "MACO", "Legumes", "6.90", "15", "07041000", "brocolis"),
        new Linha(22, "Couve-flor", "UN", "Legumes", "9.90", "12", "07041000", "couve-flor"),
        new Linha(23, "Repolho Verde", "UN", "Verduras", "6.90", "10", "07049010", "repolho"),
        new Linha(24, "Couve Manteiga", "MACO", "Verduras", "3.50", "20", "07049090", "couve"),
        new Linha(25, "Rúcula", "MACO", "Verduras", "3.90", "20", null, "rucula"),
        new Linha(26, "Agrião", "MACO", "Verduras", "3.90", "20", null, "agriao"),
        new Linha(27, "Espinafre", "MACO", "Verduras", "4.50", "20", "07097000", "espinafre"),
        new Linha(28, "Acelga", "MACO", "Verduras", "4.50", "18", null, "acelga"),
        new Linha(29, "Almeirão", "MACO", "Verduras", "3.90", "20", null, "almeirao"),
        new Linha(30, "Alface Americana", "UN", "Verduras", "4.50", "18", "07051100", "alface-americana"),
        new Linha(31, "Batata-doce", "KG", "Raízes e Tubérculos", "6.90", "4", "07142000", "batata-doce"),
        new Linha(32, "Beterraba", "KG", "Raízes e Tubérculos", "5.90", "6", "07069000", "beterraba"),
        new Linha(33, "Mandioca (Aipim)", "KG", "Raízes e Tubérculos", "5.90", "6", "07141000", "mandioca"),
        new Linha(34, "Inhame", "KG", "Raízes e Tubérculos", "12.90", "6", "07143000", "inhame"),
        new Linha(35, "Alho", "KG", "Raízes e Tubérculos", "39.90", "6", "07032090", "alho"),
        new Linha(36, "Gengibre", "KG", "Raízes e Tubérculos", "24.90", "6", "09101100", "gengibre"),
        new Linha(37, "Rabanete", "MACO", "Raízes e Tubérculos", "4.50", "12", "07069000", "rabanete"),
        new Linha(38, "Cebola Roxa", "KG", "Raízes e Tubérculos", "8.90", "4", "07031019", "cebola-roxa"),
        new Linha(39, "Milho Verde", "UN", "Legumes", "2.50", "8", "07099910", "milho"),
        new Linha(40, "Abóbora Cabotiá", "KG", "Legumes", "5.90", "6", "07099300", "abobora"),
        new Linha(41, "Chuchu", "KG", "Legumes", "4.90", "8", "07099990", "chuchu"),
        new Linha(42, "Vagem", "KG", "Legumes", "14.90", "10", "07082000", "vagem"),
        new Linha(43, "Quiabo", "KG", "Legumes", "14.90", "10", "07099990", "quiabo"),
        new Linha(44, "Jiló", "KG", "Legumes", "12.90", "10", "07099990", "jilo"),
        new Linha(45, "Pimenta Dedo-de-moça", "KG", "Legumes", "29.90", "8", "07096000", "pimenta"),
        new Linha(46, "Pimentão Vermelho", "KG", "Legumes", "14.90", "8", "07096000", "pimentao-vermelho"),
        new Linha(47, "Pimentão Amarelo", "KG", "Legumes", "14.90", "8", "07096000", "pimentao-amarelo"),
        new Linha(48, "Champignon", "UN", "Legumes", "9.90", "10", "07095100", "cogumelo"),
        new Linha(49, "Tomate Cereja", "KG", "Legumes", "19.90", "10", "07020000", "tomate-cereja"),
        new Linha(50, "Goiaba", "KG", "Frutas", "9.90", "10", "08045010", "goiaba"),
        new Linha(51, "Maracujá Azedo", "KG", "Frutas", "14.90", "10", null, "maracuja"),
        new Linha(52, "Kiwi", "KG", "Frutas", "24.90", "8", "08105000", "kiwi"),
        new Linha(53, "Abacate", "KG", "Frutas", "9.90", "10", "08044000", "abacate"),
        new Linha(54, "Coco Verde", "UN", "Frutas", "5.90", "5", "08011900", "coco"),
        new Linha(55, "Pêssego", "KG", "Frutas", "14.90", "10", "08093000", "pessego"),
        new Linha(56, "Ameixa", "KG", "Frutas", "16.90", "10", "08094000", "ameixa"),
        new Linha(57, "Caqui", "KG", "Frutas", "11.90", "12", "08107000", "caqui"),
        new Linha(58, "Figo", "KG", "Frutas", "29.90", "15", "08042000", "figo"),
        new Linha(59, "Acerola", "KG", "Frutas", "14.90", "15", null, "acerola"),
        new Linha(60, "Tangerina Ponkan", "KG", "Frutas", "6.90", "6", "08052100", "tangerina"),
        new Linha(61, "Melão Amarelo", "KG", "Frutas", "5.90", "6", "08071900", "melao"),
        new Linha(62, "Maçã Verde", "KG", "Frutas", "12.90", "6", "08081000", "maca-verde"),
        new Linha(63, "Banana Nanica", "KG", "Frutas", "6.90", "8", "08039000", "banana-nanica"),
        new Linha(64, "Cheiro-verde", "MACO", "Temperos e Ervas", "3.00", "20", null, "cheiro-verde"),
        new Linha(65, "Salsinha", "MACO", "Temperos e Ervas", "2.50", "20", null, "salsa"),
        new Linha(66, "Coentro", "MACO", "Temperos e Ervas", "2.50", "20", null, "coentro"),
        new Linha(67, "Cebolinha", "MACO", "Temperos e Ervas", "2.50", "20", null, "cebolinha"),
        new Linha(68, "Hortelã", "MACO", "Temperos e Ervas", "3.00", "20", null, "hortela"),
        new Linha(69, "Manjericão", "MACO", "Temperos e Ervas", "3.50", "20", null, "manjericao")
    };

    @Transactional
    void onStart(@Observes @Priority(15) StartupEvent ev) {
        if (!automatico) return;
        Resultado r = aplicar();
        LOG.infof("Pré-cadastro: %d produtos novos, %d categorias novas (%d já existiam)", r.produtosNovos(), r.categoriasNovas(), r.produtosJaExistiam());
    }

    @Transactional
    public Resultado aplicar() {
        Map<String, Categoria> cats = new HashMap<>();
        int ordemMax = 0;
        for (Categoria c : Categoria.<Categoria>listAll()) { cats.put(c.nome.toLowerCase(), c); ordemMax = Math.max(ordemMax, c.ordem == null ? 0 : c.ordem); }
        int categoriasNovas = 0;
        for (String[] l : CATEGORIAS) {
            if (cats.containsKey(l[0].toLowerCase())) continue;
            Categoria c = new Categoria(); c.nome = l[0]; c.ordem = ordemMax + Integer.parseInt(l[1]); c.persist();
            cats.put(l[0].toLowerCase(), c); categoriasNovas++;
        }
        Set<String> nomes = new HashSet<>(); Set<Integer> plus = new HashSet<>(); Map<String, Produto> porNome = new HashMap<>();
        for (Produto p : Produto.<Produto>listAll()) { nomes.add(p.nome.toLowerCase()); porNome.put(p.nome.toLowerCase(), p); if (p.plu != null) plus.add(p.plu); }
        int novos = 0, existentes = 0, ncmPreenchidos = 0;
        for (Linha l : PRODUTOS) {
            if (nomes.contains(l.nome().toLowerCase())) {
                existentes++;
                Produto ex = porNome.get(l.nome().toLowerCase());     // produto antigo sem NCM: completa (a rotina de NCM confere depois)
                if (ex != null && l.ncm() != null && (ex.ncm == null || ex.ncm.isBlank())) { ex.ncm = l.ncm(); ex.ncmStatus = null; ex.ncmVerificadoEm = null; ncmPreenchidos++; }
                continue;
            }
            Produto p = new Produto();
            p.nome = l.nome(); p.unidade = Produto.Unidade.valueOf(l.unidade());
            Categoria c = cats.get(l.categoria().toLowerCase()); p.categoria = c != null ? c.nome : l.categoria();
            p.precoVarejo = new BigDecimal(l.preco()); p.taxaPerdaPct = new BigDecimal(l.perda()); p.custoMedio = BigDecimal.ZERO;
            p.plu = plus.contains(l.ordem()) ? null : l.ordem();           // PLU já ocupado: fica sem PLU
            if (p.plu != null) plus.add(p.plu);
            p.ncm = l.ncm(); p.imagem = l.imagem(); p.ativo = true;
            p.persist(); nomes.add(p.nome.toLowerCase()); porNome.put(p.nome.toLowerCase(), p); novos++;
        }
        return new Resultado(categoriasNovas, novos, existentes, ncmPreenchidos);
    }
}
