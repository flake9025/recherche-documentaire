package fr.vvlabs.recherche.service.index.embeddings.store.qdrant;

import fr.vvlabs.recherche.service.index.embeddings.BertEmbeddingDocument;
import fr.vvlabs.recherche.service.index.embeddings.BertEmbeddingsStoreType;
import fr.vvlabs.recherche.service.index.embeddings.store.BertEmbeddingsStore;
import org.springframework.stereotype.Component;

import java.util.Collection;
import java.util.List;

/**
 * Point d'extension pour une integration Qdrant.
 */
@Component
public class QdrantBertEmbeddingsStore implements BertEmbeddingsStore {

    private static final String MESSAGE = "Qdrant store is not implemented in this POC yet";

    @Override
    public String getType() {
        return BertEmbeddingsStoreType.QDRANT;
    }

    @Override
    public void upsert(BertEmbeddingDocument document) {
        throw new UnsupportedOperationException(MESSAGE);
    }

    @Override
    public List<BertEmbeddingDocument> findAll() {
        throw new UnsupportedOperationException(MESSAGE);
    }

    @Override
    public long count() {
        throw new UnsupportedOperationException(MESSAGE);
    }

    @Override
    public void clear() {
        throw new UnsupportedOperationException(MESSAGE);
    }

    @Override
    public void replaceAll(Collection<BertEmbeddingDocument> entities) {
        throw new UnsupportedOperationException(MESSAGE);
    }

    @Override
    public List<BertEmbeddingMatch> search(BertEmbeddingsStoreQuery query) {
        throw new UnsupportedOperationException(MESSAGE);
    }
}
