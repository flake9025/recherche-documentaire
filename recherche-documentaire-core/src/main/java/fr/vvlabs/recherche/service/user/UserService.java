package fr.vvlabs.recherche.service.user;

import fr.vvlabs.recherche.model.UserEntity;
import fr.vvlabs.recherche.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class UserService implements UserDetailsService {
    private final UserRepository repository;

    @Override
    @Transactional(readOnly = true)
    public UserDetails loadUserByUsername(String username) {
        UserEntity user = requireUser(username);
        return new SessionUser(user.getUsername(), user.getPasswordHash(), user.getRole());
    }

    @Transactional(readOnly = true)
    public UserEntity requireUser(String username) {
        return repository.findByUsername(username)
                .filter(UserEntity::isEnabled)
                .orElseThrow(() -> new UsernameNotFoundException("Compte indisponible"));
    }

    public record SessionUser(String username, String password, fr.vvlabs.recherche.model.UserRole role)
            implements UserDetails {
        @Override public String getUsername() { return username; }
        @Override public String getPassword() { return password; }
        @Override public java.util.Collection<? extends org.springframework.security.core.GrantedAuthority> getAuthorities() {
            return java.util.List.of(new org.springframework.security.core.authority.SimpleGrantedAuthority("ROLE_" + role.name()));
        }
    }
}
