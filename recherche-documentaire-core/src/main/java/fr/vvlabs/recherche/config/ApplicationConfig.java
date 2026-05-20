package fr.vvlabs.recherche.config;

import fr.vvlabs.recherche.service.index.IndexServiceFactory;
import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.apache.lucene.store.ByteBuffersDirectory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Configuration;

import java.time.Duration;
import java.time.LocalTime;

@Configuration
@Slf4j
public class ApplicationConfig {

    private final LuceneConfig luceneConfig;
    private final IndexServiceFactory indexServiceFactory;

    public ApplicationConfig(
            LuceneConfig luceneConfig,
            IndexServiceFactory indexServiceFactory
    ) {
        this.luceneConfig = luceneConfig;
        this.indexServiceFactory = indexServiceFactory;
    }

    @Value("${app.indexer.default:lucene}")
    private String defaultIndexType;

    @PostConstruct
    public void reloadIndexAtStartup() {
        try {
            LocalTime t1 = LocalTime.now();
            Object loadedStore = indexServiceFactory.getDefaultIndexService().loadDocumentIndexFromDatabase();
            if (loadedStore instanceof ByteBuffersDirectory directory) {
                luceneConfig.setDocumentsIndex(directory);
            }
            Duration duration = Duration.between(t1, LocalTime.now());
            log.info("Index {} loaded in memory in {} ms", defaultIndexType, duration.toMillis());
        } catch (Exception e) {
            log.error("Load default index {} error: {}", defaultIndexType, e.getMessage(), e);
        }
    }
}
