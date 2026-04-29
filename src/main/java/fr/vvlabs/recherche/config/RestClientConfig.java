package fr.vvlabs.recherche.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.converter.json.MappingJackson2HttpMessageConverter;
import org.springframework.web.client.RestClient;

@Configuration
public class RestClientConfig {

    /**
     * Configure le RestClient.Builder avec l'ObjectMapper Spring Boot
     * (JavaTimeModule inclus) pour eviter que Jackson echoue silencieusement
     * sur LocalDateTime / LocalDate dans les DTOs FAISS distants.
     * Spring Boot 4 ne fournit plus RestClient.Builder comme @Bean prototype,
     * donc @ConditionalOnMissingBean laisse ce bean actif.
     */
    @Bean
    @ConditionalOnMissingBean(RestClient.Builder.class)
    RestClient.Builder restClientBuilder(ObjectMapper objectMapper) {
        return RestClient.builder()
                .messageConverters(converters -> {
                    converters.removeIf(c -> c instanceof MappingJackson2HttpMessageConverter);
                    converters.add(0, new MappingJackson2HttpMessageConverter(objectMapper));
                });
    }
}
