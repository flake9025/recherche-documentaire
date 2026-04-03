package fr.vvlabs.recherche.service.index.embeddings;

/**
 * Types de store supportes pour les embeddings BERT.
 */
public final class BertEmbeddingsStoreType {

    public static final String HASHMAP = "hashmap";
    public static final String FAISS_REMOTE = "faiss-remote";
    public static final String QDRANT = "qdrant";
    public static final String MILVUS = "milvus";

    private BertEmbeddingsStoreType() {
    }
}
