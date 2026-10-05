package br.com.fruteira.catalogo;

import io.quarkus.hibernate.orm.panache.PanacheEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;

@Entity
public class Categoria extends PanacheEntity {
    @Column(unique = true) public String nome;
    public Integer ordem = 0;      // ordem das abas no PDV
    @Column(columnDefinition = "boolean default true") public Boolean ativo = true;
}
