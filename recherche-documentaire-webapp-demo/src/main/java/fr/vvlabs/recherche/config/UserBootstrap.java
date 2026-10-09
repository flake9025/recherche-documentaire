package fr.vvlabs.recherche.config;

import fr.vvlabs.recherche.model.UserEntity;
import fr.vvlabs.recherche.model.UserRole;
import fr.vvlabs.recherche.repository.UserRepository;
import fr.vvlabs.recherche.web.UserAdminController;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

@Component
@RequiredArgsConstructor
public class UserBootstrap implements ApplicationRunner {
    private final UserRepository users;
    private final PasswordEncoder passwords;
    private final JdbcTemplate jdbc;
    @Value("${app.users.bootstrap-password:}")
    private String bootstrapPassword;
    @Value("${app.users.demo-enabled:true}")
    private boolean demoEnabled;

    @Override
    @Transactional
    public void run(ApplicationArguments args) {
        jdbc.execute("SELECT pg_advisory_xact_lock(9025001)");
        if (users.count() != 0) {
            return;
        }
        try {
            UserAdminController.validatePassword(bootstrapPassword);
        } catch (ResponseStatusException exception) {
            throw new IllegalStateException(
                    "APP_BOOTSTRAP_PASSWORD invalide : definir un mot de passe initial de 12 caracteres minimum"
                            + " et 72 octets UTF-8 maximum dans l'environnement du conteneur."
                            + " Recreer le conteneur pour appliquer la modification.",
                    exception);
        }
        create("admin", UserRole.ADMIN, null);
        if (demoEnabled) {
            var manager = create("carol", UserRole.MANAGER, null);
            create("alice", UserRole.USER, manager.getId());
            create("bob", UserRole.USER, manager.getId());
            var secondManager = create("david", UserRole.MANAGER, null);
            create("eve", UserRole.USER, secondManager.getId());
        }
    }

    private UserEntity create(String username, UserRole role, Long managerId) {
        var user = new UserEntity();
        user.setUsername(username);
        user.setDisplayName(username);
        user.setPasswordHash(passwords.encode(bootstrapPassword));
        user.setRole(role);
        user.setManagerId(managerId);
        return users.saveAndFlush(user);
    }
}
