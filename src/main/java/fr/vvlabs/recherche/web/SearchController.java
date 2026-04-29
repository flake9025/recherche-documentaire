package fr.vvlabs.recherche.web;

import fr.vvlabs.recherche.dto.SearchRequestDTO;
import fr.vvlabs.recherche.dto.SearchResultDTO;
import fr.vvlabs.recherche.service.document.DocumentService;
import fr.vvlabs.recherche.service.index.IndexServiceFactory;
import fr.vvlabs.recherche.service.index.IndexType;
import fr.vvlabs.recherche.service.metrics.SearchMetricsRecorder;
import fr.vvlabs.recherche.service.search.SearchService;
import fr.vvlabs.recherche.service.search.SearchServiceFactory;
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

/**
 * Expose les operations de recherche documentaire.
 */
@RestController
@RequiredArgsConstructor
@RequestMapping("/api/search")
@Tag(name = "Recherche", description = "API de recherche documentaire")
@Slf4j
public class SearchController {

    private final DocumentService documentService;
    private final IndexServiceFactory indexServiceFactory;
    private final SearchServiceFactory searchServiceFactory;
    private final SearchMetricsRecorder searchMetricsRecorder;

    @Value("${app.search.wildcard}")
    private boolean wildcardEnabled;

    @Value("${app.search.distance.enabled}")
    private boolean searchDistanceEnabled;

    @Value("${app.search.distance.levenshtein}")
    private String searchDistanceLevenshtein;

    /**
     * Recherche des documents dans l'index actif.
     *
     * @param request criteres de recherche
     * @return resultat agrege
     * @throws Exception si la preparation ou la recherche echoue
     */
    @PostMapping("/")
    @Operation(summary = "Rechercher")
    public SearchResultDTO search(@RequestBody SearchRequestDTO request) throws Exception {
        LocalTime overallStartTime = LocalTime.now();
        SearchRequestDTO effectiveRequest = request == null ? new SearchRequestDTO() : request;
        SearchService searchService = searchServiceFactory.getDefaultSearchService();
        long rebuildTimeMs = 0L;
        boolean rebuildTriggered = false;

        String text = effectiveRequest.getQuery() == null ? "" : effectiveRequest.getQuery().trim();
        if (!IndexType.LUCENE.equals(searchService.getType())) {
            if (wildcardEnabled && text.length() > 3) {
                text += "*";
            }
            if (searchDistanceEnabled && text.length() > 3) {
                text += searchDistanceLevenshtein;
            }
        }
        effectiveRequest.setQuery(text);

        if (searchService.isSearchStoreEmpty()) {
            log.info("Search store is empty: building from documents metadata");
            LocalTime startTime = LocalTime.now();
            rebuildTriggered = true;

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

            Duration duration = Duration.between(startTime, LocalTime.now());
            rebuildTimeMs = duration.toMillis();
            log.info("Elapsed millis for search store rebuild: {}", duration.toMillis());
        }

        SearchResultDTO result = searchService.search(effectiveRequest);
        long responseTimeMs = Duration.between(overallStartTime, LocalTime.now()).toMillis();
        result.setMetrics(searchMetricsRecorder.snapshot(responseTimeMs, rebuildTimeMs));
        searchMetricsRecorder.recordSearch(
                responseTimeMs,
                rebuildTimeMs,
                result.getNbResults(),
                rebuildTriggered,
                resolveQueryKind(effectiveRequest),
                "success"
        );
        return result;
    }

    private String resolveQueryKind(SearchRequestDTO request) {
        String query = request.getQuery();
        if (query == null || query.isBlank()) {
            return "filters_only";
        }
        return "text";
    }
}
