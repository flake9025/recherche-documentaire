package fr.vvlabs.recherche.service.index.embeddings.store.hashmap;

import fr.vvlabs.recherche.service.index.embeddings.BertEmbeddingDocument;
import fr.vvlabs.recherche.service.index.embeddings.store.BertEmbeddingsStore;
import fr.vvlabs.recherche.service.index.embeddings.store.BertEmbeddingsStore.BertEmbeddingMatch;
import fr.vvlabs.recherche.service.index.embeddings.store.BertEmbeddingsStore.BertEmbeddingsStoreQuery;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class HashMapBertEmbeddingsStoreTest {

    @Test
    void search_filtersAndSortsBySemanticScore() {
        HashMapBertEmbeddingsStore store = new HashMapBertEmbeddingsStore();
        store.upsert(new BertEmbeddingDocument(
                1L, "Titre 1", "Alice", "NOTE", "a.pdf",
                LocalDateTime.of(2025, 1, 10, 10, 0),
                "contenu 1", new float[]{1.0f, 0.0f}
        ));
        store.upsert(new BertEmbeddingDocument(
                2L, "Titre 2", "Alice", "NOTE", "b.pdf",
                LocalDateTime.of(2025, 1, 11, 10, 0),
                "contenu 2", new float[]{0.5f, 0.5f}
        ));
        store.upsert(new BertEmbeddingDocument(
                3L, "Titre 3", "Bob", "RAPPORT", "c.pdf",
                LocalDateTime.of(2025, 1, 11, 10, 0),
                "contenu 3", new float[]{0.0f, 1.0f}
        ));

        List<BertEmbeddingMatch> results = store.search(new BertEmbeddingsStoreQuery(
                new float[]{1.0f, 0.0f},
                "NOTE",
                "Alice",
                LocalDate.of(2025, 1, 10),
                LocalDate.of(2025, 1, 11),
                10
        ));

        assertThat(results).hasSize(2);
        assertThat(results.get(0).document().documentId()).isEqualTo(1L);
        assertThat(results.get(1).document().documentId()).isEqualTo(2L);
        assertThat(results.get(0).semanticScore()).isGreaterThan(results.get(1).semanticScore());
    }

    @Test
    void replaceAll_replacesPreviousContent() {
        HashMapBertEmbeddingsStore store = new HashMapBertEmbeddingsStore();
        store.upsert(new BertEmbeddingDocument(1L, "", "", "", "", null, null, new float[0]));

        store.replaceAll(List.of(
                new BertEmbeddingDocument(2L, "", "", "", "", null, null, new float[]{1.0f})
        ));

        assertThat(store.count()).isEqualTo(1);
        assertThat(store.findAll().getFirst().documentId()).isEqualTo(2L);
    }

    @Test
    void upsert_keepsMultipleChunksPerDocument() {
        HashMapBertEmbeddingsStore store = new HashMapBertEmbeddingsStore();
        // Deux chunks du meme document (documentId=1) ne doivent PAS s'ecraser.
        store.upsert(new BertEmbeddingDocument(1L, 0, 2, "Titre", "Alice", "NOTE", "a.pdf", null, "chunk 0", new float[]{1.0f, 0.0f}));
        store.upsert(new BertEmbeddingDocument(1L, 1, 2, "Titre", "Alice", "NOTE", "a.pdf", null, "chunk 1", new float[]{0.0f, 1.0f}));

        assertThat(store.count()).isEqualTo(2);
        assertThat(store.countDocuments()).isEqualTo(1);
    }

    @Test
    void deleteByDocumentId_removesAllChunksOfDocument() {
        HashMapBertEmbeddingsStore store = new HashMapBertEmbeddingsStore();
        store.upsert(new BertEmbeddingDocument(1L, 0, 2, "T", "A", "NOTE", "a.pdf", null, "c0", new float[]{1.0f, 0.0f}));
        store.upsert(new BertEmbeddingDocument(1L, 1, 2, "T", "A", "NOTE", "a.pdf", null, "c1", new float[]{0.0f, 1.0f}));
        store.upsert(new BertEmbeddingDocument(2L, 0, 1, "T2", "B", "NOTE", "b.pdf", null, "c0", new float[]{1.0f, 1.0f}));

        store.deleteByDocumentId(1L);

        assertThat(store.count()).isEqualTo(1);
        assertThat(store.findAll().getFirst().documentId()).isEqualTo(2L);
    }

    @Test
    void bestChunkPerDocument_keepsHighestScoringChunkPerDocument() {
        // Le meilleur chunk (score max) doit representer son document, les autres etant ecartes.
        BertEmbeddingDocument doc1ChunkA = new BertEmbeddingDocument(1L, 0, 2, "T", "A", "NOTE", "a.pdf", null, "c0", new float[]{1.0f, 0.0f});
        BertEmbeddingDocument doc1ChunkB = new BertEmbeddingDocument(1L, 1, 2, "T", "A", "NOTE", "a.pdf", null, "c1", new float[]{0.0f, 1.0f});
        BertEmbeddingDocument doc2 = new BertEmbeddingDocument(2L, 0, 1, "T2", "B", "NOTE", "b.pdf", null, "c0", new float[]{1.0f, 1.0f});

        List<BertEmbeddingMatch> matches = List.of(
                new BertEmbeddingMatch(doc1ChunkA, 0.4f),
                new BertEmbeddingMatch(doc1ChunkB, 0.9f),
                new BertEmbeddingMatch(doc2, 0.7f)
        );

        List<BertEmbeddingMatch> best = BertEmbeddingsStore.bestChunkPerDocument(matches);

        assertThat(best).hasSize(2);
        // Trie par score decroissant: doc1 (chunk B, 0.9) devant doc2 (0.7).
        assertThat(best.get(0).document().documentId()).isEqualTo(1L);
        assertThat(best.get(0).document().chunkIndex()).isEqualTo(1);
        assertThat(best.get(0).semanticScore()).isEqualTo(0.9f);
        assertThat(best.get(1).document().documentId()).isEqualTo(2L);
    }
}
