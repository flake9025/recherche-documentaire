package fr.vvlabs.recherche.service.search;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;

@Component
@Slf4j
public class SearchServiceFactory {

    private final Map<String, SearchService> services;
    private final String defaultSearch;

    public SearchServiceFactory(
            List<SearchService> services,
            @Value("${app.search.default:${app.indexer.default:lucene}}") String defaultSearch
    ) {
        this.services = services.stream()
                .peek(service -> log.info("Search service detected: {}", service.getType()))
                .collect(Collectors.toMap(SearchService::getType, Function.identity()));
        this.defaultSearch = defaultSearch;
        validateDefaultService();
    }

    public SearchService getDefaultSearchService() {
        return getSearchService(defaultSearch);
    }

    public SearchService getSearchService(String type) {
        SearchService service = services.get(type);
        if (service == null) {
            throw new IllegalStateException(buildUnknownServiceMessage("search service", type));
        }
        return service;
    }

    private void validateDefaultService() {
        if (!services.containsKey(defaultSearch)) {
            throw new IllegalStateException(buildUnknownServiceMessage("default search service", defaultSearch));
        }
    }

    private String buildUnknownServiceMessage(String label, String requestedType) {
        Set<String> availableTypes = services.keySet();
        return "Unknown " + label + ": " + requestedType + ". Available types: " + availableTypes
                + ". Check app.search.default and bean conditional configuration.";
    }
}
