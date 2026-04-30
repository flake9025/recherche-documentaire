package fr.vvlabs.recherche.service.search;

import fr.vvlabs.recherche.service.document.DocumentService;
import fr.vvlabs.recherche.service.index.IndexServiceFactory;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.io.IOException;
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
    public long rebuildIfEmpty(SearchService searchService) throws Exception {
        if (!searchService.isSearchStoreEmpty()) {
            return 0L;
        }

        log.info("Search store is empty: building from documents metadata");
        LocalTime startTime = LocalTime.now();

        documentService.findAll().forEach(documentDTO -> {
            String documentFileText = "";
            try {
                documentFileText = documentService.getFileText(documentDTO);
            } catch (IOException e) {
                log.error("getFileText error : {}", e.getMessage(), e);
            }
            try {
                indexServiceFactory.getDefaultIndexService().addDocumentToDocumentIndex(documentDTO, documentFileText);
            } catch (Exception e) {
                log.error("addToIndex error : {}", e.getMessage(), e);
            }
        });

        long elapsed = Duration.between(startTime, LocalTime.now()).toMillis();
        log.info("Elapsed millis for search store rebuild: {}", elapsed);
        return elapsed;
    }
}
