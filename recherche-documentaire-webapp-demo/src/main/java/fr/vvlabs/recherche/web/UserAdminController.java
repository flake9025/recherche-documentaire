package fr.vvlabs.recherche.web;

import fr.vvlabs.recherche.model.UserEntity;
import fr.vvlabs.recherche.model.UserRole;
import fr.vvlabs.recherche.repository.DocumentRepository;
import fr.vvlabs.recherche.repository.UserRepository;
import fr.vvlabs.recherche.service.document.DocumentAccessService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

import java.nio.charset.StandardCharsets;
import java.util.List;

@RestController
@RequestMapping("/api/admin/users")
@RequiredArgsConstructor
@Slf4j
public class UserAdminController {
    private final UserRepository users;
    private final DocumentRepository documents;
    private final PasswordEncoder passwords;
    private final DocumentAccessService access;

    @GetMapping
    public List<UserView> list() {
        requireAdmin();
        return users.findAll().stream().map(UserView::of).toList();
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    @Transactional
    public UserView create(@Valid @RequestBody UserInput input) {
        users.findAllByOrderByIdAsc();
        Long actorId = requireAdmin();
        UserEntity user = new UserEntity();
        apply(user, input, true);
        user = users.saveAndFlush(user);
        log.info("user_created actor={} target={}", actorId, user.getId());
        return UserView.of(user);
    }

    @PutMapping("/{id}")
    @Transactional
    public UserView update(@PathVariable Long id, @Valid @RequestBody UserInput input) {
        List<UserEntity> locked = users.findAllByOrderByIdAsc();
        Long actorId = requireAdmin();
        UserEntity user = locked.stream().filter(item -> item.getId().equals(id)).findFirst()
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND));
        protectLastAdmin(user, input.role() == UserRole.ADMIN && input.enabled(), locked);
        if ((input.role() != UserRole.MANAGER || !input.enabled()) && !users.findByManagerId(id).isEmpty()) {
            throw conflict("Reaffecter les utilisateurs rattaches avant de modifier ce responsable");
        }
        apply(user, input, false);
        users.flush();
        log.info("user_updated actor={} target={}", actorId, id);
        return UserView.of(user);
    }

    @DeleteMapping("/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @Transactional
    public void delete(@PathVariable Long id) {
        List<UserEntity> locked = users.findAllByOrderByIdAsc();
        Long actorId = requireAdmin();
        UserEntity user = locked.stream().filter(item -> item.getId().equals(id)).findFirst()
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND));
        if (actorId.equals(id)) {
            throw conflict("Impossible de supprimer son propre compte");
        }
        protectLastAdmin(user, false, locked);
        if (!users.findByManagerId(id).isEmpty() || documents.existsByOwnerId(id)) {
            throw conflict("Compte rattache a des utilisateurs ou documents : desactiver le compte ou reaffecter les utilisateurs");
        }
        users.delete(user);
        log.info("user_deleted actor={} target={}", actorId, id);
    }

    private Long requireAdmin() {
        var user = access.currentUser();
        if (user.getRole() != UserRole.ADMIN) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Administration reservee aux administrateurs");
        }
        return user.getId();
    }

    private void apply(UserEntity user, UserInput input, boolean creating) {
        if (users.findByUsername(input.username()).filter(other -> !other.getId().equals(user.getId())).isPresent()) {
            throw conflict("Identifiant deja utilise");
        }
        if (input.role() == UserRole.USER) {
            if (input.managerId() != null && input.managerId().equals(user.getId())) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Un utilisateur ne peut pas etre son propre responsable");
            }
            var manager = input.managerId() == null ? null : users.findById(input.managerId()).orElse(null);
            if (manager == null || !manager.isEnabled() || manager.getRole() != UserRole.MANAGER) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Un responsable actif est obligatoire");
            }
        } else if (input.managerId() != null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Seuls les utilisateurs simples ont un responsable");
        }
        if (creating || (input.password() != null && !input.password().isBlank())) {
            validatePassword(input.password());
            user.setPasswordHash(passwords.encode(input.password()));
        }
        user.setUsername(input.username());
        user.setDisplayName(input.displayName().trim());
        user.setRole(input.role());
        user.setManagerId(input.managerId());
        user.setEnabled(input.enabled());
    }

    public static void validatePassword(String password) {
        if (password == null || password.length() < 12 || password.getBytes(StandardCharsets.UTF_8).length > 72) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Mot de passe : au moins 12 caracteres, au plus 72 octets UTF-8");
        }
    }

    private void protectLastAdmin(UserEntity user, boolean remainsAdmin, List<UserEntity> all) {
        if (user.getRole() == UserRole.ADMIN && user.isEnabled() && !remainsAdmin
                && all.stream().filter(item -> item.isEnabled() && item.getRole() == UserRole.ADMIN).count() <= 1) {
            throw conflict("Le dernier administrateur actif doit etre conserve");
        }
    }

    private ResponseStatusException conflict(String message) {
        return new ResponseStatusException(HttpStatus.CONFLICT, message);
    }

    public record UserInput(
            @NotBlank @Pattern(regexp = "[a-z0-9][a-z0-9._-]{0,79}") String username,
            @NotBlank @Size(max = 120) String displayName,
            @Size(max = 72) String password,
            @NotNull UserRole role,
            Long managerId,
            boolean enabled) { }

    public record UserView(Long id, String username, String displayName, UserRole role,
                           Long managerId, boolean enabled, long version) {
        static UserView of(UserEntity user) {
            return new UserView(user.getId(), user.getUsername(), user.getDisplayName(), user.getRole(),
                    user.getManagerId(), user.isEnabled(), user.getVersion());
        }
    }
}
