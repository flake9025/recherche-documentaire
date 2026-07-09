package fr.vvlabs.recherche.service.index.embeddings.store.milvus;

import fr.vvlabs.recherche.service.index.embeddings.BertEmbeddingDocument;
import fr.vvlabs.recherche.service.index.embeddings.BertEmbeddingsStoreType;
import fr.vvlabs.recherche.service.index.embeddings.store.BertEmbeddingsStore;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import java.time.format.DateTimeFormatter;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Store BERT deleguant l'indexation et la recherche a Milvus via l'API REST v2.
 */
@Component
@ConditionalOnProperty(name = "app.embeddings.store.default", havingValue = BertEmbeddingsStoreType.MILVUS)
public class MilvusBertEmbeddingsStore implements BertEmbeddingsStore {

    private static final int DEFAULT_QUERY_LIMIT = 256;
    private static final String VECTOR_FIELD = "embedding";
    // Cle primaire = pointId (documentId * FACTOR + chunkIndex) pour autoriser plusieurs chunks par document.
    private static final String PRIMARY_FIELD = "pointId";
    private static final String DOCUMENT_ID_FIELD = "documentId";
    private static final String DEFAULT_FILTER = PRIMARY_FIELD + " >= 0";
    private static final DateTimeFormatter DATE_TIME_FORMATTER = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss");
    private static final List<String> OUTPUT_FIELDS = List.of(
            PRIMARY_FIELD,
            DOCUMENT_ID_FIELD,
            "chunkIndex",
            "chunkCount",
            "title",
            "author",
            "category",
            "filename",
            "depotDateTime",
            "contentText",
            VECTOR_FIELD
    );

    private final RestClient restClient;
    private final boolean enabled;
    private final String collectionName;
    private final int batchSize;

    public MilvusBertEmbeddingsStore(
            RestClient.Builder restClientBuilder,
            @Value("${app.embeddings.store.milvus.base-url:http://localhost:19530}") String baseUrl,
            @Value("${app.embeddings.store.milvus.token:}") String token,
            @Value("${app.embeddings.store.milvus.collection:document_embeddings}") String collectionName,
            @Value("${app.embeddings.store.milvus.enabled:false}") boolean enabled,
            @Value("${app.embeddings.store.milvus.batch-size:128}") int batchSize
    ) {
        RestClient.Builder builder = restClientBuilder.baseUrl(baseUrl);
        if (token != null && !token.isBlank()) {
            builder = builder.defaultHeader("Authorization", "Bearer " + token.trim());
        }
        this.restClient = builder.build();
        this.enabled = enabled;
        this.collectionName = collectionName;
        this.batchSize = Math.max(batchSize, 1);
    }

    @Override
    public String getType() {
        return BertEmbeddingsStoreType.MILVUS;
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
    public void deleteByDocumentId(Long documentId) {
        requireEnabled();
        if (documentId == null || !collectionExists()) {
            return;
        }
        // Supprime tous les chunks du document (filtre sur le champ documentId).
        LinkedHashMap<String, Object> body = new LinkedHashMap<>();
        body.put("collectionName", collectionName);
        body.put("filter", DOCUMENT_ID_FIELD + " == " + documentId);
        ensureSuccess(postForMap("/v2/vectordb/entities/delete", body), "Milvus delete by documentId");
    }

    @Override
    public List<BertEmbeddingDocument> findAll() {
        requireEnabled();
        if (!collectionExists()) {
            return List.of();
        }

        List<BertEmbeddingDocument> documents = new ArrayList<>();
        int offset = 0;
        while (true) {
            List<Map<String, Object>> rows = dataList(queryEntities(DEFAULT_FILTER, OUTPUT_FIELDS, DEFAULT_QUERY_LIMIT, offset));
            if (rows.isEmpty()) {
                break;
            }

            rows.stream()
                    .map(this::toDocument)
                    .forEach(documents::add);
            if (rows.size() < DEFAULT_QUERY_LIMIT) {
                break;
            }
            offset += DEFAULT_QUERY_LIMIT;
        }
        return documents;
    }

    @Override
    public long count() {
        requireEnabled();
        if (!collectionExists()) {
            return 0L;
        }

        List<Map<String, Object>> rows = dataList(queryEntities(DEFAULT_FILTER, List.of("count(*)"), 1, 0));
        if (rows.isEmpty()) {
            return 0L;
        }
        Long count = nullableLong(rows.getFirst().get("count(*)"));
        return count == null ? 0L : count;
    }

    @Override
    public void clear() {
        requireEnabled();
        if (!collectionExists()) {
            return;
        }
        ensureSuccess(postForMap("/v2/vectordb/collections/drop", Map.of("collectionName", collectionName)), "Milvus collection drop");
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

        LinkedHashMap<String, Object> body = new LinkedHashMap<>();
        body.put("collectionName", collectionName);
        body.put("data", List.of(query.queryVector()));
        body.put("annsField", VECTOR_FIELD);
        body.put("limit", effectiveLimit);
        body.put("outputFields", OUTPUT_FIELDS);
        String filter = buildFilter(query);
        if (!filter.isBlank()) {
            body.put("filter", filter);
        }

        Map<String, Object> response = postForMap("/v2/vectordb/entities/search", body);
        ensureSuccess(response, "Milvus search");
        return dataList(response).stream()
                .map(row -> new BertEmbeddingMatch(toDocument(row), semanticScore(row)))
                .toList();
    }

    private void requireEnabled() {
        if (!enabled) {
            throw new IllegalStateException("Milvus store is disabled. Set app.embeddings.store.milvus.enabled=true.");
        }
    }

    private boolean collectionExists() {
        Map<String, Object> response = postForMap("/v2/vectordb/collections/has", Map.of("collectionName", collectionName));
        ensureSuccess(response, "Milvus collection existence check");
        Object has = dataMap(response).get("has");
        return has instanceof Boolean value && value;
    }

    private void ensureCollection(int vectorSize) {
        if (collectionExists()) {
            return;
        }
        ensureSuccess(postForMap("/v2/vectordb/collections/create", Map.of(
                "collectionName", collectionName,
                "dimension", vectorSize,
                "metricType", "COSINE",
                "primaryFieldName", PRIMARY_FIELD,
                "idType", "Int64",
                "vectorFieldName", VECTOR_FIELD,
                "autoId", false,
                "enableDynamicField", true
        )), "Milvus collection creation");
    }

    private Map<String, Object> queryEntities(String filter, List<String> outputFields, int limit, int offset) {
        LinkedHashMap<String, Object> body = new LinkedHashMap<>();
        body.put("collectionName", collectionName);
        body.put("filter", filter);
        body.put("outputFields", outputFields);
        body.put("limit", limit);
        body.put("offset", offset);
        Map<String, Object> response = postForMap("/v2/vectordb/entities/query", body);
        ensureSuccess(response, "Milvus query");
        return response;
    }

    private void upsertBatch(List<BertEmbeddingDocument> documents) {
        if (documents.isEmpty()) {
            return;
        }
        ensureSuccess(postForMap("/v2/vectordb/entities/upsert", Map.of(
                "collectionName", collectionName,
                "data", documents.stream().map(this::toEntity).toList()
        )), "Milvus upsert");
    }

    private Map<String, Object> toEntity(BertEmbeddingDocument document) {
        LinkedHashMap<String, Object> entity = new LinkedHashMap<>();
        entity.put(PRIMARY_FIELD, document.pointId());
        entity.put(DOCUMENT_ID_FIELD, document.documentId());
        entity.put("chunkIndex", document.chunkIndex());
        entity.put("chunkCount", document.chunkCount());
        putIfNotNull(entity, "title", document.title());
        putIfNotNull(entity, "author", document.author());
        putIfNotNull(entity, "category", document.category());
        putIfNotNull(entity, "filename", document.filename());
        putIfNotNull(entity, "depotDateTime", formatDateTime(document.depotDateTime()));
        putIfNotNull(entity, "contentText", document.contentText());
        putIfNotNull(entity, "authorNormalized", normalize(document.author()));
        putIfNotNull(entity, "categoryNormalized", normalize(document.category()));
        putIfNotNull(entity, "depotEpochMillis", document.depotDateTime() == null ? null : toEpochMillis(document.depotDateTime()));
        validateEmbedding(document.embedding());
        entity.put(VECTOR_FIELD, document.embedding());
        return entity;
    }

    private BertEmbeddingDocument toDocument(Map<String, Object> row) {
        Long pointId = nullableLong(row.get(PRIMARY_FIELD));
        Long documentId = nullableLong(row.get(DOCUMENT_ID_FIELD));
        if (documentId == null && pointId != null) {
            documentId = BertEmbeddingDocument.documentIdFromPointId(pointId);
        }
        Integer chunkIndex = nullableInt(row.get("chunkIndex"));
        if (chunkIndex == null && pointId != null) {
            chunkIndex = BertEmbeddingDocument.chunkIndexFromPointId(pointId);
        }
        Integer chunkCount = nullableInt(row.get("chunkCount"));
        return new BertEmbeddingDocument(
                documentId,
                chunkIndex == null ? 0 : chunkIndex,
                chunkCount == null ? 1 : chunkCount,
                textOrNull(row.get("title")),
                textOrNull(row.get("author")),
                textOrNull(row.get("category")),
                textOrNull(row.get("filename")),
                parseDateTime(textOrNull(row.get("depotDateTime"))),
                textOrNull(row.get("contentText")),
                toFloatArray(row.get(VECTOR_FIELD))
        );
    }

    private float semanticScore(Map<String, Object> row) {
        Object score = row.get("distance");
        if (score == null) {
            score = row.get("score");
        }
        if (score instanceof Number number) {
            return number.floatValue();
        }
        return score == null ? 0.0f : Float.parseFloat(score.toString());
    }

    private int validateEmbedding(float[] embedding) {
        if (embedding == null || embedding.length == 0) {
            throw new IllegalArgumentException("Milvus requires a non-empty embedding vector.");
        }
        return embedding.length;
    }

    private void putIfNotNull(Map<String, Object> target, String key, Object value) {
        if (value != null) {
            target.put(key, value);
        }
    }

    private String buildFilter(BertEmbeddingsStoreQuery query) {
        List<String> clauses = new ArrayList<>();
        if (query.category() != null && !query.category().isBlank()) {
            clauses.add("categoryNormalized == \"" + escape(normalize(query.category())) + "\"");
        }
        if (query.author() != null && !query.author().isBlank()) {
            clauses.add("authorNormalized == \"" + escape(normalize(query.author())) + "\"");
        }
        if (query.dateFrom() != null) {
            clauses.add("depotEpochMillis >= " + toEpochMillis(query.dateFrom().atStartOfDay()));
        }
        if (query.dateTo() != null) {
            clauses.add("depotEpochMillis <= " + (toEpochMillis(query.dateTo().plusDays(1).atStartOfDay()) - 1L));
        }
        return String.join(" && ", clauses);
    }

    private String normalize(String value) {
        return value == null ? null : value.trim().toLowerCase(Locale.ROOT);
    }

    private String escape(String value) {
        return value == null ? "" : value.replace("\\", "\\\\").replace("\"", "\\\"");
    }

    private long toEpochMillis(LocalDateTime value) {
        return value.atZone(ZoneOffset.UTC).toInstant().toEpochMilli();
    }

    private String textOrNull(Object value) {
        if (value == null) {
            return null;
        }
        String text = value.toString();
        return text.isBlank() ? null : text;
    }

    private LocalDateTime parseDateTime(String value) {
        return value == null || value.isBlank() ? null : LocalDateTime.parse(value);
    }

    private String formatDateTime(LocalDateTime value) {
        return value == null ? null : DATE_TIME_FORMATTER.format(value);
    }

    private Long nullableLong(Object value) {
        if (value == null) {
            return null;
        }
        if (value instanceof Number number) {
            return number.longValue();
        }
        String raw = value.toString();
        return raw.isBlank() ? null : Long.parseLong(raw);
    }

    private Integer nullableInt(Object value) {
        if (value == null) {
            return null;
        }
        if (value instanceof Number number) {
            return number.intValue();
        }
        String raw = value.toString();
        return raw.isBlank() ? null : Integer.parseInt(raw);
    }

    private float[] toFloatArray(Object value) {
        if (value instanceof float[] floats) {
            return floats;
        }
        if (!(value instanceof List<?> list)) {
            return new float[0];
        }
        float[] values = new float[list.size()];
        for (int i = 0; i < list.size(); i++) {
            Object item = list.get(i);
            values[i] = item instanceof Number number ? number.floatValue() : Float.parseFloat(String.valueOf(item));
        }
        return values;
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> postForMap(String uri, Object body) {
        Object response = restClient.post()
                .uri(uri)
                .contentType(MediaType.APPLICATION_JSON)
                .body(body)
                .retrieve()
                .body(Map.class);
        return response instanceof Map<?, ?> map ? (Map<String, Object>) map : Map.of();
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> dataMap(Map<String, Object> response) {
        Object data = response == null ? null : response.get("data");
        return data instanceof Map<?, ?> map ? (Map<String, Object>) map : Map.of();
    }

    @SuppressWarnings("unchecked")
    private List<Map<String, Object>> dataList(Map<String, Object> response) {
        Object data = response == null ? null : response.get("data");
        if (!(data instanceof List<?> list)) {
            return List.of();
        }
        List<Map<String, Object>> rows = new ArrayList<>();
        for (Object item : list) {
            if (item instanceof Map<?, ?> map) {
                rows.add((Map<String, Object>) map);
            }
        }
        return rows;
    }

    private void ensureSuccess(Map<String, Object> response, String action) {
        if (response == null || response.isEmpty()) {
            return;
        }
        Object code = response.get("code");
        boolean failed = false;
        if (code instanceof Number number) {
            failed = number.intValue() != 0;
        } else if (code instanceof String string) {
            failed = !string.isBlank() && !"0".equals(string.trim());
        }
        if (failed) {
            Object message = response.getOrDefault("message", response.getOrDefault("msg", "unknown error"));
            throw new IllegalStateException(action + " failed: " + message);
        }
    }
}
