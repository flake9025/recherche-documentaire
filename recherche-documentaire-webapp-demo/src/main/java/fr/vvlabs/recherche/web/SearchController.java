package fr.vvlabs.recherche.web;

import fr.vvlabs.recherche.dto.SearchRequestDTO;
import fr.vvlabs.recherche.dto.SearchResultDTO;
import fr.vvlabs.recherche.service.index.IndexType;
import fr.vvlabs.recherche.service.metrics.SearchMetricsRecorder;
import fr.vvlabs.recherche.service.search.SearchService;
import fr.vvlabs.recherche.service.search.SearchServiceFactory;
import fr.vvlabs.recherche.service.search.SearchStoreInitializer;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

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

    private final SearchStoreInitializer searchStoreInitializer;
    private final SearchServiceFactory searchServiceFactory;
    private final SearchMetricsRecorder searchMetricsRecorder;
    private final fr.vvlabs.recherche.service.document.DocumentAccessService access;
    private final fr.vvlabs.recherche.service.ai.AiGateway aiGateway;

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
        effectiveRequest.setAllowedDocumentIds(access.visibleDocumentIds());
        SearchService searchService = searchServiceFactory.getDefaultSearchService();

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

        long rebuildTimeMs = effectiveRequest.getAllowedDocumentIds().isEmpty()
                ? 0L : searchStoreInitializer.rebuildIfEmpty(searchService);
        boolean rebuildTriggered = rebuildTimeMs > 0;

        SearchResultDTO result = effectiveRequest.getAllowedDocumentIds().isEmpty()
                ? new SearchResultDTO() : searchService.search(effectiveRequest);
        // Seconde barriere : meme un backend obsolet ne doit pas exposer un resultat hors perimetre.
        var visibleNow = access.visibleDocumentIds();
        result.setFragments(result.getFragments().stream()
                .filter(fragment -> visibleNow.contains(Long.valueOf(fragment.getId()))).toList());
        result.setNbResults(result.getFragments().size());
        if (effectiveRequest.isSummarize()) {
            try {
                var summary = aiGateway.summarize(effectiveRequest.getAiModel(), effectiveRequest.getQuery(), result.getFragments());
                result.setSummary(new SearchResultDTO.SummaryDTO(summary.text(), summary.model(), summary.sources().stream()
                        .map(source -> new SearchResultDTO.SummarySourceDTO(source.number(), source.documentId(),
                                source.title(), source.fileUrl())).toList()));
            } catch (org.springframework.web.server.ResponseStatusException exception) {
                log.warn("AI synthesis failed status={}", exception.getStatusCode().value());
                result.setSummaryError(exception.getReason());
            }
        }
        long responseTimeMs = Duration.between(overallStartTime, LocalTime.now()).toMillis();
        result.setMetrics(searchMetricsRecorder.snapshot(responseTimeMs, rebuildTimeMs, result.getEmbeddingTimeMs()));
        searchMetricsRecorder.recordSearch(
                responseTimeMs,
                rebuildTimeMs,
                result.getEmbeddingTimeMs(),
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
