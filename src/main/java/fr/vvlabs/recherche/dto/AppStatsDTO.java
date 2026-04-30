package fr.vvlabs.recherche.dto;

import lombok.Data;

@Data
public class AppStatsDTO {

    private String activeProfile;
    private String indexEngine;
    private String searchEngine;
    private String embeddingsStore;
    private String embeddingsModelId;
    private boolean embeddingsModelLoaded;

    private long databaseDocumentCount;
    private long pendingOcrCount;
    private long inMemoryIndexDocumentCount;
    private long inMemoryEmbeddingsCount;

    private long storageFileCount;
    private long storageSizeBytes;
    private String storageSizeHuman;

    private long inMemoryIndexSizeBytes;
    private String inMemoryIndexSizeHuman;
}
