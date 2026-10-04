package br.com.fruteira.retaguarda;

import br.com.fruteira.retaguarda.Entities.Nota;
import jakarta.transaction.Transactional;
import jakarta.ws.rs.*;
import jakarta.ws.rs.core.MediaType;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.List;

/**
 * NFC-e SIMULADA (homologação local): gera chave de 44 dígitos e protocolo fictício.
 * Para produção, trocar o corpo de emitir/cancelar por chamada a um emissor (SEFAZ direto via biblioteca
 * Java ou API de terceiros como Focus NFe / PlugNotas), mantendo os mesmos endpoints.
 */
@Path("/nfce") @Produces(MediaType.APPLICATION_JSON) @Consumes(MediaType.APPLICATION_JSON)
public class NfceResource {
    public record Emitir(Long vendaId, BigDecimal valor, String cpf, boolean contingencia) {}

    @GET public List<Nota> listar() { return Nota.find("order by id desc").list(); }

    @POST @Path("/emitir") @Transactional
    public Nota emitir(Emitir e) {
        Nota n = new Nota();
        n.vendaId = e.vendaId(); n.valor = e.valor(); n.cpf = e.cpf();
        n.numero = (int) Nota.count() + 1;
        n.chave = chave(n.numero);
        if (e.contingencia()) n.status = "CONTINGENCIA";          // sem rede: emite offline e transmite depois
        else { n.status = "AUTORIZADA"; n.protocolo = "SIM" + System.currentTimeMillis(); }
        n.persist();
        return n;
    }
    @POST @Path("/{id}/transmitir") @Transactional
    public Nota transmitir(@PathParam("id") Long id) {
        Nota n = achar(id);
        if (!"CONTINGENCIA".equals(n.status)) throw new WebApplicationException("Nota não está em contingência", 422);
        n.status = "AUTORIZADA"; n.protocolo = "SIM" + System.currentTimeMillis();
        return n;
    }
    @POST @Path("/{id}/cancelar") @Transactional
    public Nota cancelar(@PathParam("id") Long id) {
        Nota n = achar(id);
        if (!"AUTORIZADA".equals(n.status)) throw new WebApplicationException("Só é possível cancelar nota autorizada", 422);
        n.status = "CANCELADA";
        return n;
    }
    private Nota achar(Long id) { Nota n = Nota.findById(id); if (n == null) throw new NotFoundException(); return n; }

    /** UF(43=RS) + AAMM + CNPJ(14) + modelo 65 + série + número + tpEmis + código + DV (módulo 11) = 44 dígitos */
    static String chave(int numero) {
        String base = "43" + DateTimeFormatter.ofPattern("yyMM").format(LocalDate.now()) + "00000000000000" + "65" + "001"
                + String.format("%09d", numero) + "1" + String.format("%08d", (int) (Math.random() * 99999999));
        int soma = 0, peso = 2;
        for (int i = base.length() - 1; i >= 0; i--) { soma += (base.charAt(i) - '0') * peso; peso = peso == 9 ? 2 : peso + 1; }
        int r = soma % 11, dv = (r == 0 || r == 1) ? 0 : 11 - r;
        return base + dv;
    }
}
