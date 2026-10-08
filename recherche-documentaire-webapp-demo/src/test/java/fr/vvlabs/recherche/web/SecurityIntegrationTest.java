package fr.vvlabs.recherche.web;

import fr.vvlabs.recherche.config.SecurityConfig;
import fr.vvlabs.recherche.model.UserEntity;
import fr.vvlabs.recherche.model.UserRole;
import fr.vvlabs.recherche.repository.DocumentRepository;
import fr.vvlabs.recherche.repository.UserRepository;
import fr.vvlabs.recherche.service.document.DocumentAccessService;
import fr.vvlabs.recherche.service.document.DocumentService;
import fr.vvlabs.recherche.service.user.UserService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.ContextConfiguration;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.junit.jupiter.SpringExtension;
import org.springframework.test.context.web.WebAppConfiguration;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;
import org.springframework.web.servlet.config.annotation.EnableWebMvc;

import java.util.Optional;
import java.util.Set;
import java.util.List;

import static org.mockito.Mockito.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.*;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@ExtendWith(SpringExtension.class)
@WebAppConfiguration
@ContextConfiguration(classes = SecurityIntegrationTest.Config.class)
@TestPropertySource(properties = {"app.search.wildcard=false", "app.search.distance.enabled=false",
        "app.search.distance.levenshtein=~2"})
class SecurityIntegrationTest {
    @Autowired private WebApplicationContext context;
    @Autowired private UserRepository users;
    @Autowired private DocumentRepository documents;
    @Autowired private DocumentService documentService;
    @Autowired private PasswordEncoder passwords;
    @Autowired private fr.vvlabs.recherche.service.search.SearchService searchService;
    @Autowired private fr.vvlabs.recherche.service.ai.AiGateway gateway;
    private MockMvc mvc;

    @BeforeEach
    void setup() throws Exception {
        reset(users, documents, documentService, searchService, gateway);
        mvc = MockMvcBuilders.webAppContextSetup(context).apply(springSecurity()).build();
        var alice = account(1L, "alice", UserRole.USER);
        var admin = account(9L, "admin", UserRole.ADMIN);
        when(users.findByUsername("alice")).thenReturn(Optional.of(alice));
        when(users.findByUsername("admin")).thenReturn(Optional.of(admin));
        when(documents.findIdsByOwnerIdIn(Set.of(1L))).thenReturn(List.of(10L));
        when(documentService.findByIds(Set.of(10L))).thenReturn(List.of());
        when(searchService.getType()).thenReturn("lucene");
        when(searchService.search(any())).thenAnswer(call -> {
            var result = new fr.vvlabs.recherche.dto.SearchResultDTO();
            var allowed = new fr.vvlabs.recherche.dto.SearchFragmentDTO();
            allowed.setId("10");
            allowed.setName("Public");
            allowed.setFragment("texte autorise");
            var forbidden = new fr.vvlabs.recherche.dto.SearchFragmentDTO();
            forbidden.setId("20");
            forbidden.setName("Secret");
            result.setFragments(List.of(allowed, forbidden));
            result.setNbResults(2);
            return result;
        });
    }

    @Test
    void anonymousCannotReadDocuments() throws Exception {
        mvc.perform(get("/api/documents")).andExpect(status().isUnauthorized());
    }

    @Test
    void realLoginCreatesSessionThatCanReadOnlyAuthorizedDocuments() throws Exception {
        MockHttpSession session = login("alice");
        mvc.perform(get("/api/auth/me").session(session)).andExpect(status().isOk())
                .andExpect(jsonPath("$.username").value("alice")).andExpect(jsonPath("$.role").value("USER"));
        mvc.perform(get("/api/documents").session(session)).andExpect(status().isOk());
        verify(documentService).findByIds(Set.of(10L));
    }

    @Test
    void userCannotAdministerAndMutationsRequireCsrf() throws Exception {
        mvc.perform(get("/api/admin/users").session(login("alice"))).andExpect(status().isForbidden());
        mvc.perform(post("/api/admin/users").session(login("admin"))
                .contentType("application/json").content("{}")).andExpect(status().isForbidden());
    }

    @Test
    void disableAndPasswordChangeRevokeAnExistingSession() throws Exception {
        MockHttpSession session = login("alice");
        when(users.findByUsername("alice")).thenReturn(Optional.empty());
        mvc.perform(get("/api/documents").session(session)).andExpect(status().isUnauthorized());
        var user = account(1L, "alice", UserRole.USER);
        when(users.findByUsername("alice")).thenReturn(Optional.of(user));
        session = login("alice");
        user.setPasswordHash(passwords.encode("another-password-456"));
        mvc.perform(get("/api/documents").session(session)).andExpect(status().isUnauthorized());
    }

    @Test
    void roleChangesAreAppliedToExistingSessions() throws Exception {
        MockHttpSession session = login("admin");
        var demoted = account(9L, "admin", UserRole.USER);
        // Meme hash que la session : tester le changement de role sans changement de mot de passe.
        var current = users.findByUsername("admin").orElseThrow();
        demoted.setPasswordHash(current.getPasswordHash());
        when(users.findByUsername("admin")).thenReturn(Optional.of(demoted));
        mvc.perform(get("/api/admin/users").session(session)).andExpect(status().isForbidden());
    }

    @Test
    void searchIgnoresClientScopeAndKeepsUnauthorizedResultsAwayFromAi() throws Exception {
        when(gateway.summarize(eq("local"), any(), any())).thenAnswer(call -> {
            List<fr.vvlabs.recherche.dto.SearchFragmentDTO> fragments = call.getArgument(2);
            org.assertj.core.api.Assertions.assertThat(fragments)
                    .extracting(fr.vvlabs.recherche.dto.SearchFragmentDTO::getId).containsExactly("10");
            return new fr.vvlabs.recherche.service.ai.AiGateway.Summary("Resume [1]", "local",
                    List.of(new fr.vvlabs.recherche.service.ai.AiGateway.Source(1, "10", "Public", "/api/documents/10/file")));
        });
        mvc.perform(post("/api/search/").session(login("alice")).with(csrf())
                .contentType("application/json")
                .content("{\"query\":\"rapport\",\"allowedDocumentIds\":[20],\"summarize\":true,\"aiModel\":\"local\"}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.nbResults").value(1))
                .andExpect(jsonPath("$.fragments[0].id").value("10"))
                .andExpect(jsonPath("$.summary.sources[0].documentId").value("10"));
        var captor = org.mockito.ArgumentCaptor.forClass(fr.vvlabs.recherche.dto.SearchRequestDTO.class);
        verify(searchService).search(captor.capture());
        org.assertj.core.api.Assertions.assertThat(captor.getValue().getAllowedDocumentIds()).containsExactly(10L);
    }

    @Test
    void synthesisIsOptInAndFailureDoesNotRemoveSearchResults() throws Exception {
        var session = login("alice");
        mvc.perform(post("/api/search/").session(session).with(csrf())
                .contentType("application/json").content("{\"query\":\"rapport\"}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.nbResults").value(1));
        verifyNoInteractions(gateway);
        when(gateway.summarize(any(), any(), any())).thenThrow(new org.springframework.web.server.ResponseStatusException(
                org.springframework.http.HttpStatus.BAD_GATEWAY, "Service IA indisponible"));
        mvc.perform(post("/api/search/").session(session).with(csrf())
                .contentType("application/json").content("{\"query\":\"rapport\",\"summarize\":true,\"aiModel\":\"local\"}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.nbResults").value(1))
                .andExpect(jsonPath("$.summaryError").value("Service IA indisponible"));
    }

    @Test
    void fileAccessChecksOwnerAndAutocompleteUsesOnlyVisibleAuthors() throws Exception {
        when(documents.findById(20L)).thenReturn(Optional.of(
                new fr.vvlabs.recherche.model.DocumentEntity().setId(20L).setOwnerId(2L)));
        when(documentService.findByIds(Set.of(10L))).thenReturn(List.of(
                new fr.vvlabs.recherche.dto.DocumentDTO().setId(10L).setAuteur("Public")));
        var session = login("alice");
        mvc.perform(get("/api/documents/20/file").session(session)).andExpect(status().isNotFound());
        verify(documentService, never()).getFileResource(any());
        mvc.perform(get("/api/autocomplete/authors?query=Pu").session(session))
                .andExpect(status().isOk()).andExpect(jsonPath("$[0].author").value("Public"));
    }

    private MockHttpSession login(String username) throws Exception {
        return (MockHttpSession) mvc.perform(post("/api/auth/login").with(csrf())
                .param("username", username).param("password", "test-password-123"))
                .andExpect(status().isNoContent()).andReturn().getRequest().getSession(false);
    }

    private UserEntity account(Long id, String username, UserRole role) {
        var user = new UserEntity();
        user.setId(id);
        user.setUsername(username);
        user.setDisplayName(username);
        user.setRole(role);
        user.setPasswordHash(passwords.encode("test-password-123"));
        return user;
    }

    @Configuration
    @EnableWebSecurity
    @EnableWebMvc
    @Import({SecurityConfig.class, AuthController.class, DocumentController.class, UserAdminController.class,
            SearchController.class, AutocompleteController.class, ApiErrorHandler.class})
    static class Config {
        @Bean UserRepository users() { return mock(UserRepository.class); }
        @Bean DocumentRepository documents() { return mock(DocumentRepository.class); }
        @Bean DocumentService documentService() { return mock(DocumentService.class); }
        @Bean fr.vvlabs.recherche.service.search.SearchStoreInitializer initializer() {
            return mock(fr.vvlabs.recherche.service.search.SearchStoreInitializer.class);
        }
        @Bean fr.vvlabs.recherche.service.search.SearchService searchService() {
            return mock(fr.vvlabs.recherche.service.search.SearchService.class);
        }
        @Bean fr.vvlabs.recherche.service.search.SearchServiceFactory searchFactory(fr.vvlabs.recherche.service.search.SearchService service) {
            var factory = mock(fr.vvlabs.recherche.service.search.SearchServiceFactory.class);
            when(factory.getDefaultSearchService()).thenReturn(service);
            return factory;
        }
        @Bean fr.vvlabs.recherche.service.metrics.SearchMetricsRecorder metrics() {
            return mock(fr.vvlabs.recherche.service.metrics.SearchMetricsRecorder.class);
        }
        @Bean fr.vvlabs.recherche.service.ai.AiGateway gateway() { return mock(fr.vvlabs.recherche.service.ai.AiGateway.class); }
        @Bean fr.vvlabs.recherche.service.index.lucene.LuceneAutocompleteService autocomplete() {
            return mock(fr.vvlabs.recherche.service.index.lucene.LuceneAutocompleteService.class);
        }
        @Bean UserService userService(UserRepository users) { return new UserService(users); }
        @Bean DocumentAccessService access(UserService userService, UserRepository users, DocumentRepository documents) {
            return new DocumentAccessService(userService, users, documents);
        }
    }
}
