package br.com.fruteira.retaguarda;

import br.com.fruteira.retaguarda.Entities.*;
import jakarta.inject.Inject;
import jakarta.transaction.Transactional;
import jakarta.ws.rs.*;
import jakarta.ws.rs.core.MediaType;
import br.com.fruteira.retaguarda.fiscal.Cadastro;
import br.com.fruteira.retaguarda.fiscal.Cpf;
import java.math.*;
import java.time.LocalDate;
import java.util.*;

@Path("/clientes") @Produces(MediaType.APPLICATION_JSON) @Consumes(MediaType.APPLICATION_JSON)
public class ClienteResource {
    static final BigDecimal CASHBACK = new BigDecimal("0.02"); // 2% da compra
    @Inject Auditor auditor;

    @GET public List<Cliente> listar(@QueryParam("inativos") boolean inativos) {
        return inativos && auditor.ehGerente() ? Cliente.<Cliente>listAll() : Cliente.<Cliente>list("ativo", true);
    }
    /** Documento (CPF ou CNPJ) e cadastro completo: confere dígitos verificadores, Inscrição Estadual e endereço (se informado, completo). */
    private static void conferirCadastro(Cliente c, boolean novo) {
        if (c.nome == null || c.nome.isBlank()) throw new WebApplicationException("Informe o nome do cliente", 422);
        if (novo) {
            String doc = Cadastro.digitos(c.cpf);
            if (doc.length() == 11) { if (!Cpf.valido(doc)) throw new WebApplicationException("CPF inválido", 422); c.tipo = "PF"; }
            else if (doc.length() == 14) { if (!Cadastro.cnpjValido(doc)) throw new WebApplicationException("CNPJ inválido", 422); c.tipo = "PJ"; }
            else throw new WebApplicationException("Informe o CPF (11 dígitos) ou o CNPJ (14 dígitos) do cliente", 422);
            c.cpf = doc;
        }
        c.nome = c.nome.trim(); c.ie = CadastroFiscal.vz(c.ie); c.email = CadastroFiscal.vz(c.email);
        if (c.email != null && !c.email.matches("[^@\\s]+@[^@\\s]+\\.[^@\\s]+")) throw new WebApplicationException("E-mail inválido", 422);
        CadastroFiscal.exigirEndereco("Cliente", c.logradouro, c.numero, c.bairro, c.municipio, c.cMun, c.uf, c.cep);
        CadastroFiscal.exigirIe(c.ie, c.uf);
        if (c.cep != null) c.cep = Cadastro.digitos(c.cep);
        if (c.cMun != null) c.cMun = Cadastro.digitos(c.cMun);
        if (c.uf != null) c.uf = c.uf.trim().toUpperCase();
    }
    @POST @Transactional public Cliente criar(Cliente c) {
        conferirCadastro(c, true);
        Cliente ex = Cliente.find("cpf", c.cpf).firstResult();
        if (ex != null) throw new WebApplicationException(Boolean.FALSE.equals(ex.ativo)
            ? "CPF pertence a um cliente desativado (somente o gerente pode reativar)" : "CPF já cadastrado", 409);
        c.ativo = true; c.persist();
        auditor.registrar("CADASTRO", "Cliente", c.id, "Cliente cadastrado: " + c.nome, null, c);
        return c;
    }
    /** Atualiza o cadastro (nome, telefone, e-mail, IE e endereço). Documento, cashback e limite do caderno não mudam por aqui. */
    @PUT @Path("/{id}") @Transactional public Cliente atualizar(@PathParam("id") Long id, Cliente in) {
        Cliente c = porId(id); String antes = auditor.snap(c);
        c.nome = in.nome; c.telefone = in.telefone; c.email = in.email; c.ie = in.ie; c.logradouro = in.logradouro; c.numero = in.numero; c.bairro = in.bairro;
        c.municipio = in.municipio; c.cMun = in.cMun; c.uf = in.uf; c.cep = in.cep;
        conferirCadastro(c, false);
        auditor.registrar("EDICAO", "Cliente", id, "Cadastro do cliente alterado: " + c.nome, antes, c);
        return c;
    }
    @GET @Path("/cpf/{cpf}") public Cliente porCpf(@PathParam("cpf") String cpf) { return achar(cpf); }

    /** Acumula cashback após a venda */
    @POST @Path("/cpf/{cpf}/compra") @Transactional
    public Cliente compra(@PathParam("cpf") String cpf, @QueryParam("valor") BigDecimal valor) {
        Cliente c = achar(cpf);
        c.cashback = c.cashback.add(valor.multiply(CASHBACK)).setScale(2, RoundingMode.HALF_UP);
        return c;
    }
    @POST @Path("/cpf/{cpf}/resgatar") @Transactional
    public Cliente resgatar(@PathParam("cpf") String cpf, @QueryParam("valor") BigDecimal valor) {
        Cliente c = achar(cpf);
        if (c.cashback.compareTo(valor) < 0) throw new WebApplicationException("Saldo de cashback insuficiente", 422);
        c.cashback = c.cashback.subtract(valor);
        auditor.registrar("EDICAO", "Cliente", c.id, "Resgate de cashback de R$ " + valor + " — " + c.nome, null, null);
        return c;
    }
    public record LimiteIn(BigDecimal limite) {}
    /** Limite do caderno (fiado) do cliente: só o gerente altera. null = limite padrão do sistema; 0 = não pode fiar. */
    @PUT @Path("/{id}/limite-caderno") @Transactional public Cliente limiteCaderno(@PathParam("id") Long id, LimiteIn in) {
        if (!auditor.ehGerente()) throw new ForbiddenException("Somente o gerente altera o limite do caderno");
        if (in != null && in.limite() != null && (in.limite().signum() < 0 || in.limite().compareTo(new BigDecimal("100000")) > 0)) throw new WebApplicationException("Limite inválido", 422);
        Cliente c = porId(id); BigDecimal antes = c.limiteCaderno;
        c.limiteCaderno = in == null || in.limite() == null ? null : in.limite().setScale(2, RoundingMode.HALF_UP);
        auditor.registrar("EDICAO", "Cliente", id, "Limite do caderno de " + c.nome + ": " + (antes == null ? "padrão" : "R$ " + antes) + " → " + (c.limiteCaderno == null ? "padrão" : "R$ " + c.limiteCaderno), null, null);
        return c;
    }
    @DELETE @Path("/{id}") @Transactional public Cliente desativar(@PathParam("id") Long id) {
        Cliente c = porId(id);
        if (Boolean.FALSE.equals(c.ativo)) return c;
        c.ativo = false;
        auditor.registrar("DESATIVACAO", "Cliente", id, "Cliente desativado: " + c.nome, null, null);
        return c;
    }
    @POST @Path("/{id}/reativar") @Transactional public Cliente reativar(@PathParam("id") Long id) {
        if (!auditor.ehGerente()) throw new ForbiddenException("Somente o gerente pode reativar");
        Cliente c = porId(id); c.ativo = true;
        auditor.registrar("REATIVACAO", "Cliente", id, "Cliente reativado: " + c.nome, null, null);
        return c;
    }
    private Cliente porId(Long id) { Cliente c = Cliente.findById(id); if (c == null) throw new NotFoundException(); return c; }
    /** Cliente desativado não é encontrado no caixa (não acumula nem resgata cashback). */
    private Cliente achar(String cpf) {
        Cliente c = Cliente.find("cpf", cpf).firstResult();
        if (c == null || Boolean.FALSE.equals(c.ativo)) throw new NotFoundException("Cliente não cadastrado");
        return c;
    }
}
