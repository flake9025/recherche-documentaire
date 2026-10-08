package fr.vvlabs.recherche.service.ai;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;
import org.springframework.validation.annotation.Validated;

import java.net.URI;
import java.util.LinkedHashMap;
import java.util.Map;

@Component
@ConfigurationProperties("app.ai")
@Validated
@Data
public class AiProperties {
    private boolean enabled;
    @Min(1) @Max(32)
    private int maxConcurrent = 2;
    @Min(1) @Max(300)
    private int timeoutSeconds = 60;
    @Min(1) @Max(10)
    private int maxSources = 5;
    @Min(500) @Max(32000)
    private int maxContextChars = 12000;
    @Min(1) @Max(4096)
    private int maxOutputTokens = 512;
    @Valid
    private Map<String, Model> models = new LinkedHashMap<>();

    public enum Provider { OLLAMA, OPENAI }

    @Data
    public static class Model {
        @NotNull
        private Provider provider;
        @NotNull
        private URI baseUrl;
        @NotBlank
        private String model;
        private String apiKey = "";
    }
}
