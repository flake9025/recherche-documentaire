package fr.vvlabs.recherche.service.ai;

import fr.vvlabs.recherche.service.document.DocumentAccessService;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.ConfigDataApplicationContextInitializer;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

class AiGatewayConfigTest {
    private final ApplicationContextRunner context = new ApplicationContextRunner()
            .withInitializer(new ConfigDataApplicationContextInitializer())
            .withUserConfiguration(Config.class);

    @Test
    void litellmProfileBindsGatewayAliasesAndPrefersLocalVllmMistral() {
        context.withPropertyValues("spring.profiles.active=litellm", "app.ai.enabled=true",
                "APP_AI_GATEWAY_API_KEY=sk-" + UUID.randomUUID() + UUID.randomUUID())
                .run(application -> {
                    assertThat(application).hasNotFailed();
                    var properties = application.getBean(AiProperties.class);
                    assertThat(properties.isCacheEnabled()).isFalse();
                    assertThat(properties.getModels().get("mistral-local").getProvider())
                            .isEqualTo(AiProperties.Provider.LITELLM);
                    assertThat(properties.getModels().get("mistral-local").getBaseUrl().toString())
                            .isEqualTo("http://localhost:4000/v1");
                    assertThat(application.getBean(AiGateway.class).models().getFirst().id()).isEqualTo("mistral-local");
                    assertThat(application.getBean(AiGateway.class).models().getFirst().hosting())
                            .isEqualTo(AiProperties.Hosting.LOCAL);
                });
    }

    @Test
    void enabledLitellmFailsStartupWithoutCredential() {
        context.withPropertyValues("spring.profiles.active=litellm", "app.ai.enabled=true",
                "APP_AI_GATEWAY_API_KEY=").run(application -> assertThat(application).hasFailed());
    }

    @Test
    void disabledLitellmDoesNotRequireGatewayCredential() {
        context.withPropertyValues("spring.profiles.active=litellm", "app.ai.enabled=false",
                "APP_AI_GATEWAY_API_KEY=").run(application -> {
                    assertThat(application).hasNotFailed();
                    assertThat(application.getBean(AiGateway.class).models()).isEmpty();
                });
    }

    @Test
    void bedrockProfileExposesOnlyProxyAliasAndKeepsCacheOff() {
        context.withPropertyValues("spring.profiles.active=bedrock", "app.ai.enabled=true",
                "APP_AI_GATEWAY_API_KEY=sk-" + UUID.randomUUID() + UUID.randomUUID(),
                "APP_AI_BEDROCK_MODEL=openai.gpt-oss-20b")
                .run(application -> {
                    assertThat(application).hasNotFailed();
                    var properties = application.getBean(AiProperties.class);
                    assertThat(properties.isCacheEnabled()).isFalse();
                    assertThat(properties.getModels().get("bedrock").getProvider())
                            .isEqualTo(AiProperties.Provider.LITELLM);
                    assertThat(properties.getModels().get("bedrock").getCacheVersion())
                            .isEqualTo("openai.gpt-oss-20b:1");
                    assertThat(application.getBean(AiGateway.class).models())
                            .extracting(AiGateway.ModelView::id).containsExactly("bedrock");
                    assertThat(application.getBean(AiGateway.class).models().getFirst().hosting())
                            .isEqualTo(AiProperties.Hosting.CLOUD);
                    assertThat(application.getBean(AiGateway.class).models().getFirst().displayName())
                            .isEqualTo("openai.gpt-oss-20b");
                });
    }

    @Test
    void disabledBedrockDoesNotRequireAnyCloudCredential() {
        context.withPropertyValues("spring.profiles.active=bedrock", "app.ai.enabled=false",
                "APP_AI_GATEWAY_API_KEY=").run(application -> {
                    assertThat(application).hasNotFailed();
                    assertThat(application.getBean(AiGateway.class).models()).isEmpty();
                });
    }

    @Test
    void enabledBedrockStillRequiresPrivateProxyCredential() {
        context.withPropertyValues("spring.profiles.active=bedrock", "app.ai.enabled=true",
                "APP_AI_GATEWAY_API_KEY=").run(application -> assertThat(application).hasFailed());
    }

    @Configuration(proxyBeanMethods = false)
    @EnableConfigurationProperties(AiProperties.class)
    @Import(AiGateway.class)
    static class Config {
        @Bean MeterRegistry metrics() { return new SimpleMeterRegistry(); }
        @Bean DocumentAccessService access() { return mock(DocumentAccessService.class); }
        @Bean AiDocumentContextService documentContext() { return mock(AiDocumentContextService.class); }
    }
}
