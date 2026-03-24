package fr.vvlabs.recherche.config;

import fr.vvlabs.recherche.service.business.index.lucene.LuceneIndexService;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.lucene.store.ByteBuffersDirectory;
import org.springframework.context.annotation.Configuration;

import java.time.Duration;
import java.time.LocalTime;

@Configuration
@RequiredArgsConstructor
@Slf4j
public class ApplicationConfig {

    private final LuceneConfig luceneConfig;
    private final LuceneIndexService luceneIndexService;

    @PostConstruct
    public void reloadIndexAtStartup(){
        try {
            LocalTime t1 = LocalTime.now();

            ByteBuffersDirectory index = luceneIndexService.loadDocumentIndexFromDatabase();
            log.info("Default Index loaded from Database !");
            luceneConfig.setDocumentsIndex(index);
            log.info("Default Index loaded in Memory !");

            LocalTime t2 = LocalTime.now();
            Duration d = Duration.between(t1, t2);
            log.info("Millis Ã©coulÃ©s pour le chargement, dechiffrement, mise en memoire : {}" , d.toMillis());
        } catch (Exception e) {
            log.error("Load Default Index from Database error: {}", e.getMessage(), e);
        }
    }
}

