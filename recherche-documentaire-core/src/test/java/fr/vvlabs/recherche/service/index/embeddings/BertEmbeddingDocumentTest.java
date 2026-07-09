package fr.vvlabs.recherche.service.index.embeddings;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class BertEmbeddingDocumentTest {

    @Test
    void pointId_isDerivedFromDocumentIdAndChunkIndex() {
        BertEmbeddingDocument doc = new BertEmbeddingDocument(
                42L, 3, 5, "T", "A", "C", "f.pdf", null, "chunk", new float[]{1.0f});

        assertThat(doc.pointId()).isEqualTo(42L * BertEmbeddingDocument.POINT_ID_FACTOR + 3);
    }

    @Test
    void pointId_roundTripsBackToDocumentIdAndChunkIndex() {
        long pointId = BertEmbeddingDocument.pointId(123L, 7);

        assertThat(BertEmbeddingDocument.documentIdFromPointId(pointId)).isEqualTo(123L);
        assertThat(BertEmbeddingDocument.chunkIndexFromPointId(pointId)).isEqualTo(7);
    }

    @Test
    void convenienceConstructor_defaultsToSingleChunk() {
        BertEmbeddingDocument doc = new BertEmbeddingDocument(
                1L, "T", "A", "C", "f.pdf", null, "content", new float[]{1.0f});

        assertThat(doc.chunkIndex()).isZero();
        assertThat(doc.chunkCount()).isEqualTo(1);
        assertThat(doc.pointId()).isEqualTo(BertEmbeddingDocument.POINT_ID_FACTOR);
    }
}
