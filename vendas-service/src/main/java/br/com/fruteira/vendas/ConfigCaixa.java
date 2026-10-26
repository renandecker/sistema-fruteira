package br.com.fruteira.vendas;

import io.quarkus.hibernate.orm.panache.PanacheEntity;
import jakarta.persistence.Entity;
import java.time.LocalDateTime;

/** Configuração do caixa (uma linha só). Padrão: tudo desligado; quem liga é o supervisor. */
@Entity
public class ConfigCaixa extends PanacheEntity {
    /** true = o operador só faz sangria com a aprovação (PIN) do supervisor. Desligado por padrão. */
    public boolean sangriaExigeAprovacao = false;
    public String atualizadoPor;
    public LocalDateTime atualizadoEm;

    /** A configuração em vigor (sem gravar nada: quem nunca configurou tem o padrão, desligado). */
    public static ConfigCaixa atual() {
        ConfigCaixa c = ConfigCaixa.<ConfigCaixa>findAll().firstResult();
        return c != null ? c : new ConfigCaixa();
    }
}
