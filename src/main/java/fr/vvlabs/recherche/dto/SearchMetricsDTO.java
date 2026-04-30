package fr.vvlabs.recherche.dto;

import lombok.Data;

@Data
public class SearchMetricsDTO {

    private long responseTimeMs;
    private long rebuildTimeMs;
    private long embeddingTimeMs;
    private String searchEngine;
    private String indexEngine;
    private String embeddingsStore;
    private String ocrEngine;
    private long containerMemoryUsedMb;
    private long containerMemoryLimitMb;
}
