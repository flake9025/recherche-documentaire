package fr.vvlabs.recherche.service.metrics;

import com.sun.management.OperatingSystemMXBean;
import fr.vvlabs.recherche.dto.SearchMetricsDTO;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.DistributionSummary;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Tags;
import io.micrometer.core.instrument.Timer;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.lang.management.ManagementFactory;
import java.lang.management.MemoryMXBean;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

@Component
public class SearchMetricsRecorder {

    private final MeterRegistry meterRegistry;
    private final MemoryMXBean memoryMxBean;
    private final OperatingSystemMXBean operatingSystemMxBean;

    private final String indexEngine;
    private final String searchEngine;
    private final String embeddingsStore;
    private final String ocrEngine;

    public SearchMetricsRecorder(
            MeterRegistry meterRegistry,
            @Value("${app.indexer.default:lucene}") String indexEngine,
            @Value("${app.search.default:${app.indexer.default:lucene}}") String searchEngine,
            @Value("${app.embeddings.store.default:hashmap}") String embeddingsStore,
            @Value("${app.parser.ocr.default:pdfbox}") String ocrEngine
    ) {
        this.meterRegistry = meterRegistry;
        this.memoryMxBean = ManagementFactory.getMemoryMXBean();
        this.operatingSystemMxBean = ManagementFactory.getPlatformMXBean(OperatingSystemMXBean.class);
        this.indexEngine = indexEngine;
        this.searchEngine = searchEngine;
        this.embeddingsStore = embeddingsStore;
        this.ocrEngine = ocrEngine;
        registerConfigurationGauge();
    }

    public void recordSearch(long responseTimeMs, long rebuildTimeMs, int resultCount, boolean rebuildTriggered, String queryKind, String status) {
        Tags tags = commonTags().and("query_kind", queryKind, "status", status);

        Timer.builder("recherche.search.latency")
                .description("End-to-end search latency in milliseconds")
                .tags(tags)
                .register(meterRegistry)
                .record(responseTimeMs, TimeUnit.MILLISECONDS);

        DistributionSummary.builder("recherche.search.results")
                .description("Number of results returned by searches")
                .baseUnit("results")
                .tags(tags)
                .register(meterRegistry)
                .record(resultCount);

        Counter.builder("recherche.search.requests")
                .description("Number of search requests")
                .tags(tags)
                .register(meterRegistry)
                .increment();

        if (rebuildTriggered) {
            Timer.builder("recherche.search.rebuild.latency")
                    .description("Latency of automatic search store rebuild before search")
                    .tags(commonTags().and("status", status))
                    .register(meterRegistry)
                    .record(rebuildTimeMs, TimeUnit.MILLISECONDS);

            Counter.builder("recherche.search.rebuild.requests")
                    .description("Number of automatic search store rebuilds")
                    .tags(commonTags().and("status", status))
                    .register(meterRegistry)
                    .increment();
        }
    }

    public SearchMetricsDTO snapshot(long responseTimeMs, long rebuildTimeMs) {
        SearchMetricsDTO metrics = new SearchMetricsDTO();
        metrics.setResponseTimeMs(responseTimeMs);
        metrics.setRebuildTimeMs(rebuildTimeMs);
        metrics.setSearchEngine(searchEngine);
        metrics.setIndexEngine(indexEngine);
        metrics.setEmbeddingsStore(embeddingsStore);
        metrics.setOcrEngine(ocrEngine);
        metrics.setSystemCpuUsagePct(toPercent(operatingSystemMxBean == null ? -1.0d : operatingSystemMxBean.getCpuLoad()));
        metrics.setProcessCpuUsagePct(toPercent(operatingSystemMxBean == null ? -1.0d : operatingSystemMxBean.getProcessCpuLoad()));
        metrics.setHeapUsedMb(toMb(memoryMxBean.getHeapMemoryUsage().getUsed()));
        metrics.setHeapMaxMb(toMb(memoryMxBean.getHeapMemoryUsage().getMax()));
        metrics.setNonHeapUsedMb(toMb(memoryMxBean.getNonHeapMemoryUsage().getUsed()));
        if (operatingSystemMxBean != null) {
            long totalMemory = operatingSystemMxBean.getTotalMemorySize();
            long freeMemory = operatingSystemMxBean.getFreeMemorySize();
            metrics.setSystemMemoryTotalMb(toMb(totalMemory));
            metrics.setSystemMemoryUsedMb(toMb(Math.max(totalMemory - freeMemory, 0L)));
        }
        return metrics;
    }

    private Tags commonTags() {
        return Tags.of(
                "index_engine", indexEngine,
                "search_engine", searchEngine,
                "embeddings_store", embeddingsStore,
                "ocr_engine", ocrEngine
        );
    }

    private void registerConfigurationGauge() {
        AtomicInteger active = new AtomicInteger(1);
        Gauge.builder("recherche.configuration.active", active, AtomicInteger::get)
                .description("Active search/index benchmark configuration")
                .tags(commonTags())
                .register(meterRegistry);
    }

    private double toPercent(double ratio) {
        if (ratio < 0.0d) {
            return -1.0d;
        }
        return Math.round(ratio * 10_000.0d) / 100.0d;
    }

    private long toMb(long bytes) {
        if (bytes < 0L) {
            return -1L;
        }
        return bytes / (1024L * 1024L);
    }
}
