package fr.vvlabs.recherche.service.index.embeddings.store.qdrant;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import fr.vvlabs.recherche.service.index.embeddings.BertEmbeddingDocument;
import fr.vvlabs.recherche.service.index.embeddings.BertEmbeddingsStoreType;
import fr.vvlabs.recherche.service.index.embeddings.store.BertEmbeddingsStore;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.RestClient;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Locale;

/**
 * Store BERT deleguant l'indexation et la recherche a un serveur Qdrant.
 */
@Component
@ConditionalOnProperty(name = "app.embeddings.store.default", havingValue = BertEmbeddingsStoreType.QDRANT)
public class QdrantBertEmbeddingsStore implements BertEmbeddingsStore {

    private static final int DEFAULT_SCROLL_LIMIT = 256;

    private final RestClient restClient;
    private final boolean enabled;
    private final String collectionName;
    private final int batchSize;

    public QdrantBertEmbeddingsStore(
            RestClient.Builder restClientBuilder,
            @Value("${app.embeddings.store.qdrant.base-url:http://localhost:6333}") String baseUrl,
            @Value("${app.embeddings.store.qdrant.api-key:}") String apiKey,
            @Value("${app.embeddings.store.qdrant.collection:document-embeddings}") String collectionName,
            @Value("${app.embeddings.store.qdrant.enabled:false}") boolean enabled,
            @Value("${app.embeddings.store.qdrant.batch-size:128}") int batchSize
    ) {
        RestClient.Builder builder = restClientBuilder.baseUrl(baseUrl);
        if (apiKey != null && !apiKey.isBlank()) {
            builder = builder.defaultHeader("api-key", apiKey.trim());
        }
        this.restClient = builder.build();
        this.enabled = enabled;
        this.collectionName = collectionName;
        this.batchSize = Math.max(batchSize, 1);
    }

    @Override
    public String getType() {
        return BertEmbeddingsStoreType.QDRANT;
    }

    @Override
    public void upsert(BertEmbeddingDocument document) {
        requireEnabled();
        if (document == null || document.documentId() == null) {
            return;
        }
        ensureCollection(validateEmbedding(document.embedding()));
        upsertBatch(List.of(document));
    }

    @Override
    public List<BertEmbeddingDocument> findAll() {
        requireEnabled();
        if (!collectionExists()) {
            return List.of();
        }

        List<BertEmbeddingDocument> documents = new ArrayList<>();
        Object offset = null;
        do {
            QdrantScrollResponse response = restClient.post()
                    .uri("/collections/{collection}/points/scroll", collectionName)
                    .body(new QdrantScrollRequest(DEFAULT_SCROLL_LIMIT, offset, true, true))
                    .retrieve()
                    .body(QdrantScrollResponse.class);

            if (response == null || response.result() == null || response.result().points() == null) {
                break;
            }

            response.result().points().stream()
                    .map(this::toDocument)
                    .forEach(documents::add);
            offset = response.result().nextPageOffset();
        } while (offset != null);
        return documents;
    }

    @Override
    public long count() {
        requireEnabled();
        if (!collectionExists()) {
            return 0L;
        }
        QdrantCountResponse response = restClient.post()
                .uri("/collections/{collection}/points/count", collectionName)
                .body(new QdrantCountRequest(true))
                .retrieve()
                .body(QdrantCountResponse.class);
        return response == null || response.result() == null ? 0L : response.result().count();
    }

    @Override
    public void clear() {
        requireEnabled();
        try {
            restClient.delete()
                    .uri("/collections/{collection}", collectionName)
                    .retrieve()
                    .toBodilessEntity();
        } catch (HttpClientErrorException.NotFound ignored) {
            // Collection deja absente: rien a faire.
        }
    }

    @Override
    public void replaceAll(Collection<BertEmbeddingDocument> entities) {
        requireEnabled();
        clear();
        if (entities == null || entities.isEmpty()) {
            return;
        }

        List<BertEmbeddingDocument> sanitized = entities.stream()
                .filter(document -> document != null && document.documentId() != null)
                .toList();
        if (sanitized.isEmpty()) {
            return;
        }

        ensureCollection(validateEmbedding(sanitized.getFirst().embedding()));
        for (int i = 0; i < sanitized.size(); i += batchSize) {
            int end = Math.min(i + batchSize, sanitized.size());
            upsertBatch(sanitized.subList(i, end));
        }
    }

    @Override
    public List<BertEmbeddingMatch> search(BertEmbeddingsStoreQuery query) {
        requireEnabled();
        if (query == null || query.queryVector() == null || query.queryVector().length == 0 || !collectionExists()) {
            return List.of();
        }

        int effectiveLimit = query.limit() > 0 ? query.limit() : Math.toIntExact(Math.max(count(), 0L));
        if (effectiveLimit <= 0) {
            return List.of();
        }

        QdrantSearchResponse response = restClient.post()
                .uri("/collections/{collection}/points/search", collectionName)
                .body(new QdrantSearchRequest(
                        query.queryVector(),
                        effectiveLimit,
                        buildFilter(query),
                        true,
                        true
                ))
                .retrieve()
                .body(QdrantSearchResponse.class);

        if (response == null || response.result() == null) {
            return List.of();
        }

        return response.result().stream()
                .map(point -> new BertEmbeddingMatch(toDocument(point), point.score()))
                .toList();
    }

    private void requireEnabled() {
        if (!enabled) {
            throw new IllegalStateException("Qdrant store is disabled. Set app.embeddings.store.qdrant.enabled=true.");
        }
    }

    private boolean collectionExists() {
        try {
            restClient.get()
                    .uri("/collections/{collection}", collectionName)
                    .retrieve()
                    .toBodilessEntity();
            return true;
        } catch (HttpClientErrorException.NotFound ignored) {
            return false;
        }
    }

    private void ensureCollection(int vectorSize) {
        if (collectionExists()) {
            return;
        }
        restClient.put()
                .uri("/collections/{collection}", collectionName)
                .body(new QdrantCreateCollectionRequest(new QdrantVectorParams(vectorSize, "Cosine")))
                .retrieve()
                .toBodilessEntity();
    }

    private void upsertBatch(List<BertEmbeddingDocument> documents) {
        List<QdrantPoint> points = documents.stream()
                .map(this::toPoint)
                .toList();
        if (points.isEmpty()) {
            return;
        }
        restClient.put()
                .uri("/collections/{collection}/points?wait=true", collectionName)
                .body(new QdrantUpsertRequest(points))
                .retrieve()
                .toBodilessEntity();
    }

    private QdrantFilter buildFilter(BertEmbeddingsStoreQuery query) {
        List<Object> must = new ArrayList<>();
        if (query.category() != null && !query.category().isBlank()) {
            must.add(new QdrantMatchCondition("categoryNormalized", new QdrantMatchValue(normalize(query.category()))));
        }
        if (query.author() != null && !query.author().isBlank()) {
            must.add(new QdrantMatchCondition("authorNormalized", new QdrantMatchValue(normalize(query.author()))));
        }
        if (query.dateFrom() != null || query.dateTo() != null) {
            must.add(new QdrantRangeCondition("depotEpochMillis", new QdrantRange(
                    query.dateFrom() == null ? null : toEpochMillis(query.dateFrom().atStartOfDay()),
                    query.dateTo() == null ? null : toEpochMillis(query.dateTo().plusDays(1).atStartOfDay()) - 1L
            )));
        }
        return must.isEmpty() ? null : new QdrantFilter(must);
    }

    private QdrantPoint toPoint(BertEmbeddingDocument document) {
        validateEmbedding(document.embedding());
        return new QdrantPoint(
                document.documentId(),
                document.embedding(),
                new QdrantPayload(
                        document.documentId(),
                        document.title(),
                        document.author(),
                        document.category(),
                        document.filename(),
                        document.depotDateTime(),
                        document.contentText(),
                        normalize(document.author()),
                        normalize(document.category()),
                        document.depotDateTime() == null ? null : toEpochMillis(document.depotDateTime())
                )
        );
    }

    private BertEmbeddingDocument toDocument(QdrantSearchPoint point) {
        QdrantPayload payload = point.payload();
        long documentId = payload != null && payload.documentId() != null ? payload.documentId() : asLong(point.id());
        return new BertEmbeddingDocument(
                documentId,
                payload == null ? null : payload.title(),
                payload == null ? null : payload.author(),
                payload == null ? null : payload.category(),
                payload == null ? null : payload.filename(),
                payload == null ? null : payload.depotDateTime(),
                payload == null ? null : payload.contentText(),
                point.vector() == null ? new float[0] : point.vector()
        );
    }

    private int validateEmbedding(float[] embedding) {
        if (embedding == null || embedding.length == 0) {
            throw new IllegalArgumentException("Qdrant requires a non-empty embedding vector.");
        }
        return embedding.length;
    }

    private String normalize(String value) {
        return value == null ? null : value.trim().toLowerCase(Locale.ROOT);
    }

    private long toEpochMillis(LocalDateTime value) {
        return value.atZone(ZoneOffset.UTC).toInstant().toEpochMilli();
    }

    private long asLong(Object value) {
        if (value instanceof Number number) {
            return number.longValue();
        }
        if (value instanceof String string && !string.isBlank()) {
            return Long.parseLong(string);
        }
        throw new IllegalStateException("Unsupported Qdrant point id: " + value);
    }

    private record QdrantCreateCollectionRequest(
            QdrantVectorParams vectors
    ) {
    }

    private record QdrantVectorParams(
            int size,
            String distance
    ) {
    }

    private record QdrantUpsertRequest(
            List<QdrantPoint> points
    ) {
    }

    private record QdrantPoint(
            long id,
            float[] vector,
            QdrantPayload payload
    ) {
    }

    @JsonInclude(JsonInclude.Include.NON_NULL)
    private record QdrantPayload(
            Long documentId,
            String title,
            String author,
            String category,
            String filename,
            LocalDateTime depotDateTime,
            String contentText,
            String authorNormalized,
            String categoryNormalized,
            Long depotEpochMillis
    ) {
    }

    private record QdrantCountRequest(
            boolean exact
    ) {
    }

    private record QdrantCountResponse(
            QdrantCountResult result
    ) {
    }

    private record QdrantCountResult(
            long count
    ) {
    }

    @JsonInclude(JsonInclude.Include.NON_NULL)
    private record QdrantSearchRequest(
            float[] vector,
            int limit,
            QdrantFilter filter,
            @JsonProperty("with_payload") boolean withPayload,
            @JsonProperty("with_vector") boolean withVector
    ) {
    }

    private record QdrantSearchResponse(
            List<QdrantSearchPoint> result
    ) {
    }

    private record QdrantSearchPoint(
            Object id,
            float score,
            QdrantPayload payload,
            float[] vector
    ) {
    }

    private record QdrantScrollRequest(
            int limit,
            Object offset,
            @JsonProperty("with_payload") boolean withPayload,
            @JsonProperty("with_vector") boolean withVector
    ) {
    }

    private record QdrantScrollResponse(
            QdrantScrollResult result
    ) {
    }

    private record QdrantScrollResult(
            List<QdrantSearchPoint> points,
            @JsonProperty("next_page_offset") Object nextPageOffset
    ) {
    }

    private record QdrantFilter(
            List<Object> must
    ) {
    }

    private record QdrantMatchCondition(
            String key,
            QdrantMatchValue match
    ) {
    }

    private record QdrantMatchValue(
            String value
    ) {
    }

    private record QdrantRangeCondition(
            String key,
            QdrantRange range
    ) {
    }

    @JsonInclude(JsonInclude.Include.NON_NULL)
    private record QdrantRange(
            Long gte,
            Long lte
    ) {
    }
}
