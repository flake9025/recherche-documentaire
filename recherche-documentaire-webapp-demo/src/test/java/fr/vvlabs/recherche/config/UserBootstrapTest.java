package fr.vvlabs.recherche.config;

import fr.vvlabs.recherche.model.UserEntity;
import fr.vvlabs.recherche.model.UserRole;
import fr.vvlabs.recherche.repository.UserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class UserBootstrapTest {
    @Mock
    private UserRepository users;
    @Mock
    private PasswordEncoder passwords;
    @Mock
    private JdbcTemplate jdbc;

    private UserBootstrap bootstrap;

    @BeforeEach
    void setUp() {
        bootstrap = new UserBootstrap(users, passwords, jdbc);
        ReflectionTestUtils.setField(bootstrap, "demoEnabled", false);
    }

    static Stream<String> invalidPasswords() {
        return Stream.of(null, "", "short", "a".repeat(73), "\u00E9".repeat(37));
    }

    @ParameterizedTest
    @MethodSource("invalidPasswords")
    void invalidBootstrapPasswordFailsWithAnActionableConfigurationError(String password) {
        ReflectionTestUtils.setField(bootstrap, "bootstrapPassword", password);

        assertThatThrownBy(() -> bootstrap.run(null))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("APP_BOOTSTRAP_PASSWORD")
                .hasMessageContaining("12 caracteres minimum")
                .hasMessageContaining("72 octets UTF-8 maximum")
                .hasMessageContaining("Recreer le conteneur");
        verify(users, never()).saveAndFlush(any());
        verifyNoInteractions(passwords);
    }

    @Test
    void existingUsersDoNotRequireOrResetTheBootstrapPassword() {
        when(users.count()).thenReturn(1L);
        ReflectionTestUtils.setField(bootstrap, "bootstrapPassword", "");

        bootstrap.run(null);

        verify(users, never()).saveAndFlush(any());
        verifyNoInteractions(passwords);
    }

    @Test
    void validPasswordCreatesOnlyTheAdministratorWhenDemoIsDisabled() {
        ReflectionTestUtils.setField(bootstrap, "bootstrapPassword", "test-password-123");
        when(passwords.encode("test-password-123")).thenReturn("encoded-password");
        when(users.saveAndFlush(any(UserEntity.class))).thenAnswer(invocation -> invocation.getArgument(0));

        bootstrap.run(null);

        var saved = ArgumentCaptor.forClass(UserEntity.class);
        verify(users).saveAndFlush(saved.capture());
        assertThat(saved.getValue().getUsername()).isEqualTo("admin");
        assertThat(saved.getValue().getRole()).isEqualTo(UserRole.ADMIN);
        assertThat(saved.getValue().getPasswordHash()).isEqualTo("encoded-password");
    }
}
