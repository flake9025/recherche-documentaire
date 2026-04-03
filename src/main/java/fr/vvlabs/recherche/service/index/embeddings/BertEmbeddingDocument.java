package fr.vvlabs.recherche.service.index.embeddings;

import java.time.LocalDateTime;

public record BertEmbeddingDocument(
        Long documentId,
        String title,
        String author,
        String category,
        String filename,
        LocalDateTime depotDateTime,
        String contentText,
        float[] embedding
) {
}
