package fr.vvlabs.recherche.web;

import fr.vvlabs.recherche.model.DocumentEntity;
import fr.vvlabs.recherche.model.UserEntity;
import fr.vvlabs.recherche.model.UserRole;
import fr.vvlabs.recherche.repository.DocumentRepository;
import fr.vvlabs.recherche.repository.UserRepository;
import fr.vvlabs.recherche.service.document.DocumentAccessService;
import fr.vvlabs.recherche.service.user.UserService;
import com.zaxxer.hikari.HikariDataSource;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.core.io.ClassPathResource;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;
import org.springframework.jdbc.datasource.init.ScriptUtils;
import org.springframework.orm.jpa.JpaTransactionManager;
import org.springframework.orm.jpa.LocalContainerEntityManagerFactoryBean;
import org.springframework.orm.jpa.vendor.HibernateJpaVendorAdapter;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.ContextConfiguration;
import org.springframework.test.context.junit.jupiter.SpringExtension;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.EnableTransactionManagement;

import javax.sql.DataSource;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import static org.assertj.core.api.Assertions.*;

@ExtendWith(SpringExtension.class)
@ContextConfiguration(classes = UserPersistenceIntegrationTest.Config.class)
class UserPersistenceIntegrationTest {
    @Autowired private UserRepository users;
    @Autowired private DocumentRepository documents;
    @Autowired private UserAdminController admin;
    @Autowired private PasswordEncoder passwords;
    @Autowired private DocumentAccessService access;
    private UserEntity actor;

    @BeforeEach
    void setup() {
        documents.deleteAllInBatch();
        users.findAll().forEach(user -> { user.setManagerId(null); users.save(user); });
        users.deleteAllInBatch();
        actor = seed("admin", UserRole.ADMIN);
        authenticate(actor);
    }

    @AfterEach
    void cleanup() { SecurityContextHolder.clearContext(); }

    @Test
    void crudPersistsHashesAssignmentsAndUpdatesAndDeletesAccounts() {
        var manager = admin.create(input("carol", UserRole.MANAGER, null));
        var alice = admin.create(input("alice", UserRole.USER, manager.id()));
        var stored = users.findById(alice.id()).orElseThrow();
        assertThat(stored.getManagerId()).isEqualTo(manager.id());
        assertThat(stored.getPasswordHash()).isNotEqualTo("test-password-123");
        assertThat(passwords.matches("test-password-123", stored.getPasswordHash())).isTrue();
        var changed = admin.update(alice.id(), new UserAdminController.UserInput("alice", "Alice",
                "", UserRole.USER, manager.id(), false));
        assertThat(changed.enabled()).isFalse();
        assertThat(users.findById(alice.id()).orElseThrow().getPasswordHash()).isEqualTo(stored.getPasswordHash());
        assertThat(admin.list()).hasSize(3);
        admin.delete(alice.id());
        assertThat(users.findById(alice.id())).isEmpty();
    }

    @Test
    void validatesManagerAndProtectsDependenciesAndLastAdmin() {
        assertThatThrownBy(() -> admin.create(input("alice", UserRole.USER, actor.getId())))
                .hasMessageContaining("responsable actif");
        var manager = admin.create(input("carol", UserRole.MANAGER, null));
        var alice = admin.create(input("alice", UserRole.USER, manager.id()));
        assertThatThrownBy(() -> admin.delete(manager.id())).hasMessageContaining("rattache");
        assertThatThrownBy(() -> admin.update(actor.getId(), input("admin", UserRole.MANAGER, null)))
                .hasMessageContaining("dernier administrateur");
        documents.saveAndFlush(new DocumentEntity().setOwnerId(alice.id()).setTitreDocument("titre")
                .setAuteurDepot("auteur").setCategoriesEns("note"));
        assertThatThrownBy(() -> admin.delete(alice.id())).hasMessageContaining("documents");
        assertThatThrownBy(() -> admin.create(input("carol", UserRole.MANAGER, null)))
                .hasMessageContaining("deja utilise");
    }

    @Test
    void managerCannotBecomeAUserReportingToThemselves() {
        var manager = admin.create(input("carol", UserRole.MANAGER, null));
        assertThatThrownBy(() -> admin.update(manager.id(), input("carol", UserRole.USER, manager.id())))
                .hasMessageContaining("propre responsable");
        var unchanged = users.findById(manager.id()).orElseThrow();
        assertThat(unchanged.getRole()).isEqualTo(UserRole.MANAGER);
        assertThat(unchanged.getManagerId()).isNull();
    }

    @Test
    void concurrentDemotionsCannotRemoveBothLastAdministrators() throws Exception {
        var second = seed("second", UserRole.ADMIN);
        try (var executor = Executors.newFixedThreadPool(2)) {
            List<Future<Boolean>> outcomes = executor.invokeAll(List.of(
                    () -> demote(actor), () -> demote(second)));
            assertThat(outcomes.stream().map(future -> {
                try { return future.get(); } catch (Exception exception) { throw new AssertionError(exception); }
            }).filter(Boolean::booleanValue).count()).isEqualTo(1);
        }
        assertThat(users.findAll().stream().filter(user -> user.isEnabled() && user.getRole() == UserRole.ADMIN).count())
                .isEqualTo(1);
    }

    @Test
    void persistedOwnerAndManagerRelationshipsDetermineActualDocumentScope() {
        var manager = admin.create(input("carol", UserRole.MANAGER, null));
        var alice = admin.create(input("alice", UserRole.USER, manager.id()));
        var other = admin.create(input("david", UserRole.MANAGER, null));
        Long own = saveDocument(manager.id());
        Long child = saveDocument(alice.id());
        Long outside = saveDocument(other.id());
        Long legacy = saveDocument(null);
        authenticate(users.findById(manager.id()).orElseThrow());
        assertThat(access.visibleDocumentIds()).containsExactlyInAnyOrder(own, child);
        authenticate(users.findById(alice.id()).orElseThrow());
        assertThat(access.visibleDocumentIds()).containsExactly(child);
        authenticate(actor);
        assertThat(access.visibleDocumentIds()).containsExactlyInAnyOrder(own, child, outside, legacy);
    }

    private Long saveDocument(Long ownerId) {
        return documents.saveAndFlush(new DocumentEntity().setOwnerId(ownerId).setTitreDocument("titre")
                .setAuteurDepot("auteur").setCategoriesEns("rapport")).getId();
    }

    private boolean demote(UserEntity user) {
        authenticate(user);
        try {
            admin.update(user.getId(), input(user.getUsername(), UserRole.MANAGER, null));
            return true;
        } catch (org.springframework.web.server.ResponseStatusException exception) {
            assertThat(exception.getStatusCode().value()).isEqualTo(409);
            return false;
        } finally {
            SecurityContextHolder.clearContext();
        }
    }

    private UserEntity seed(String name, UserRole role) {
        var user = new UserEntity();
        user.setUsername(name);
        user.setDisplayName(name);
        user.setRole(role);
        user.setPasswordHash(passwords.encode("test-password-123"));
        return users.saveAndFlush(user);
    }

    private void authenticate(UserEntity user) {
        SecurityContextHolder.getContext().setAuthentication(UsernamePasswordAuthenticationToken.authenticated(
                new UserService.SessionUser(user.getUsername(), user.getPasswordHash(), user.getRole()), null, List.of()));
    }

    private UserAdminController.UserInput input(String name, UserRole role, Long managerId) {
        return new UserAdminController.UserInput(name, name, "test-password-123", role, managerId, true);
    }

    @Configuration
    @EnableJpaRepositories("fr.vvlabs.recherche.repository")
    @EnableTransactionManagement(proxyTargetClass = true)
    @Import({UserService.class, DocumentAccessService.class, UserAdminController.class})
    static class Config {
        @Bean(destroyMethod = "close") DataSource dataSource() throws Exception {
            var dataSource = new HikariDataSource();
            dataSource.setJdbcUrl("jdbc:h2:mem:users;MODE=PostgreSQL;DB_CLOSE_DELAY=-1");
            dataSource.setUsername("sa");
            dataSource.setMaximumPoolSize(4);
            try (var connection = dataSource.getConnection()) {
                ScriptUtils.executeSqlScript(connection, new ClassPathResource("schema.sql"));
            }
            return dataSource;
        }
        @Bean LocalContainerEntityManagerFactoryBean entityManagerFactory(DataSource dataSource) {
            var factory = new LocalContainerEntityManagerFactoryBean();
            factory.setDataSource(dataSource);
            factory.setPackagesToScan("fr.vvlabs.recherche.model");
            factory.setJpaVendorAdapter(new HibernateJpaVendorAdapter());
            factory.setJpaPropertyMap(Map.of("hibernate.hbm2ddl.auto", "validate",
                    "hibernate.physical_naming_strategy", "org.hibernate.boot.model.naming.PhysicalNamingStrategySnakeCaseImpl"));
            return factory;
        }
        @Bean PlatformTransactionManager transactionManager(jakarta.persistence.EntityManagerFactory factory) {
            return new JpaTransactionManager(factory);
        }
        @Bean PasswordEncoder passwordEncoder() { return new BCryptPasswordEncoder(); }
    }
}
