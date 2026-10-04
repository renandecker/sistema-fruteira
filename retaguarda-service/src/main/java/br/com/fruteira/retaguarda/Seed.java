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

    @Transactional
    void onStart(@Observes StartupEvent ev) {
        if (!seed || Usuario.count() > 0) return;
        for (String perfil : new String[]{"gerente", "supervisor", "operador"}) {
            Usuario u = new Usuario();
            u.nome = Character.toUpperCase(perfil.charAt(0)) + perfil.substring(1);
            u.login = perfil; u.perfil = perfil; u.pinHash = UsuarioResource.hash(perfil);
            u.persist();
        }
    }
}
