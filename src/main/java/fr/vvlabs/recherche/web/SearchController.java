package fr.vvlabs.recherche.web;

import fr.vvlabs.recherche.dto.SearchRequestDTO;
import fr.vvlabs.recherche.dto.SearchResultDTO;
import fr.vvlabs.recherche.service.business.document.DocumentService;
import fr.vvlabs.recherche.service.business.index.IndexServiceFactory;
import fr.vvlabs.recherche.service.business.index.IndexType;
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

@RestController
@RequiredArgsConstructor
@RequestMapping("/api/search")
@Tag(name = "Recherche", description = "API de recherche documentaire")
@Slf4j
public class SearchController {

    private final DocumentService documentService;
    private final IndexServiceFactory indexServiceFactory;
    private final SearchServiceFactory searchServiceFactory;

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
        SearchService searchService = searchServiceFactory.getDefaultSearchService();

        prepareQuery(effectiveRequest, searchService);

        if (searchService.isSearchStoreEmpty()) {
            buildIndexFromDocumentsMetadata();
        }

        return searchService.search(effectiveRequest);
    }

    private void buildIndexFromDocumentsMetadata() throws IOException {
        log.info("Search store is empty: building from documents metadata");
        LocalTime t1 = LocalTime.now();

        documentService.findAll().forEach(documentDTO -> {
            String documentFileText = "";
            try {
                documentFileText = documentService.getFileText(documentDTO);
            } catch (IOException e) {
                log.error("getFileText error : {}", e.getMessage(), e);
            }

            try {
                indexServiceFactory.getDefaultIndexService().addDocumentToDocumentIndex(documentDTO, documentFileText);
            } catch (IOException e) {
                log.error("addToIndex error : {}", e.getMessage(), e);
            }
        });

        Duration duration = Duration.between(t1, LocalTime.now());
        log.info("Elapsed millis for search store rebuild: {}", duration.toMillis());
    }

    private void prepareQuery(SearchRequestDTO request, SearchService searchService) {
        String text = request.getQuery() == null ? "" : request.getQuery().trim();
        if (!IndexType.LUCENE.equals(searchService.getType())) {
            request.setQuery(text);

            if (wildcardEnabled && text.length() > 3) {
                text += "*";
            }
            if (searchDistanceEnabled && text.length() > 3) {
                text += searchDistanceLevenshtein;
            }
            return;
        }
        request.setQuery(text);
    }
}
