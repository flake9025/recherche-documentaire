package fr.vvlabs.recherche.service.index.embeddings;

import java.time.LocalDateTime;

/**
 * Fragment vectorise d'un document (un chunk = un vecteur).
 *
 * <p>Un document est decoupe en un ou plusieurs chunks; chaque chunk produit son
 * propre embedding et sa propre entree dans le store vectoriel. Les metadonnees
 * ({@code title}, {@code author}, {@code category}, {@code filename}, {@code depotDateTime})
 * restent celles du document; {@code contentText} porte le texte du chunk (extrait affiche),
 * et {@code chunkIndex}/{@code chunkCount} situent le chunk dans le document.</p>
 *
 * <p>La cle unique d'un chunk dans les stores vectoriels est {@link #pointId()},
 * derivee de {@code documentId} et {@code chunkIndex}.</p>
 */
public record BertEmbeddingDocument(
        Long documentId,
        int chunkIndex,
        int chunkCount,
        String title,
        String author,
        String category,
        String filename,
        LocalDateTime depotDateTime,
        String contentText,
        float[] embedding
) {

    /**
     * Facteur de derivation de la cle de point: {@code pointId = documentId * FACTOR + chunkIndex}.
     * Limite implicite de {@value} chunks par document (largement suffisant).
     */
    public static final long POINT_ID_FACTOR = 10_000L;

    /**
     * Constructeur de commodite pour un document a chunk unique (chunkIndex=0, chunkCount=1).
     *
     * @param documentId identifiant du document
     * @param title titre
     * @param author auteur
     * @param category categorie
     * @param filename nom du fichier
     * @param depotDateTime date de depot
     * @param contentText texte (chunk unique)
     * @param embedding vecteur
     */
    public BertEmbeddingDocument(
            Long documentId,
            String title,
            String author,
            String category,
            String filename,
            LocalDateTime depotDateTime,
            String contentText,
            float[] embedding
    ) {
        this(documentId, 0, 1, title, author, category, filename, depotDateTime, contentText, embedding);
    }

    /**
     * Identifiant unique et stable du chunk, utilisable comme cle de point / PK numerique
     * dans les stores vectoriels (Qdrant, Milvus, hashmap, FAISS).
     *
     * @return identifiant compose {@code documentId * POINT_ID_FACTOR + chunkIndex}
     */
    public long pointId() {
        return pointId(documentId, chunkIndex);
    }

    /**
     * Calcule la cle de point pour un couple (documentId, chunkIndex).
     *
     * @param documentId identifiant du document
     * @param chunkIndex position du chunk (0-based)
     * @return identifiant compose
     */
    public static long pointId(Long documentId, int chunkIndex) {
        return documentId * POINT_ID_FACTOR + chunkIndex;
    }

    /**
     * Retrouve l'identifiant du document a partir d'une cle de point.
     *
     * @param pointId cle de point
     * @return identifiant du document
     */
    public static long documentIdFromPointId(long pointId) {
        return pointId / POINT_ID_FACTOR;
    }

    /**
     * Retrouve l'index du chunk a partir d'une cle de point.
     *
     * @param pointId cle de point
     * @return index du chunk (0-based)
     */
    public static int chunkIndexFromPointId(long pointId) {
        return (int) (pointId % POINT_ID_FACTOR);
    }
}
