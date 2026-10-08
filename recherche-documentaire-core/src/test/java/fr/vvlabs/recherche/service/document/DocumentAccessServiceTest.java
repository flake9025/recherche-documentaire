package fr.vvlabs.recherche.service.document;

import fr.vvlabs.recherche.model.DocumentEntity;
import fr.vvlabs.recherche.model.UserEntity;
import fr.vvlabs.recherche.model.UserRole;
import fr.vvlabs.recherche.repository.DocumentRepository;
import fr.vvlabs.recherche.repository.UserRepository;
import fr.vvlabs.recherche.service.user.UserService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;

import java.util.List;
import java.util.Optional;
import java.util.Set;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class DocumentAccessServiceTest {
    private final UserRepository users = mock(UserRepository.class);
    private final DocumentRepository documents = mock(DocumentRepository.class);
    private final UserService userService = mock(UserService.class);
    private final DocumentAccessService access = new DocumentAccessService(userService, users, documents);

    @AfterEach
    void clearSession() {
        SecurityContextHolder.clearContext();
    }

    @Test
    void userOnlySeesOwnStore() {
        authenticate(user(1L, UserRole.USER));
        when(documents.findIdsByOwnerIdIn(Set.of(1L))).thenReturn(List.of(10L));
        assertThat(access.visibleDocumentIds()).containsExactly(10L);
        verifyNoInteractions(users);
    }

    @Test
    void managerSeesOwnStoreAndOnlyDirectUsers() {
        authenticate(user(2L, UserRole.MANAGER));
        when(users.findByManagerId(2L)).thenReturn(List.of(user(1L, UserRole.USER), user(3L, UserRole.USER)));
        when(documents.findIdsByOwnerIdIn(Set.of(1L, 2L, 3L))).thenReturn(List.of(10L, 20L, 30L));
        assertThat(access.visibleDocumentIds()).containsExactlyInAnyOrder(10L, 20L, 30L);
    }

    @Test
    void adminSeesAllDocumentsIncludingLegacyOnes() {
        authenticate(user(9L, UserRole.ADMIN));
        when(documents.findAllIds()).thenReturn(List.of(10L, 99L));
        assertThat(access.visibleDocumentIds()).containsExactlyInAnyOrder(10L, 99L);
    }

    @Test
    void downloadAndLegacyAccessFailClosedForOtherUsers() {
        authenticate(user(1L, UserRole.USER));
        when(documents.findById(20L)).thenReturn(Optional.of(new DocumentEntity().setId(20L).setOwnerId(2L)));
        when(documents.findById(99L)).thenReturn(Optional.of(new DocumentEntity().setId(99L)));
        assertThatThrownBy(() -> access.requireRead(20L)).isInstanceOf(org.springframework.web.server.ResponseStatusException.class);
        assertThatThrownBy(() -> access.requireRead(99L)).isInstanceOf(org.springframework.web.server.ResponseStatusException.class);
    }

    @Test
    void reassignmentImmediatelyChangesManagerScope() {
        authenticate(user(2L, UserRole.MANAGER));
        when(users.findByManagerId(2L)).thenReturn(List.of(user(1L, UserRole.USER)), List.of());
        when(documents.findIdsByOwnerIdIn(Set.of(1L, 2L))).thenReturn(List.of(10L, 20L));
        when(documents.findIdsByOwnerIdIn(Set.of(2L))).thenReturn(List.of(20L));
        assertThat(access.visibleDocumentIds()).contains(10L);
        assertThat(access.visibleDocumentIds()).doesNotContain(10L);
    }

    private void authenticate(UserEntity user) {
        SecurityContextHolder.getContext().setAuthentication(
                UsernamePasswordAuthenticationToken.authenticated("test", null, List.of()));
        when(userService.requireUser("test")).thenReturn(user);
    }

    private UserEntity user(Long id, UserRole role) {
        UserEntity user = new UserEntity();
        user.setId(id);
        user.setRole(role);
        return user;
    }
}
