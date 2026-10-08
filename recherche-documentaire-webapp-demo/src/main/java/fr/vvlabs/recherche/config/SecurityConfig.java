package fr.vvlabs.recherche.config;

import fr.vvlabs.recherche.service.user.UserService;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.access.intercept.AuthorizationFilter;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;

@Configuration
public class SecurityConfig {
    @Bean
    public PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder();
    }

    @Bean
    public SecurityFilterChain securityFilterChain(HttpSecurity http, UserService users) throws Exception {
        http.authorizeHttpRequests(auth -> auth
                .requestMatchers("/login.html", "/login.js", "/auth.js", "/style.css", "/api/auth/csrf",
                        "/actuator/health", "/actuator/health/**", "/error").permitAll()
                .requestMatchers("/api/admin/**", "/admin.html", "/admin.js", "/api/stats/**",
                        "/api/bulk/**", "/api/autocomplete/authors/rebuild", "/actuator/**",
                        "/swagger-ui/**", "/v3/api-docs/**").hasRole("ADMIN")
                .anyRequest().authenticated());
        http.formLogin(form -> form.loginPage("/login.html").loginProcessingUrl("/api/auth/login")
                .successHandler((request, response, authentication) -> response.setStatus(204))
                .failureHandler((request, response, exception) -> response.sendError(401, "Identifiants invalides"))
                .permitAll());
        http.logout(logout -> logout.logoutUrl("/api/auth/logout")
                .logoutSuccessHandler((request, response, authentication) -> response.setStatus(204)));
        http.httpBasic(org.springframework.security.config.Customizer.withDefaults());
        http.exceptionHandling(errors -> errors
                .authenticationEntryPoint((request, response, exception) -> {
                    if (request.getRequestURI().startsWith("/api/") || request.getRequestURI().startsWith("/actuator/")) {
                        response.sendError(401, "Connexion requise");
                    } else {
                        response.sendRedirect("/login.html");
                    }
                })
                .accessDeniedHandler((request, response, exception) -> response.sendError(403, "Acces refuse")));
        http.addFilterBefore(new OncePerRequestFilter() {
            @Override
            protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
                    throws ServletException, IOException {
                var authentication = SecurityContextHolder.getContext().getAuthentication();
                if (authentication != null && authentication.getPrincipal() instanceof UserDetails previous) {
                    try {
                        UserDetails current = users.loadUserByUsername(previous.getUsername());
                        if (!current.isEnabled() || !current.getPassword().equals(previous.getPassword())) {
                            throw new UsernameNotFoundException("Session revoquee");
                        }
                        SecurityContextHolder.getContext().setAuthentication(
                                UsernamePasswordAuthenticationToken.authenticated(current, null, current.getAuthorities()));
                    } catch (UsernameNotFoundException exception) {
                        SecurityContextHolder.clearContext();
                        var session = request.getSession(false);
                        if (session != null) {
                            session.invalidate();
                        }
                        response.sendError(401, "Session revoquee");
                        return;
                    }
                }
                chain.doFilter(request, response);
            }
        }, AuthorizationFilter.class);
        return http.build();
    }
}
