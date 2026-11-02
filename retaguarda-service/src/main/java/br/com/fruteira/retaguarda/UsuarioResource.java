package br.com.fruteira.retaguarda;

import br.com.fruteira.retaguarda.Entities.Usuario;
import jakarta.inject.Inject;
import jakarta.transaction.Transactional;
import jakarta.ws.rs.*;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import org.eclipse.microprofile.config.inject.ConfigProperty;

/** Usuários, login e senhas do próprio sistema (sem Keycloak). O login devolve um token assinado (produção) que o front envia em /api/*. */
@Path("/usuarios") @Produces(MediaType.APPLICATION_JSON) @Consumes(MediaType.APPLICATION_JSON)
public class UsuarioResource {
    @Inject Auditor auditor;
    @Inject Tokens tokens;
    @ConfigProperty(name = "fruteira.senha.minimo", defaultValue = "4") int minimo;
    @ConfigProperty(name = "fruteira.senha.provisoria", defaultValue = "false") boolean provisoria;   // true em produção: senha criada/redefinida pelo gerente deve ser trocada no 1º acesso

    public record Pin(String pin) {}
    public record Login(String login, String senha) {}
    public record TrocaSenha(String atual, String nova) {}

    // bloqueio contra tentativa em massa: 5 erros seguidos do mesmo login bloqueiam por 5 minutos
    private static final Map<String, long[]> TENTATIVAS = new ConcurrentHashMap<>();
    private static final int MAX_ERROS = 5; private static final long BLOQUEIO_MS = 5 * 60_000L;

    @POST @Path("/login") @Transactional
    public Map<String, Object> login(Login l) {
        String id = l == null || l.login() == null ? "" : l.login().trim().toLowerCase();
        if (l == null || id.isEmpty() || l.senha() == null) throw new ForbiddenException("Usuário ou senha inválidos");
        long[] t = TENTATIVAS.get(id);
        if (t != null && t[0] >= MAX_ERROS && System.currentTimeMillis() < t[1]) throw new WebApplicationException("Muitas tentativas. Aguarde 5 minutos e tente de novo.", 429);
        Usuario u = Usuario.find("login = ?1 and ativo = true", id).firstResult();
        if (u == null || !Senhas.confere(l.senha(), u.pinHash)) {
            TENTATIVAS.compute(id, (k, v) -> { long[] n = v == null || System.currentTimeMillis() >= v[1] ? new long[] {0, 0} : v; n[0]++; n[1] = System.currentTimeMillis() + BLOQUEIO_MS; return n; });
            throw new ForbiddenException("Usuário ou senha inválidos");
        }
        TENTATIVAS.remove(id);
        if (Senhas.precisaAtualizar(u.pinHash)) u.pinHash = Senhas.gerar(l.senha());
        Map<String, Object> r = new LinkedHashMap<>();
        r.put("nome", u.nome); r.put("login", u.login); r.put("perfil", u.perfil); r.put("provisoria", u.provisoria);
        String token = tokens.emitir(u); if (token != null) r.put("token", token);
        return r;
    }

    /** O próprio usuário troca a senha (exige a atual). Em produção exige token. */
    @POST @Path("/minha-senha") @Transactional
    public Map<String, Object> minhaSenha(TrocaSenha t) {
        String login = auditor.usuario();
        Usuario u = Usuario.find("login = ?1 and ativo = true", login == null ? "" : login.toLowerCase()).firstResult();
        if (u == null || t == null || !Senhas.confere(t.atual(), u.pinHash)) throw new ForbiddenException("Senha atual incorreta");
        validarNova(t.nova());
        if (Senhas.confere(t.nova(), u.pinHash)) throw new WebApplicationException("A nova senha deve ser diferente da atual", 422);
        u.pinHash = Senhas.gerar(t.nova()); u.provisoria = false;
        auditor.registrar("EDICAO", "Usuário", u.id, "Senha alterada pelo próprio usuário '" + u.login + "'", null, null);
        return Map.of("ok", true);
    }

    /** Gerente redefine a senha de outro usuário (a nova fica provisória em produção). */
    @POST @Path("/{id}/senha") @Transactional
    public Usuario trocarSenha(@PathParam("id") Long id, @QueryParam("nova") String nova) {
        validarNova(nova);
        Usuario u = Usuario.findById(id); if (u == null) throw new NotFoundException();
        u.pinHash = Senhas.gerar(nova); u.provisoria = provisoria;
        auditor.registrar("EDICAO", "Usuário", id, "Senha redefinida do usuário '" + u.login + "'", null, null);
        return u;
    }

    @GET public List<Usuario> listar() { return Usuario.listAll(); }

    @POST @Transactional public Usuario criar(Usuario u) {
        if (u.login == null || u.login.isBlank()) throw new WebApplicationException("Informe o login", 422);
        if (!List.of("operador", "supervisor", "gerente").contains(u.perfil)) throw new WebApplicationException("Perfil inválido (operador, supervisor ou gerente)", 422);
        validarNova(u.pin);
        u.login = u.login.trim().toLowerCase();
        if (Usuario.count("login", u.login) > 0) throw new WebApplicationException("Já existe um usuário com esse login", 409);
        u.pinHash = Senhas.gerar(u.pin); u.provisoria = provisoria; u.persist();
        auditor.registrar("CADASTRO", "Usuário", u.id, "Usuário criado: " + u.login + " (" + u.perfil + ")", null, u);
        return u;
    }
    @POST @Path("/{id}/ativo") @Transactional public Usuario ativo(@PathParam("id") Long id, @QueryParam("valor") boolean valor) {
        Usuario u = Usuario.findById(id); if (u == null) throw new NotFoundException();
        if (!valor && u.login.equalsIgnoreCase(auditor.usuario())) throw new WebApplicationException("Você não pode desativar o próprio usuário", 422);
        u.ativo = valor;
        auditor.registrar(valor ? "REATIVACAO" : "DESATIVACAO", "Usuário", id, "Usuário '" + u.login + "' " + (valor ? "reativado" : "desativado"), null, null);
        return u;
    }
    /** Autorização de supervisor/gerente no caixa (cancelamento, desconto, estorno): confere a senha contra os supervisores e gerentes ativos. */
    @POST @Path("/validar")
    public Map<String, String> validar(Pin p) {
        if (p == null || p.pin() == null || p.pin().isBlank()) throw new ForbiddenException("PIN inválido");
        List<Usuario> candidatos = Usuario.list("ativo = true and perfil in ('supervisor', 'gerente')");
        for (Usuario u : candidatos) if (Senhas.confere(p.pin(), u.pinHash)) return Map.of("nome", u.nome, "perfil", u.perfil);
        throw new ForbiddenException("PIN inválido");
    }

    private void validarNova(String s) {
        if (s == null || s.length() < minimo) throw new WebApplicationException("Senha mínima de " + minimo + " caracteres", 422);
    }
}
