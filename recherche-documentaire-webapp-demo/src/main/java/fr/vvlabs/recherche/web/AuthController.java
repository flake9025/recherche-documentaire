package fr.vvlabs.recherche.web;

import fr.vvlabs.recherche.model.UserRole;
import fr.vvlabs.recherche.service.document.DocumentAccessService;
import lombok.RequiredArgsConstructor;
import org.springframework.security.web.csrf.CsrfToken;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/auth")
@RequiredArgsConstructor
public class AuthController {
    private final DocumentAccessService access;

    @GetMapping("/csrf")
    public CsrfToken csrf(CsrfToken token) {
        return token;
    }

    @GetMapping("/me")
    public CurrentUser me() {
        var user = access.currentUser();
        return new CurrentUser(user.getId(), user.getUsername(), user.getDisplayName(), user.getRole(), user.getManagerId());
    }

    public record CurrentUser(Long id, String username, String displayName, UserRole role, Long managerId) { }
}
