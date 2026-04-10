package fr.vvlabs.recherche.service.index.embeddings.store.milvus;

import fr.vvlabs.recherche.service.index.embeddings.BertEmbeddingDocument;
import fr.vvlabs.recherche.service.index.embeddings.BertEmbeddingsStoreType;
import fr.vvlabs.recherche.service.index.embeddings.store.BertEmbeddingsStore;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.util.Collection;
import java.util.List;

/**
 * Point d'extension pour une integration Milvus.
 */
@Component
@ConditionalOnProperty(name = "app.embeddings.store.default", havingValue = BertEmbeddingsStoreType.MILVUS)
public class MilvusBertEmbeddingsStore implements BertEmbeddingsStore {

    private static final String MESSAGE = "Milvus store is not implemented in this POC yet";

    @Override
    public String getType() {
        return BertEmbeddingsStoreType.MILVUS;
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
