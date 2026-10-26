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

/** Fornecedores nunca são apagados: "excluir" = desativar. Só o gerente vê e reativa os desativados. */
@Path("/fornecedores") @Produces(MediaType.APPLICATION_JSON) @Consumes(MediaType.APPLICATION_JSON)
public class FornecedorResource {
    @Inject Auditor auditor;

    @GET public List<Fornecedor> listar(@QueryParam("inativos") boolean inativos) {
        return inativos && auditor.ehGerente() ? Fornecedor.<Fornecedor>listAll() : Fornecedor.<Fornecedor>list("ativo", true);
    }
    /** CNPJ (empresa/CEASA) ou CPF (produtor rural), Inscrição Estadual/Municipal e endereço completo. */
    private static void conferirCadastro(Fornecedor f) {
        if (f.nome == null || f.nome.isBlank()) throw new WebApplicationException("Informe o nome do fornecedor", 422);
        String doc = Cadastro.digitos(f.documento);
        if (doc.length() == 11) { if (!Cpf.valido(doc)) throw new WebApplicationException("CPF do fornecedor inválido", 422); }
        else if (doc.length() == 14) { if (!Cadastro.cnpjValido(doc)) throw new WebApplicationException("CNPJ do fornecedor inválido", 422); }
        else throw new WebApplicationException("Informe o CNPJ (14 dígitos) ou o CPF de produtor rural (11 dígitos) do fornecedor", 422);
        if (doc.length() == 11 && !"PRODUTOR_RURAL".equals(f.tipo)) throw new WebApplicationException("Fornecedor com CPF deve ser do tipo Produtor rural; empresa e CEASA têm CNPJ", 422);
        f.documento = doc; f.nome = f.nome.trim(); f.ie = CadastroFiscal.vz(f.ie); f.im = CadastroFiscal.vz(f.im); f.email = CadastroFiscal.vz(f.email);
        if (f.email != null && !f.email.matches("[^@\\s]+@[^@\\s]+\\.[^@\\s]+")) throw new WebApplicationException("E-mail inválido", 422);
        CadastroFiscal.exigirEndereco("Fornecedor", f.logradouro, f.numero, f.bairro, f.municipio, f.cMun, f.uf, f.cep);
        CadastroFiscal.exigirIe(f.ie, f.uf);
        if (f.cep != null) f.cep = Cadastro.digitos(f.cep);
        if (f.cMun != null) f.cMun = Cadastro.digitos(f.cMun);
        if (f.uf != null) f.uf = f.uf.trim().toUpperCase();
    }
    @POST @Transactional public Fornecedor criar(Fornecedor f) {
        conferirCadastro(f);
        f.ativo = true; f.persist();
        auditor.registrar("CADASTRO", "Fornecedor", f.id, "Fornecedor cadastrado: " + f.nome, null, f);
        return f;
    }
    @PUT @Path("/{id}") @Transactional public Fornecedor atualizar(@PathParam("id") Long id, Fornecedor in) {
        Fornecedor f = achar(id); String antes = auditor.snap(f);
        in.id = null; conferirCadastro(in);
        f.nome = in.nome; f.documento = in.documento; f.tipo = in.tipo; f.telefone = in.telefone; f.ie = in.ie; f.im = in.im; f.email = in.email; f.logradouro = in.logradouro;
        f.numero = in.numero; f.bairro = in.bairro; f.municipio = in.municipio; f.cMun = in.cMun; f.uf = in.uf; f.cep = in.cep;
        auditor.registrar("EDICAO", "Fornecedor", id, "Cadastro do fornecedor alterado: " + f.nome, antes, f);
        return f;
    }
    @DELETE @Path("/{id}") @Transactional public Fornecedor desativar(@PathParam("id") Long id) {
        Fornecedor f = achar(id);
        if (Boolean.FALSE.equals(f.ativo)) return f;
        f.ativo = false;
        auditor.registrar("DESATIVACAO", "Fornecedor", id, "Fornecedor desativado: " + f.nome, null, null);
        return f;
    }
    @POST @Path("/{id}/reativar") @Transactional public Fornecedor reativar(@PathParam("id") Long id) {
        if (!auditor.ehGerente()) throw new ForbiddenException("Somente o gerente pode reativar");
        Fornecedor f = achar(id); f.ativo = true;
        auditor.registrar("REATIVACAO", "Fornecedor", id, "Fornecedor reativado: " + f.nome, null, null);
        return f;
    }
    private Fornecedor achar(Long id) { Fornecedor f = Fornecedor.findById(id); if (f == null) throw new NotFoundException(); return f; }
}
