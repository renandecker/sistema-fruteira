package br.com.fruteira.catalogo;

import io.quarkus.runtime.StartupEvent;
import jakarta.annotation.Priority;
import jakarta.enterprise.context.ApplicationScoped;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import jakarta.enterprise.event.Observes;
import jakarta.transaction.Transactional;
import java.math.BigDecimal;

/** Popula produtos de exemplo apenas se a tabela estiver vazia. */
@ApplicationScoped
public class Seed {
    @ConfigProperty(name = "fruteira.seed", defaultValue = "false") boolean seed; // true só em dev

    @Transactional
    void onStart(@Observes @Priority(10) StartupEvent ev) {
        if (!seed || Produto.count() > 0) return;
        criar("Banana Prata", Produto.Unidade.KG, "7.90", 1, "Frutas", "8", "4.50");
        criar("Tomate Longa Vida", Produto.Unidade.KG, "9.90", 2, "Legumes", "10", "5.50");
        criar("Maçã Fuji", Produto.Unidade.KG, "11.90", 3, "Frutas", "6", "7.00");
        criar("Alface Crespa", Produto.Unidade.UN, "3.50", 4, "Verduras", "18", "1.60");
        criar("Melancia", Produto.Unidade.KG, "4.99", 5, "Frutas", "5", "2.30");
    }
    private void criar(String nome, Produto.Unidade un, String preco, int plu, String cat, String perda, String custo) {
        Produto p = new Produto();
        p.nome = nome; p.unidade = un; p.precoVarejo = new BigDecimal(preco); p.plu = plu; p.categoria = cat;
        p.taxaPerdaPct = new BigDecimal(perda); p.custoMedio = new BigDecimal(custo);
        p.persist();
    }
}
