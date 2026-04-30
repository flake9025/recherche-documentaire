package fr.vvlabs.recherche.service.metrics;

import fr.vvlabs.recherche.dto.SearchMetricsDTO;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.DistributionSummary;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Tags;
import io.micrometer.core.instrument.Timer;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

@Component
public class SearchMetricsRecorder {

    private final MeterRegistry meterRegistry;

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
        this.indexEngine = indexEngine;
        this.searchEngine = searchEngine;
        this.embeddingsStore = embeddingsStore;
        this.ocrEngine = ocrEngine;
        registerConfigurationGauge();
    }

    public void recordSearch(long responseTimeMs, long rebuildTimeMs, long embeddingTimeMs, int resultCount, boolean rebuildTriggered, String queryKind, String status) {
        Tags tags = commonTags().and("query_kind", queryKind, "status", status);

        Timer.builder("recherche.search.latency")
                .description("End-to-end search latency in milliseconds")
                .tags(tags)
                .register(meterRegistry)
                .record(responseTimeMs, TimeUnit.MILLISECONDS);

        if (embeddingTimeMs > 0) {
            Timer.builder("recherche.search.embedding.latency")
                    .description("BERT embedding generation latency in milliseconds")
                    .tags(tags)
                    .register(meterRegistry)
                    .record(embeddingTimeMs, TimeUnit.MILLISECONDS);
        }

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

    public SearchMetricsDTO snapshot(long responseTimeMs, long rebuildTimeMs, long embeddingTimeMs) {
        SearchMetricsDTO metrics = new SearchMetricsDTO();
        metrics.setResponseTimeMs(responseTimeMs);
        metrics.setRebuildTimeMs(rebuildTimeMs);
        metrics.setEmbeddingTimeMs(embeddingTimeMs);
        metrics.setSearchEngine(searchEngine);
        metrics.setIndexEngine(indexEngine);
        metrics.setEmbeddingsStore(embeddingsStore);
        metrics.setOcrEngine(ocrEngine);
        metrics.setContainerMemoryUsedMb(readCgroupMemoryMb(
                "/sys/fs/cgroup/memory.current",           // cgroups v2
                "/sys/fs/cgroup/memory/memory.usage_in_bytes" // cgroups v1
        ));
        metrics.setContainerMemoryLimitMb(readCgroupMemoryLimitMb());
        return metrics;
    }

    // ---------------------------------------------------------------------------
    // Lecture RAM conteneur via cgroups (v2 prioritaire, v1 en fallback)
    // ---------------------------------------------------------------------------

    private long readCgroupMemoryMb(String... paths) {
        for (String path : paths) {
            try {
                Path p = Path.of(path);
                if (Files.exists(p)) {
                    String raw = Files.readString(p).trim();
                    if (!raw.isBlank() && !"max".equals(raw)) {
                        return Long.parseLong(raw) / (1024L * 1024L);
                    }
                }
            } catch (Exception ignored) {
            }
        }
        return -1L;
    }

    private long readCgroupMemoryLimitMb() {
        // cgroups v2 : "max" = pas de limite
        try {
            Path p = Path.of("/sys/fs/cgroup/memory.max");
            if (Files.exists(p)) {
                String raw = Files.readString(p).trim();
                if ("max".equals(raw)) return -1L;
                return Long.parseLong(raw) / (1024L * 1024L);
            }
        } catch (Exception ignored) {
        }
        // cgroups v1 : valeur très grande = pas de limite
        try {
            Path p = Path.of("/sys/fs/cgroup/memory/memory.limit_in_bytes");
            if (Files.exists(p)) {
                long val = Long.parseLong(Files.readString(p).trim());
                if (val > Long.MAX_VALUE / 2) return -1L;
                return val / (1024L * 1024L);
            }
        } catch (Exception ignored) {
        }
        return -1L;
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
}
