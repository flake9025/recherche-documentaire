package fr.vvlabs.recherche.service.index.embeddings.store.faiss;

import fr.vvlabs.recherche.service.index.embeddings.BertEmbeddingDocument;
import fr.vvlabs.recherche.service.index.embeddings.BertEmbeddingsStoreType;
import fr.vvlabs.recherche.service.index.embeddings.faiss.FaissRemoteSearchMatch;
import fr.vvlabs.recherche.service.index.embeddings.faiss.FaissRemoteSearchRequest;
import fr.vvlabs.recherche.service.index.embeddings.faiss.FaissRemoteSearchResponse;
import fr.vvlabs.recherche.service.index.embeddings.faiss.FaissRemoteStatsResponse;
import fr.vvlabs.recherche.service.index.embeddings.faiss.FaissRemoteStoreDocument;
import fr.vvlabs.recherche.service.index.embeddings.store.BertEmbeddingsStore;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import java.util.Collection;
import java.util.List;

/**
 * Store BERT deleguant l'indexation et la recherche a un service FAISS distant.
 */
@Component
@ConditionalOnProperty(name = "app.embeddings.store.default", havingValue = BertEmbeddingsStoreType.FAISS_REMOTE)
public class FaissRemoteBertEmbeddingsStore implements BertEmbeddingsStore {

    private final RestClient restClient;
    private final boolean enabled;

    public FaissRemoteBertEmbeddingsStore(
            RestClient.Builder restClientBuilder,
            @Value("${app.embeddings.store.faiss.base-url:http://localhost:8090}") String baseUrl,
            @Value("${app.embeddings.store.faiss.enabled:false}") boolean enabled
    ) {
        this.restClient = restClientBuilder.baseUrl(baseUrl).build();
        this.enabled = enabled;
    }

    @Override
    public String getType() {
        return BertEmbeddingsStoreType.FAISS_REMOTE;
    }

    @Override
    public void upsert(BertEmbeddingDocument document) {
        requireEnabled();
        restClient.post()
                .uri("/api/faiss/documents")
                .body(toRemoteDocument(document))
                .retrieve()
                .toBodilessEntity();
    }

    @Override
    public List<BertEmbeddingDocument> findAll() {
        requireEnabled();
        FaissRemoteStoreDocument[] documents = restClient.get()
                .uri("/api/faiss/documents")
                .retrieve()
                .body(FaissRemoteStoreDocument[].class);
        return documents == null ? List.of() : List.of(documents).stream().map(this::fromRemoteDocument).toList();
    }

    @Override
    public long count() {
        requireEnabled();
        FaissRemoteStatsResponse response = restClient.get()
                .uri("/api/faiss/stats")
                .retrieve()
                .body(FaissRemoteStatsResponse.class);
        return response == null ? 0L : response.count();
    }

    @Override
    public void clear() {
        requireEnabled();
        restClient.delete()
                .uri("/api/faiss/documents")
                .retrieve()
                .toBodilessEntity();
    }

    @Override
    public void replaceAll(Collection<BertEmbeddingDocument> entities) {
        requireEnabled();
        List<FaissRemoteStoreDocument> body = entities == null
                ? List.of()
                : entities.stream().map(this::toRemoteDocument).toList();
        restClient.put()
                .uri("/api/faiss/documents")
                .body(body)
                .retrieve()
                .toBodilessEntity();
    }

    @Override
    public List<BertEmbeddingMatch> search(BertEmbeddingsStoreQuery query) {
        requireEnabled();
        FaissRemoteSearchResponse response = restClient.post()
                .uri("/api/faiss/search")
                .body(new FaissRemoteSearchRequest(
                        query.queryVector(),
                        query.category(),
                        query.author(),
                        query.dateFrom(),
                        query.dateTo(),
                        query.limit()
                ))
                .retrieve()
                .body(FaissRemoteSearchResponse.class);
        if (response == null || response.matches() == null) {
            return List.of();
        }
        return response.matches().stream()
                .map(this::fromRemoteMatch)
                .toList();
    }

    private void requireEnabled() {
        if (!enabled) {
            throw new IllegalStateException("FAISS remote store is disabled. Set app.embeddings.store.faiss.enabled=true.");
        }
    }

    private FaissRemoteStoreDocument toRemoteDocument(BertEmbeddingDocument document) {
        return new FaissRemoteStoreDocument(
                document.documentId(),
                document.title(),
                document.author(),
                document.category(),
                document.filename(),
                document.depotDateTime(),
                document.contentText(),
                document.embedding()
        );
    }

    private BertEmbeddingDocument fromRemoteDocument(FaissRemoteStoreDocument document) {
        return new BertEmbeddingDocument(
                document.documentId(),
                document.title(),
                document.author(),
                document.category(),
                document.filename(),
                document.depotDateTime(),
                document.contentText(),
                document.embedding()
        );
    }

    private BertEmbeddingMatch fromRemoteMatch(FaissRemoteSearchMatch match) {
        return new BertEmbeddingMatch(fromRemoteDocument(match.document()), match.semanticScore());
    }
}
