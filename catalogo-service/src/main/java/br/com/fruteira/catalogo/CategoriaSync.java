package br.com.fruteira.catalogo;

import io.quarkus.runtime.StartupEvent;
import jakarta.annotation.Priority;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Observes;
import jakarta.transaction.Transactional;
import java.util.*;

/** Ao subir, cria a Categoria de todo texto de categoria já usado em produtos (migra o cadastro antigo, que era texto livre). */
@ApplicationScoped
public class CategoriaSync {
    @Transactional
    void onStart(@Observes @Priority(20) StartupEvent ev) {
        List<String> usadas = Produto.getEntityManager()
            .createQuery("select distinct p.categoria from Produto p where p.categoria is not null", String.class).getResultList();
        Set<String> existentes = new HashSet<>();
        for (Categoria c : Categoria.<Categoria>listAll()) existentes.add(c.nome.toLowerCase());
        int ordem = (int) Categoria.count();
        for (String n : usadas) {
            String nome = n.trim();
            if (nome.isEmpty() || existentes.contains(nome.toLowerCase())) continue;
            Categoria c = new Categoria(); c.nome = nome; c.ordem = ++ordem; c.persist();
            existentes.add(nome.toLowerCase());
        }
    }
}
