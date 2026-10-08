package fr.vvlabs.recherche.service.ai;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import fr.vvlabs.recherche.dto.SearchFragmentDTO;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.*;

class AiGatewayTest {
    private HttpServer server;
    private AiProperties properties;
    private final AtomicReference<String> captured = new AtomicReference<>();

    @BeforeEach
    void setup() throws Exception {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        properties = new AiProperties();
        properties.setEnabled(true);
        properties.setMaxConcurrent(1);
        properties.setMaxSources(1);
        properties.setMaxContextChars(1000);
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
    void ollamaSendsBoundedContextWithoutMarkupAndMapsCitations() throws Exception {
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
                .hasMessageContaining("Aucun extrait");
    }

    @Test
    void rejectsEmptyOrNonObjectProviderJsonExplicitly() {
        respond("/api/chat", "null", 200);
        assertThatThrownBy(() -> gateway().summarize("local", "q", List.of(fragment("10", "texte"))))
                .hasMessageContaining("JSON IA invalide");
    }

    private AiGateway gateway() { return new AiGateway(properties, new SimpleMeterRegistry()); }

    private SearchFragmentDTO fragment(String id, String text) {
        var fragment = new SearchFragmentDTO();
        fragment.setId(id);
        fragment.setName("Document " + id);
        fragment.setFragment(text);
        return fragment;
    }

    private void respond(String path, String body, int status) {
        server.createContext(path, exchange -> {
            captured.set(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(status, bytes.length);
            exchange.getResponseBody().write(bytes);
            exchange.close();
        });
    }
}
