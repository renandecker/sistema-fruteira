package br.com.fruteira.retaguarda;

import br.com.fruteira.retaguarda.Entities.Usuario;
import io.quarkus.runtime.StartupEvent;
import jakarta.enterprise.context.ApplicationScoped;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import jakarta.enterprise.event.Observes;
import jakarta.transaction.Transactional;

/** Cria os usuários iniciais (login = senha = perfil). TROQUE as senhas em "Usuários e perfis". */
@ApplicationScoped
public class Seed {
    @ConfigProperty(name = "fruteira.seed", defaultValue = "false") boolean seed; // true só em dev
    @ConfigProperty(name = "fruteira.admin.senha-inicial") java.util.Optional<String> senhaInicial;   // produção: senha do 1º gerente (ADMIN_SENHA_INICIAL)

    @Transactional
    void onStart(@Observes StartupEvent ev) {
        if (Usuario.count() > 0) return;
        String inicial = senhaInicial.map(String::trim).orElse("");
        if (!seed && !inicial.isEmpty()) {
            // Produção, banco sem usuários: cria o gerente inicial com a senha do .env; ele troca no primeiro acesso.
            Usuario g = new Usuario(); g.nome = "Gerente"; g.login = "gerente"; g.perfil = "gerente"; g.pinHash = Senhas.gerar(inicial); g.provisoria = true; g.persist();
            return;
        }
        if (!seed) return;
        for (String perfil : new String[]{"gerente", "supervisor", "operador"}) {
            Usuario u = new Usuario();
            u.nome = Character.toUpperCase(perfil.charAt(0)) + perfil.substring(1);
            u.login = perfil; u.perfil = perfil; u.pinHash = Senhas.gerar(perfil);
            u.persist();
        }
    }
}
