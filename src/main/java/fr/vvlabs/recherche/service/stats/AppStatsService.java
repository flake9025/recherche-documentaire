package fr.vvlabs.recherche.service.stats;

import fr.vvlabs.recherche.config.LuceneConfig;
import fr.vvlabs.recherche.dto.AppStatsDTO;
import fr.vvlabs.recherche.repository.DocumentRepository;
import fr.vvlabs.recherche.service.index.embeddings.store.BertEmbeddingsStoreFactory;
import fr.vvlabs.recherche.service.storage.StorageServiceFactory;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.stream.Stream;

@Service
@RequiredArgsConstructor
@Slf4j
public class AppStatsService {

    private final Environment environment;
    private final DocumentRepository documentRepository;
    private final LuceneConfig luceneConfig;
    private final BertEmbeddingsStoreFactory bertEmbeddingsStoreFactory;
    private final StorageServiceFactory storageServiceFactory;

    @Value("${app.indexer.default:lucene}")
    private String indexEngine;

    @Value("${app.search.default:${app.indexer.default:lucene}}")
    private String searchEngine;

    @Value("${app.embeddings.store.default:hashmap}")
    private String embeddingsStore;

    public AppStatsDTO getStats() {
        AppStatsDTO stats = new AppStatsDTO();
        stats.setActiveProfile(resolveActiveProfile());
        stats.setIndexEngine(indexEngine);
        stats.setSearchEngine(searchEngine);
        stats.setEmbeddingsStore(embeddingsStore);
        stats.setDatabaseDocumentCount(documentRepository.count());
        stats.setPendingOcrCount(documentRepository.findByOcrIndexDoneFalse().size());
        stats.setInMemoryEmbeddingsCount(resolveEmbeddingsCount());
        populateLuceneStats(stats);
        populateStorageStats(stats);
        return stats;
    }

    private String resolveActiveProfile() {
        String[] profiles = environment.getActiveProfiles();
        return profiles.length == 0 ? "default" : String.join(",", profiles);
    }

    private long resolveEmbeddingsCount() {
        try {
            return bertEmbeddingsStoreFactory.getDefaultStore().count();
        } catch (Exception e) {
            log.debug("Embeddings store count unavailable: {}", e.getMessage());
            return 0L;
        }
    }

    private void populateLuceneStats(AppStatsDTO stats) {
        try {
            Map<String, Object> luceneStats = luceneConfig.getIndexSizeStats();
            stats.setInMemoryIndexDocumentCount(asLong(luceneStats.get("numDocs")));
            stats.setInMemoryIndexSizeBytes(asLong(luceneStats.get("sizeInBytes")));
            stats.setInMemoryIndexSizeHuman(formatBytes(stats.getInMemoryIndexSizeBytes()));
        } catch (Exception e) {
            log.debug("Lucene in-memory index stats unavailable: {}", e.getMessage());
            stats.setInMemoryIndexDocumentCount(0L);
            stats.setInMemoryIndexSizeBytes(0L);
            stats.setInMemoryIndexSizeHuman("0 B");
        }
    }

    private void populateStorageStats(AppStatsDTO stats) {
        try {
            Path root = storageServiceFactory.getDefaultStorageService().getPath("");
            if (root == null || !Files.exists(root)) {
                stats.setStorageFileCount(0L);
                stats.setStorageSizeBytes(0L);
                stats.setStorageSizeHuman("0 B");
                return;
            }

            try (Stream<Path> paths = Files.walk(root)) {
                StorageAggregate aggregate = paths
                        .filter(Files::isRegularFile)
                        .map(this::toAggregate)
                        .reduce(new StorageAggregate(0L, 0L), StorageAggregate::merge);
                stats.setStorageFileCount(aggregate.fileCount());
                stats.setStorageSizeBytes(aggregate.sizeBytes());
                stats.setStorageSizeHuman(formatBytes(aggregate.sizeBytes()));
            }
        } catch (Exception e) {
            log.debug("Storage stats unavailable: {}", e.getMessage());
            stats.setStorageFileCount(0L);
            stats.setStorageSizeBytes(0L);
            stats.setStorageSizeHuman("0 B");
        }
    }

    private StorageAggregate toAggregate(Path path) {
        try {
            return new StorageAggregate(1L, Files.size(path));
        } catch (IOException e) {
            return new StorageAggregate(1L, 0L);
        }
    }

    private long asLong(Object value) {
        if (value instanceof Number number) {
            return number.longValue();
        }
        return 0L;
    }

    private String formatBytes(long bytes) {
        if (bytes < 1024L) {
            return bytes + " B";
        }
        double kb = bytes / 1024.0d;
        if (kb < 1024.0d) {
            return String.format("%.2f KB", kb);
        }
        double mb = kb / 1024.0d;
        if (mb < 1024.0d) {
            return String.format("%.2f MB", mb);
        }
        double gb = mb / 1024.0d;
        return String.format("%.2f GB", gb);
    }

    private record StorageAggregate(long fileCount, long sizeBytes) {
        private StorageAggregate merge(StorageAggregate other) {
            return new StorageAggregate(fileCount + other.fileCount, sizeBytes + other.sizeBytes);
        }
    }
}
