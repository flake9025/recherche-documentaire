package fr.vvlabs.recherche.service.ai;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import fr.vvlabs.recherche.dto.SearchFragmentDTO;
import fr.vvlabs.recherche.dto.DocumentDTO;
import fr.vvlabs.recherche.model.UserEntity;
import fr.vvlabs.recherche.service.document.DocumentAccessService;
import fr.vvlabs.recherche.service.document.DocumentService;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.HashMap;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class AiGatewayTest {
    private HttpServer server;
    private AiProperties properties;
    private DocumentAccessService access;
    private DocumentService documents;
    private final Map<Long, String> fileTexts = new HashMap<>();
    private final AtomicReference<String> captured = new AtomicReference<>();
    private final AtomicReference<String> authorization = new AtomicReference<>();

    @BeforeEach
    void setup() throws Exception {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        properties = new AiProperties();
        properties.setEnabled(true);
        properties.setMaxConcurrent(1);
        properties.setMaxSources(1);
        properties.setMaxContextChars(1000);
        access = mock(DocumentAccessService.class);
        documents = mock(DocumentService.class);
        when(documents.findByIds(anySet())).thenAnswer(call -> {
            Set<Long> ids = call.getArgument(0);
            return ids.stream().map(id -> new DocumentDTO().setId(id).setTitre("Document " + id)).toList();
        });
        when(documents.getFileText(any(DocumentDTO.class))).thenAnswer(call ->
                fileTexts.get(((DocumentDTO) call.getArgument(0)).getId()));
        var user = new UserEntity();
        user.setId(1L);
        when(access.currentUser()).thenReturn(user);
        var model = new AiProperties.Model();
        model.setProvider(AiProperties.Provider.OLLAMA);
        model.setModel("mistral");
        model.setBaseUrl(URI.create("http://127.0.0.1:" + server.getAddress().getPort()));
        properties.getModels().put("local", model);
        server.start();
    }

    @AfterEach
    void cleanup() {
        server.stop(0);
    }

    @Test
    void ollamaSendsBoundedSourceTextInsteadOfDisplayMarkupAndMapsCitations() throws Exception {
        respond("/api/chat", "{\"message\":{\"content\":\"Resume [1]\"}}", 200);
        AiGateway gateway = gateway();
        var result = gateway.summarize("local", "question", List.of(fragment("10", "<b>" + "texte ".repeat(2000) + "</b>"),
                fragment("20", "ne pas envoyer")));
        assertThat(result.text()).isEqualTo("Resume [1]");
        assertThat(result.sources()).hasSize(1);
        assertThat(result.sources().getFirst().fileUrl()).isEqualTo("/api/documents/10/file");
        var request = new ObjectMapper().readTree(captured.get());
        assertThat(request.path("stream").asBoolean()).isFalse();
        assertThat(request.path("options").path("num_predict").asInt()).isEqualTo(512);
        assertThat(request.path("messages").get(1).path("content").asText())
                .doesNotContain("<b>", "ne pas envoyer").hasSizeLessThan(1200);
    }

    @Test
    void openAiCompatibleLocalMistralUsesChatCompletions() {
        var model = properties.getModels().get("local");
        model.setProvider(AiProperties.Provider.OPENAI);
        model.setBaseUrl(URI.create(model.getBaseUrl() + "/v1"));
        respond("/v1/chat/completions", "{\"choices\":[{\"message\":{\"content\":\"Resume local [1]\"}}]}", 200);
        assertThat(gateway().summarize("local", "question", List.of(fragment("10", "texte"))).text())
                .isEqualTo("Resume local [1]");
    }

    @Test
    void disabledUnknownModelAndProviderErrorsAreExplicit() {
        AiGateway gateway = gateway();
        assertThatThrownBy(() -> gateway.summarize("untrusted", "q", List.of(fragment("10", "texte"))))
                .isInstanceOf(org.springframework.web.server.ResponseStatusException.class);
        properties.setEnabled(false);
        assertThat(gateway.models()).isEmpty();
        assertThatThrownBy(() -> gateway.summarize("local", "q", List.of(fragment("10", "texte"))))
                .hasMessageContaining("desactivee");
        properties.setEnabled(true);
        respond("/api/chat", "{}", 503);
        assertThatThrownBy(() -> gateway.summarize("local", "q", List.of(fragment("10", "texte"))))
                .hasMessageContaining("invalide");
    }

    @Test
    void boundsConcurrencyAndReleasesPermitAfterCompletion() throws Exception {
        var entered = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        server.createContext("/api/chat", exchange -> {
            entered.countDown();
            try {
                if (!release.await(5, TimeUnit.SECONDS)) throw new IllegalStateException("test timeout");
            } catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
                throw new java.io.IOException(exception);
            }
            byte[] body = "{\"message\":{\"content\":\"Resume\"}}".getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, body.length);
            exchange.getResponseBody().write(body);
            exchange.close();
        });
        var gateway = gateway();
        var first = CompletableFuture.supplyAsync(() -> gateway.summarize("local", "q", List.of(fragment("10", "texte"))));
        try {
            assertThat(entered.await(5, TimeUnit.SECONDS)).isTrue();
            assertThatThrownBy(() -> gateway.summarize("local", "q", List.of(fragment("10", "texte"))))
                    .hasMessageContaining("429");
        } finally {
            release.countDown();
        }
        assertThat(first.get(5, TimeUnit.SECONDS).text()).isEqualTo("Resume");
        assertThat(gateway.summarize("local", "q", List.of(fragment("10", "texte"))).text()).isEqualTo("Resume");
    }

    @Test
    void rejectsOversizedProviderResponseAndEmptySources() {
        respond("/api/chat", "x".repeat(256001), 200);
        var gateway = gateway();
        assertThatThrownBy(() -> gateway.summarize("local", "q", List.of(fragment("10", "texte"))))
                .hasMessageContaining("indisponible");
        assertThatThrownBy(() -> gateway.summarize("local", "q", List.of(fragment("10", ""))))
                .hasMessageContaining("Texte source inexploitable");
    }

    @Test
    void rejectsEmptyOrNonObjectProviderJsonExplicitly() {
        respond("/api/chat", "null", 200);
        assertThatThrownBy(() -> gateway().summarize("local", "q", List.of(fragment("10", "texte"))))
                .hasMessageContaining("JSON IA invalide");
    }

    @Test
    void liteLlmUsesAuthenticatedOpenAiTransportAndDisablesCacheByDefault() throws Exception {
        var model = liteLlmModel();
        respond("/v1/chat/completions", "{\"choices\":[{\"message\":{\"content\":\"Resume [1]\"}}]}", 200);
        gateway().summarize("local", "question", List.of(fragment("10", "texte")));
        var request = new ObjectMapper().readTree(captured.get());
        assertThat(authorization.get()).isEqualTo("Bearer " + model.getApiKey());
        assertThat(request.path("model").asText()).isEqualTo("mistral-local");
        assertThat(request.path("max_tokens").asInt()).isEqualTo(512);
        assertThat(request.path("user").asText()).matches("[0-9a-f]{64}");
        assertThat(request.path("cache").path("no-cache").asBoolean()).isTrue();
        assertThat(request.path("cache").path("no-store").asBoolean()).isTrue();
        assertThat(request.path("cache").has("namespace")).isFalse();
    }

    @Test
    void exactCacheScopeIsStableAndSeparatesUsersSourcesContentAndModelRevisions() throws Exception {
        var model = liteLlmModel();
        properties.setCacheEnabled(true);
        properties.setCacheTtlSeconds(20);
        respond("/v1/chat/completions", "{\"choices\":[{\"message\":{\"content\":\"Resume [1]\"}}]}", 200);
        var gateway = gateway();
        var source = fragment("10", "texte");
        source.setName("Titre commun");
        gateway.summarize("local", "question", List.of(source));
        var request = new ObjectMapper().readTree(captured.get());
        String alice = request.path("user").asText();
        String namespace = request.path("cache").path("namespace").asText();
        assertThat(namespace).matches("documents-[0-9a-f]{64}");
        assertThat(request.path("cache").path("use-cache").asBoolean()).isTrue();
        assertThat(request.path("cache").path("ttl").asInt()).isEqualTo(20);
        gateway.summarize("local", "question", List.of(source));
        assertThat(cacheNamespace()).isEqualTo(namespace);

        var bob = new UserEntity();
        bob.setId(2L);
        when(access.currentUser()).thenReturn(bob);
        gateway.summarize("local", "question", List.of(source));
        assertThat(cacheNamespace()).isNotEqualTo(namespace);
        assertThat(new ObjectMapper().readTree(captured.get()).path("user").asText()).isNotEqualTo(alice);

        bob.setId(1L);
        fileTexts.put(11L, "texte");
        source.setId("11");
        gateway.summarize("local", "question", List.of(source));
        assertThat(cacheNamespace()).isNotEqualTo(namespace);
        source.setId("10");
        source.setFragment("texte modifie");
        gateway.summarize("local", "question", List.of(source));
        assertThat(cacheNamespace()).isEqualTo(namespace);
        fileTexts.put(10L, "contenu source modifie");
        gateway.summarize("local", "question", List.of(source));
        assertThat(cacheNamespace()).isNotEqualTo(namespace);
        fileTexts.put(10L, "texte");
        source.setFragment("texte");
        model.setCacheVersion("2");
        gateway.summarize("local", "question", List.of(source));
        assertThat(cacheNamespace()).isNotEqualTo(namespace);
        model.setCacheVersion("1");
        gateway.summarize("local", "autre question", List.of(source));
        assertThat(cacheNamespace()).isNotEqualTo(namespace);
        properties.setMaxOutputTokens(64);
        gateway.summarize("local", "question", List.of(source));
        assertThat(cacheNamespace()).isNotEqualTo(namespace);
        properties.setMaxOutputTokens(512);
        properties.setCacheTtlSeconds(10);
        gateway.summarize("local", "question", List.of(source));
        assertThat(cacheNamespace()).isNotEqualTo(namespace);
        properties.setCacheTtlSeconds(20);
        gateway.summarize("local", "question", List.of(source));
        assertThat(cacheNamespace()).isEqualTo(namespace);
    }

    @Test
    void liteLlmRequiresTrustedIdentityAndReleasesPermitAfterFailure() {
        liteLlmModel();
        when(access.currentUser()).thenThrow(new org.springframework.web.server.ResponseStatusException(
                org.springframework.http.HttpStatus.UNAUTHORIZED, "Connexion requise"));
        var gateway = gateway();
        assertThatThrownBy(() -> gateway.summarize("local", "q", List.of(fragment("10", "texte"))))
                .hasMessageContaining("401");
        assertThat(captured.get()).isNull();
        var user = new UserEntity();
        user.setId(1L);
        doReturn(user).when(access).currentUser();
        respond("/v1/chat/completions", "{\"choices\":[{\"message\":{\"content\":\"Resume\"}}]}", 200);
        assertThat(gateway.summarize("local", "q", List.of(fragment("10", "texte"))).text()).isEqualTo("Resume");
    }

    @Test
    void liteLlmRefusesMissingOrWeakCredentialOnlyWhenEnabled() {
        var model = liteLlmModel();
        model.setApiKey("");
        assertThatThrownBy(this::gateway).hasMessageContaining("APP_AI_GATEWAY_API_KEY");
        model.setApiKey("short");
        assertThatThrownBy(this::gateway).hasMessageContaining("32 caracteres");
        properties.setEnabled(false);
        assertThat(gateway().models()).isEmpty();
    }

    @Test
    void selectsConfiguredDefaultWithoutChangingAllowedModelIdentifiers() {
        properties.getModels().put("preferred", properties.getModels().get("local"));
        properties.setDefaultModel("preferred");
        assertThat(gateway().models()).extracting(AiGateway.ModelView::id).containsExactly("preferred", "local");
        properties.setDefaultModel("unknown");
        assertThatThrownBy(this::gateway).hasMessageContaining("app.ai.default-model");
    }

    @Test
    void disabledModelsAreNotAdvertisedOrCallableOrUsableAsDefault() {
        var model = properties.getModels().get("local");
        model.setEnabled(false);
        assertThat(gateway().models()).isEmpty();
        assertThatThrownBy(() -> gateway().summarize("local", "q", List.of(fragment("10", "texte"))))
                .hasMessageContaining("Modele IA non autorise");
        assertThat(captured.get()).isNull();
        properties.setDefaultModel("local");
        assertThatThrownBy(this::gateway).hasMessageContaining("modeles actifs");
    }

    @Test
    void upstreamRateLimitIsNotMisreportedAsAnInvalidResponse() {
        liteLlmModel();
        respond("/v1/chat/completions", "{}", 429);
        assertThatThrownBy(() -> gateway().summarize("local", "q", List.of(fragment("10", "texte"))))
                .hasMessageContaining("429", "Gateway IA occupee");
    }

    @Test
    void upstreamAuthenticationErrorDoesNotExposeItsResponseBody() {
        liteLlmModel();
        respond("/v1/chat/completions", "private-upstream-diagnostic", 401);
        assertThatThrownBy(() -> gateway().summarize("local", "q", List.of(fragment("10", "texte"))))
                .hasMessageContaining("Authentification").hasMessageNotContaining("private-upstream-diagnostic");
    }

    @Test
    void includesActualResultBeyondPreviewAndOtherPageWithoutReindexing() throws Exception {
        properties.setMaxContextChars(12000);
        respond("/api/chat", "{\"message\":{\"content\":\"Le rapport indique un controle negatif [1]\"}}", 200);
        var source = fragment("10", "Conseils generaux de prevention des fuites et entretien du circuit.");
        String complete = "Rapport fictif du 12/05/2030\nControle du circuit\n"
                + "Resultat : NEGATIF\nIndice 0,42 ; seuil < 1,00\n"
                + "Conclusion : aucune fuite observee sous reserve des conditions du controle.\n"
                + "Notes de protocole. ".repeat(170)
                + "\nPage 2 : Conseils generaux de prevention des fuites et entretien du circuit.";
        fileTexts.put(10L, complete);
        var result = gateway().summarize("local", "Quel est le resultat du controle du circuit ?", List.of(source));
        var request = new ObjectMapper().readTree(captured.get());
        String prompt = request.path("messages").get(1).path("content").asText();
        var context = new ObjectMapper().readTree(prompt.substring(prompt.indexOf(":\n") + 2).strip());
        assertThat(context.path("extrait").asText()).isEqualTo(complete)
                .contains("NEGATIF", "0,42", "< 1,00", "sous reserve", "Page 2");
        assertThat(context.path("partiel").asBoolean()).isFalse();
        assertThat(result.sources().getFirst().partial()).isFalse();
        verify(documents, times(1)).getFileText(any(DocumentDTO.class));
    }

    @Test
    void respectsSerializedContextBudgetAndSharesItBetweenSources() throws Exception {
        properties.setMaxSources(2);
        properties.setMaxContextChars(1000);
        respond("/api/chat", "{\"message\":{\"content\":\"Resume [1] [2]\"}}", 200);
        var first = fragment("10", "apercu");
        var second = fragment("20", "autre apercu");
        fileTexts.put(10L, "\"Controle du circuit\\n\"\n".repeat(200));
        fileTexts.put(20L, "\"Controle de la pompe\\n\"\n".repeat(200));
        var result = gateway().summarize("local", "controle circuit pompe", List.of(first, second));
        String prompt = new ObjectMapper().readTree(captured.get()).path("messages").get(1).path("content").asText();
        String context = prompt.substring(prompt.indexOf(":\n") + 2);
        assertThat(context.length()).isLessThanOrEqualTo(1000);
        assertThat(result.sources()).hasSize(2).allMatch(AiGateway.Source::partial);
        var lines = context.lines().toList();
        assertThat(lines).hasSize(2);
        assertThat(new ObjectMapper().readTree(lines.get(0)).path("extrait").asText()).contains("circuit");
        assertThat(new ObjectMapper().readTree(lines.get(1)).path("extrait").asText()).contains("pompe");
    }

    @Test
    void revocationWhileReadingSourcePreventsAnyProviderCall() throws Exception {
        respond("/api/chat", "{\"message\":{\"content\":\"Ne doit pas etre appele\"}}", 200);
        var source = fragment("10", "apercu");
        when(documents.getFileText(any(DocumentDTO.class))).thenAnswer(call -> {
            doThrow(new org.springframework.web.server.ResponseStatusException(
                    org.springframework.http.HttpStatus.NOT_FOUND)).when(access).requireRead(10L);
            return "contenu devenu inaccessible";
        });
        assertThatThrownBy(() -> gateway().summarize("local", "question", List.of(source)))
                .isInstanceOf(org.springframework.web.server.ResponseStatusException.class);
        assertThat(captured.get()).isNull();
    }

    @Test
    void extractionErrorPreservesExplicitReasonAndNeverUsesPreviewAsFallback() throws Exception {
        var source = fragment("10", "apercu insuffisant");
        when(documents.getFileText(any(DocumentDTO.class))).thenThrow(new java.io.IOException("fixture"));
        assertThatThrownBy(() -> gateway().summarize("local", "question", List.of(source)))
                .hasMessageContaining("Lecture du contenu source impossible");
        assertThat(captured.get()).isNull();
    }

    @Test
    void catalogExposesHostingAndDisplayNameButNoCredential() throws Exception {
        var model = liteLlmModel();
        model.setHosting(AiProperties.Hosting.CLOUD);
        model.setDisplayName("Modele de demonstration");
        String catalog = new ObjectMapper().writeValueAsString(gateway().models());
        assertThat(catalog).contains("\"hosting\":\"CLOUD\"", "Modele de demonstration")
                .doesNotContain(model.getApiKey(), "baseUrl", "apiKey");
    }

    private AiProperties.Model liteLlmModel() {
        var model = properties.getModels().get("local");
        model.setProvider(AiProperties.Provider.LITELLM);
        model.setModel("mistral-local");
        model.setBaseUrl(URI.create(model.getBaseUrl() + "/v1"));
        model.setApiKey("sk-" + UUID.randomUUID() + UUID.randomUUID());
        return model;
    }

    private String cacheNamespace() throws Exception {
        return new ObjectMapper().readTree(captured.get()).path("cache").path("namespace").asText();
    }

    private AiGateway gateway() {
        return new AiGateway(properties, new SimpleMeterRegistry(), access,
                new AiDocumentContextService(documents, access));
    }

    private SearchFragmentDTO fragment(String id, String text) {
        var fragment = new SearchFragmentDTO();
        fragment.setId(id);
        fragment.setName("Document " + id);
        fragment.setFragment(text);
        fileTexts.put(Long.valueOf(id), org.jsoup.Jsoup.parse(text).text());
        return fragment;
    }

    private void respond(String path, String body, int status) {
        server.createContext(path, exchange -> {
            captured.set(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            authorization.set(exchange.getRequestHeaders().getFirst("Authorization"));
            byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(status, bytes.length);
            exchange.getResponseBody().write(bytes);
            exchange.close();
        });
    }
}
