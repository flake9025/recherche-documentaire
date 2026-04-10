package fr.vvlabs.recherche.dto;

import lombok.Data;

@Data
public class SearchMetricsDTO {

    private long responseTimeMs;
    private long rebuildTimeMs;
    private String searchEngine;
    private String indexEngine;
    private String embeddingsStore;
    private String ocrEngine;
    private double systemCpuUsagePct;
    private double processCpuUsagePct;
    private long heapUsedMb;
    private long heapMaxMb;
    private long nonHeapUsedMb;
    private long systemMemoryUsedMb;
    private long systemMemoryTotalMb;
}
