package fr.vvlabs.recherche.service.index;

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
public class IndexServiceFactory {

    private final Map<String, IndexService> services;
    private final String defaultIndex;

    public IndexServiceFactory(
            List<IndexService> ocrServices,
            @Value("${app.indexer.default:lucene}") String defaultIndex
    ) {
        this.services = ocrServices.stream()
                .peek(service -> log.info("Indexer dÃ©tectÃ© : {}", service.getType()))
                .collect(Collectors.toMap(
                        IndexService::getType,
                        Function.identity()
                ));
        this.defaultIndex = defaultIndex;
        validateDefaultService();
    }

    public IndexService getDefaultIndexService() {
        return getIndexService(defaultIndex);
    }

    public IndexService getIndexService(String indexType) {
        IndexService service = services.get(indexType);
        if (service == null) {
            throw new IllegalStateException(buildUnknownServiceMessage("index", indexType));
        }
        return service;
    }

    private void validateDefaultService() {
        if (!services.containsKey(defaultIndex)) {
            throw new IllegalStateException(buildUnknownServiceMessage("default index", defaultIndex));
        }
    }

    private String buildUnknownServiceMessage(String label, String requestedType) {
        Set<String> availableTypes = services.keySet();
        return "Unknown " + label + ": " + requestedType + ". Available types: " + availableTypes
                + ". Check app.indexer.default and bean conditional configuration.";
    }
}
