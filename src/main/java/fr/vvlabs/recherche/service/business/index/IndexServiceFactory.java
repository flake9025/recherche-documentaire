package fr.vvlabs.recherche.service.business.index;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;
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
    }

    public IndexService getDefaultIndexService() {
        return getIndexService(defaultIndex);
    }

    public IndexService getIndexService(String indexType) {
        IndexService service = services.get(indexType);
        if (service == null) {
            throw new IllegalStateException("Unknown Index: " + indexType);
        }
        return service;
    }
}
