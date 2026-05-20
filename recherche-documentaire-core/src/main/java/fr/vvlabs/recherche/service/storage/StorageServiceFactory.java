package fr.vvlabs.recherche.service.storage;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

@Component
@Slf4j
public class StorageServiceFactory {

    private final Map<String, StorageService> services;
    private final String defaultStorage;

    public StorageServiceFactory(
            List<StorageService> ocrServices,
            @Value("${app.storage.default:fs}") String defaultStorage
    ) {
        this.services = ocrServices.stream()
                .peek(service -> log.info("Storage détecté : {}", service.getType()))
                .collect(Collectors.toMap(
                        StorageService::getType,
                        Function.identity()
                ));
        this.defaultStorage = defaultStorage;
    }

    public StorageService getDefaultStorageService() {
        return getStorageService(defaultStorage);
    }

    public StorageService getStorageService(String storageType) {
        StorageService service = services.get(storageType);
        if (service == null) {
            throw new IllegalStateException("Unknown Storage: " + storageType);
        }
        return service;
    }
}
