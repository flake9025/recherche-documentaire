package fr.vvlabs.recherche.service.document;

import fr.vvlabs.recherche.model.UserEntity;
import fr.vvlabs.recherche.model.UserRole;
import fr.vvlabs.recherche.repository.DocumentRepository;
import fr.vvlabs.recherche.repository.UserRepository;
import fr.vvlabs.recherche.service.user.UserService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.util.HashSet;
import java.util.Set;

@Service
@RequiredArgsConstructor
public class DocumentAccessService {
    private final UserService userService;
    private final UserRepository userRepository;
    private final DocumentRepository documentRepository;

    public UserEntity currentUser() {
        var authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication == null || !authentication.isAuthenticated()) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Connexion requise");
        }
        return userService.requireUser(authentication.getName());
    }

    public Set<Long> visibleOwnerIds(UserEntity user) {
        Set<Long> ids = new HashSet<>();
        ids.add(user.getId());
        if (user.getRole() == UserRole.MANAGER) {
            userRepository.findByManagerId(user.getId()).stream()
                    .filter(member -> member.getRole() == UserRole.USER)
                    .map(UserEntity::getId).forEach(ids::add);
        }
        return ids;
    }

    @Transactional(readOnly = true)
    public Set<Long> visibleDocumentIds() {
        UserEntity user = currentUser();
        return new HashSet<>(user.getRole() == UserRole.ADMIN
                ? documentRepository.findAllIds()
                : documentRepository.findIdsByOwnerIdIn(visibleOwnerIds(user)));
    }

    @Transactional(readOnly = true)
    public void requireRead(Long documentId) {
        UserEntity user = currentUser();
        var document = documentRepository.findById(documentId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND));
        if (user.getRole() != UserRole.ADMIN && !visibleOwnerIds(user).contains(document.getOwnerId())) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND);
        }
    }
}
