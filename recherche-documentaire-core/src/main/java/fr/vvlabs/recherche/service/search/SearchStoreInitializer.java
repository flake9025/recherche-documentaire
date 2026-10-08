package fr.vvlabs.recherche.service.search;

import fr.vvlabs.recherche.service.document.DocumentService;
import fr.vvlabs.recherche.service.index.IndexServiceFactory;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.LocalTime;

/**
 * Reconstruit le store de recherche à partir de la base de documents quand il est vide.
 * Isole cette logique d'orchestration hors du controller et hors des SearchService.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class SearchStoreInitializer {

    private final DocumentService documentService;
    private final IndexServiceFactory indexServiceFactory;

    /**
     * Si le store est vide, le reconstruit et retourne le temps écoulé en ms.
     * Retourne 0 si aucun rebuild n'était nécessaire.
     */
    public synchronized long rebuildIfEmpty(SearchService searchService) throws Exception {
        if (!searchService.isSearchStoreEmpty()) {
            return 0L;
        }

        log.info("Search store is empty: building from documents metadata");
        LocalTime startTime = LocalTime.now();

        indexAllDocuments();

        long elapsed = Duration.between(startTime, LocalTime.now()).toMillis();
        log.info("Elapsed millis for search store rebuild: {}", elapsed);
        return elapsed;
    }

    public synchronized long rebuildAll() throws Exception {
        long start = System.nanoTime();
        indexAllDocuments();
        indexServiceFactory.getDefaultIndexService().saveDocumentIndexToDatabase();
        return (System.nanoTime() - start) / 1_000_000L;
    }

    private void indexAllDocuments() throws Exception {
        for (var document : documentService.findAll()) {
            String text = documentService.getFileText(document);
            indexServiceFactory.getDefaultIndexService().addDocumentToDocumentIndex(document, text);
        }
    }
}
