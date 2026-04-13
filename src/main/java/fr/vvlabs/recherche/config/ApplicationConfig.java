package fr.vvlabs.recherche.config;

import fr.vvlabs.recherche.service.index.IndexType;
import fr.vvlabs.recherche.service.index.embeddings.BertEmbeddingsIndexService;
import fr.vvlabs.recherche.service.index.lucene.LuceneIndexService;
import fr.vvlabs.recherche.service.index.lucene.LuceneVectorIndexService;
import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.apache.lucene.store.ByteBuffersDirectory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Configuration;

import java.time.Duration;
import java.time.LocalTime;
import java.util.Optional;

@Configuration
@Slf4j
public class ApplicationConfig {

    private final LuceneConfig luceneConfig;
    private final Optional<LuceneIndexService> luceneIndexService;
    private final Optional<LuceneVectorIndexService> luceneVectorIndexService;
    private final Optional<BertEmbeddingsIndexService> bertEmbeddingsIndexService;

    @Autowired
    public ApplicationConfig(
            LuceneConfig luceneConfig,
            Optional<LuceneIndexService> luceneIndexService,
            Optional<LuceneVectorIndexService> luceneVectorIndexService,
            Optional<BertEmbeddingsIndexService> bertEmbeddingsIndexService
    ) {
        this.luceneConfig = luceneConfig;
        this.luceneIndexService = luceneIndexService;
        this.luceneVectorIndexService = luceneVectorIndexService;
        this.bertEmbeddingsIndexService = bertEmbeddingsIndexService;
    }

    @Value("${app.indexer.default:lucene}")
    private String defaultIndexType;

    @PostConstruct
    public void reloadIndexAtStartup() {
        try {
            LocalTime t1 = LocalTime.now();
            switch (defaultIndexType) {
                case IndexType.LUCENE:
                    luceneConfig.setDocumentsIndex(luceneIndexService.orElseThrow(
                                    () -> new IllegalStateException("LuceneIndexService bean is not available for app.indexer.default=lucene")
                            )
                            .loadDocumentIndexFromDatabase());
                    break;
                case IndexType.LUCENE_VECTOR:
                    luceneConfig.setDocumentsIndex(luceneVectorIndexService.orElseThrow(
                                    () -> new IllegalStateException("LuceneVectorIndexService bean is not available for app.indexer.default=lucene-vector")
                            )
                            .loadDocumentIndexFromDatabase());
                    break;
                case IndexType.BERT:
                    bertEmbeddingsIndexService.orElseThrow(
                                    () -> new IllegalStateException("BertEmbeddingsIndexService bean is not available for app.indexer.default=bert")
                            )
                            .loadDocumentIndexFromDatabase();
                    break;
                default:
                    log.info("Index warmup skipped because default indexer is {}", defaultIndexType);
                    return;

            }
            Duration duration = Duration.between(t1, LocalTime.now());
            log.info("Index {} loaded in memory in {} ms", defaultIndexType, duration.toMillis());
        } catch (Exception e) {
            log.error("Load default index {} error: {}", defaultIndexType, e.getMessage(), e);
        }
    }
}
