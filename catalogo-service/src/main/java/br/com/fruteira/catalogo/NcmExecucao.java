package br.com.fruteira.catalogo;

import io.quarkus.hibernate.orm.panache.PanacheEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import java.time.LocalDateTime;

/** Resumo de cada execução da verificação de NCM (agendada ou manual). */
@Entity
public class NcmExecucao extends PanacheEntity {
    public String origem;                 // AGENDADA | MANUAL | INICIO
    public LocalDateTime inicio, fim;
    public int total, validos, invalidos, vencidos, ausentes, naoVerificados, normalizados;
    @Column(length = 500) public String erro;
}
