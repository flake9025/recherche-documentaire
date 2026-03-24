package fr.vvlabs.recherche.web;

import fr.vvlabs.recherche.config.LuceneConfig;
import fr.vvlabs.recherche.dto.SearchRequestDTO;
import fr.vvlabs.recherche.dto.SearchResultDTO;
import fr.vvlabs.recherche.service.business.document.DocumentService;
import fr.vvlabs.recherche.service.business.index.lucene.LuceneIndexService;
import fr.vvlabs.recherche.service.search.lucene.LuceneSearchService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.io.IOException;
import java.time.Duration;
import java.time.LocalTime;

@RestController
@RequiredArgsConstructor
@RequestMapping("/api/search")
@Tag(name = "Recherche", description = "API de recherche documentaire")
@Slf4j
public class SearchController {

    private final LuceneConfig luceneConfig;
    private final LuceneIndexService luceneIndexService;
    private final DocumentService documentService;
    private final LuceneSearchService luceneSearchService;

    @Value("${app.search.wildcard}")
    private boolean wildcardEnabled;
    @Value("${app.search.distance.enabled}")
    private boolean searchDistanceEnabled;
    @Value("${app.search.distance.levenshtein}")
    private String searchDistanceLevenshtein;

    @PostMapping("/")
    @Operation(summary = "Rechercher")
    public SearchResultDTO search(@RequestBody SearchRequestDTO request) throws Exception {
        SearchRequestDTO effectiveRequest = request == null ? new SearchRequestDTO() : request;
        String text = effectiveRequest.getQuery() == null ? "" : effectiveRequest.getQuery().trim();

        if (wildcardEnabled && text.length() > 3) {
            text += "*";
        }
        if (searchDistanceEnabled && text.length() > 3) {
            text += searchDistanceLevenshtein;
        }
        effectiveRequest.setQuery(text);

        if (luceneConfig.isIndexEmpty()) {
            buildIndexFromDocumentsMetadata();
        }

        return luceneSearchService.search(effectiveRequest);
    }

    private void buildIndexFromDocumentsMetadata() throws IOException {
        log.info("Index is empty : Building index from documents metadata");
        LocalTime t1 = LocalTime.now();
        // Etape 1 : Recherche des documents en BDD
        documentService.findAll().forEach(documentDTO -> {
            // Etape 2 : Contenu
            String documentFileText = "";
            try {
                documentFileText = documentService.getFileText(documentDTO);
            } catch (IOException e) {
                log.error("getFileText error : {}", e.getMessage(), e);
                documentFileText = "";
            }
            // Etape 3 : Indexation des metadatas et contenu
            try {
                luceneIndexService.addDocumentToDocumentIndex(documentDTO, documentFileText);
            } catch (IOException e) {
                log.error("addToIndex error : {}", e.getMessage(), e);
            }
        });
        LocalTime t2 = LocalTime.now();
        Duration d = Duration.between(t1, t2);
        log.info("Millis Ã©coulÃ©s pour l'indexation : {}" , d.toMillis());
        log.info("Lucene index stats: {}", luceneConfig.getIndexSizeStats());
    }
}

