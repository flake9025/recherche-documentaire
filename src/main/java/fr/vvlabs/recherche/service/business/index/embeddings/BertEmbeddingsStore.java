package fr.vvlabs.recherche.service.business.index.embeddings;

import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

@Component
public class BertEmbeddingsStore {

    private final Map<Long, BertEmbeddingDocument> documents = new ConcurrentHashMap<>();

    public void upsert(BertEmbeddingDocument document) {
        if (document == null || document.documentId() == null) {
            return;
        }
        documents.put(document.documentId(), document);
    }

    public List<BertEmbeddingDocument> findAll() {
        return new ArrayList<>(documents.values());
    }

    public long count() {
        return documents.size();
    }

    public void clear() {
        documents.clear();
    }

    public void replaceAll(Collection<BertEmbeddingDocument> entities) {
        documents.clear();
        if (entities == null) {
            return;
        }
        for (BertEmbeddingDocument entity : entities) {
            upsert(entity);
        }
    }
}
