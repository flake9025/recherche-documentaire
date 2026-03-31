package fr.vvlabs.recherche.service.search;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

@Component
@Slf4j
public class SearchServiceFactory {

    private final Map<String, SearchService> services;
    private final String defaultSearch;

    public SearchServiceFactory(
            List<SearchService> services,
            @Value("${app.indexer.default:lucene}") String defaultSearch
    ) {
        this.services = services.stream()
                .peek(service -> log.info("Search service detected: {}", service.getType()))
                .collect(Collectors.toMap(SearchService::getType, Function.identity()));
        this.defaultSearch = defaultSearch;
    }

    public SearchService getDefaultSearchService() {
        return getSearchService(defaultSearch);
    }

    public SearchService getSearchService(String type) {
        SearchService service = services.get(type);
        if (service == null) {
            throw new IllegalStateException("Unknown search service: " + type);
        }
        return service;
    }
}
