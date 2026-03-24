package fr.vvlabs.recherche.config;

import jakarta.annotation.PreDestroy;
import lombok.Getter;
import lombok.Setter;
import lombok.extern.slf4j.Slf4j;
import org.apache.lucene.analysis.standard.StandardAnalyzer;
import org.apache.lucene.index.DirectoryReader;
import org.apache.lucene.search.suggest.analyzing.AnalyzingInfixSuggester;
import org.apache.lucene.store.ByteBuffersDirectory;
import org.apache.lucene.store.Directory;
import org.apache.lucene.store.FSDirectory;
import org.springframework.context.annotation.Configuration;

import java.io.IOException;
import java.nio.file.Paths;
import java.util.HashMap;
import java.util.Map;

@Configuration
@Getter
@Setter
@Slf4j
public class LuceneConfig {

    public static final String DEFAULT_INDEX = "default_index";
    private static final String SUGGEST_INDEX_PATH = "./lucene-suggest";

    // documents (search)
    private StandardAnalyzer documentsAnalyzer;
    private ByteBuffersDirectory documentsIndex;
    private final Object documentsIndexLock = new Object();
    // authors (autocomplete)
    private StandardAnalyzer authorsAnalyzer;
    private AnalyzingInfixSuggester authorsSuggester;
    private Directory authorsSuggestDirectory;

    public LuceneConfig() {
        try {
            documentsAnalyzer = new StandardAnalyzer();
            documentsIndex = new ByteBuffersDirectory();

            authorsAnalyzer = new StandardAnalyzer();
            authorsSuggestDirectory = FSDirectory.open(Paths.get(SUGGEST_INDEX_PATH));
            authorsSuggester = new AnalyzingInfixSuggester(authorsSuggestDirectory, authorsAnalyzer, authorsAnalyzer, 2, false);
        } catch (IOException e) {
            log.error("Lucene Config KO : {}", e.getMessage(), e);
        }

    }

    public boolean isIndexEmpty() throws IOException {
        if (!DirectoryReader.indexExists(documentsIndex)) {
            return true;
        }
        try (DirectoryReader reader = DirectoryReader.open(documentsIndex)) {
            return reader.numDocs() == 0;
        }
    }

    private long computeIndexSizeInBytes() throws IOException {
        long size = 0L;
        for (String fileName : documentsIndex.listAll()) {
            size += documentsIndex.fileLength(fileName);
        }
        return size;
    }

    public Map<String, Object> getIndexSizeStats() throws IOException {
        Map<String, Object> stats = new HashMap<>();

        long totalSizeInBytes = computeIndexSizeInBytes();

        double sizeInKB = totalSizeInBytes / 1024.0;
        double sizeInMB = sizeInKB / 1024.0;
        double sizeInGB = sizeInMB / 1024.0;

        stats.put("sizeInBytes", totalSizeInBytes);
        stats.put("sizeInKB", String.format("%.2f KB", sizeInKB));
        stats.put("sizeInMB", String.format("%.2f MB", sizeInMB));
        stats.put("sizeInGB", String.format("%.3f GB", sizeInGB));

        if (DirectoryReader.indexExists(documentsIndex)) {
            try (DirectoryReader reader = DirectoryReader.open(documentsIndex)) {
                stats.put("numDocs", reader.numDocs());
                stats.put("maxDoc", reader.maxDoc());
                stats.put("numDeletedDocs", reader.numDeletedDocs());
            }
        } else {
            stats.put("numDocs", 0);
            stats.put("maxDoc", 0);
            stats.put("numDeletedDocs", 0);
        }

        return stats;
    }

    @PreDestroy
    public void cleanup() throws IOException {
        log.info("Closing Author Autocomplete Service");
        if (authorsSuggester != null) {
            authorsSuggester.close();
        }
        if (authorsSuggestDirectory != null) {
            authorsSuggestDirectory.close();
        }
        if (authorsAnalyzer != null) {
            authorsAnalyzer.close();
        }
    }
}

