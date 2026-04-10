package fr.vvlabs.recherche.service.index.embeddings.store.hashmap;

import fr.vvlabs.recherche.service.index.embeddings.BertEmbeddingDocument;
import fr.vvlabs.recherche.service.index.embeddings.BertEmbeddingsStoreType;
import fr.vvlabs.recherche.service.index.embeddings.store.BertEmbeddingsStore;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Store en memoire base sur une {@link ConcurrentHashMap}.
 */
@Component
@ConditionalOnProperty(
        name = "app.embeddings.store.default",
        havingValue = BertEmbeddingsStoreType.HASHMAP,
        matchIfMissing = true
)
public class HashMapBertEmbeddingsStore implements BertEmbeddingsStore {

    private final Map<Long, BertEmbeddingDocument> documents = new ConcurrentHashMap<>();

    @Override
    public String getType() {
        return BertEmbeddingsStoreType.HASHMAP;
    }

    @Override
    public void upsert(BertEmbeddingDocument document) {
        if (document == null || document.documentId() == null) {
            return;
        }
        documents.put(document.documentId(), document);
    }

    @Override
    public List<BertEmbeddingDocument> findAll() {
        return new ArrayList<>(documents.values());
    }

    @Override
    public long count() {
        return documents.size();
    }

    @Override
    public void clear() {
        documents.clear();
    }

    @Override
    public void replaceAll(Collection<BertEmbeddingDocument> entities) {
        documents.clear();
        if (entities == null) {
            return;
        }
        for (BertEmbeddingDocument entity : entities) {
            upsert(entity);
        }
    }

    @Override
    public List<BertEmbeddingMatch> search(BertEmbeddingsStoreQuery query) {
        Comparator<BertEmbeddingMatch> comparator = Comparator.comparing(BertEmbeddingMatch::semanticScore).reversed();
        return documents.values().stream()
                .filter(document -> matchesFilters(document, query.category(), query.author(), query.dateFrom(), query.dateTo()))
                .map(document -> new BertEmbeddingMatch(document, cosineSimilarity(query.queryVector(), document.embedding())))
                .sorted(comparator)
                .limit(query.limit() > 0 ? query.limit() : Long.MAX_VALUE)
                .toList();
    }

    private boolean matchesFilters(
            BertEmbeddingDocument document,
            String category,
            String author,
            LocalDate dateFrom,
            LocalDate dateTo
    ) {
        return matchesTextFilter(document.category(), category)
                && matchesTextFilter(document.author(), author)
                && matchesDateRange(document.depotDateTime(), dateFrom, dateTo);
    }

    private boolean matchesTextFilter(String value, String filter) {
        if (filter == null || filter.isBlank()) {
            return true;
        }
        if (value == null) {
            return false;
        }
        return value.equalsIgnoreCase(filter.trim());
    }

    private boolean matchesDateRange(LocalDateTime value, LocalDate from, LocalDate to) {
        if (from == null && to == null) {
            return true;
        }
        if (value == null) {
            return false;
        }
        LocalDate date = value.toLocalDate();
        return (from == null || !date.isBefore(from)) && (to == null || !date.isAfter(to));
    }

    private float cosineSimilarity(float[] left, float[] right) {
        if (left == null || right == null || left.length == 0 || right.length == 0 || left.length != right.length) {
            return 0.0f;
        }
        double dotProduct = 0.0d;
        double leftNorm = 0.0d;
        double rightNorm = 0.0d;
        for (int i = 0; i < left.length; i++) {
            dotProduct += left[i] * right[i];
            leftNorm += left[i] * left[i];
            rightNorm += right[i] * right[i];
        }
        if (leftNorm == 0.0d || rightNorm == 0.0d) {
            return 0.0f;
        }
        return (float) (dotProduct / (Math.sqrt(leftNorm) * Math.sqrt(rightNorm)));
    }
}
