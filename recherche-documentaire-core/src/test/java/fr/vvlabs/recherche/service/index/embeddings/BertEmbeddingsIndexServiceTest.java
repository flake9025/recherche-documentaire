package fr.vvlabs.recherche.service.index.embeddings;

import fr.vvlabs.recherche.dto.DocumentDTO;
import fr.vvlabs.recherche.model.BertEmbeddingsIndexEntity;
import fr.vvlabs.recherche.repository.BertEmbeddingsIndexRepository;
import fr.vvlabs.recherche.service.index.embeddings.BertEmbeddingDocument;
import fr.vvlabs.recherche.service.index.embeddings.BertEmbeddingsIndexService;
import fr.vvlabs.recherche.service.index.embeddings.BertEmbeddingsService;
import fr.vvlabs.recherche.service.index.embeddings.store.BertEmbeddingsStore;
import fr.vvlabs.recherche.service.index.embeddings.store.BertEmbeddingsStoreFactory;
import fr.vvlabs.recherche.service.index.embeddings.store.hashmap.HashMapBertEmbeddingsStore;
import fr.vvlabs.recherche.service.index.lucene.LuceneAutocompleteService;
import fr.vvlabs.recherche.service.cipher.CipherService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Captor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.DataInputStream;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.util.Optional;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class BertEmbeddingsIndexServiceTest {

    @Mock
    private BertEmbeddingsService bertEmbeddingsService;

    @Mock
    private BertEmbeddingsIndexRepository indexRepository;

    @Mock
    private CipherService cipherService;

    @Mock
    private LuceneAutocompleteService luceneAutocompleteService;

    @Captor
    private ArgumentCaptor<BertEmbeddingsIndexEntity> indexEntityCaptor;

    @Captor
    private ArgumentCaptor<byte[]> bytesCaptor;

    private BertEmbeddingsStore bertEmbeddingsStore;
    private BertEmbeddingsStoreFactory bertEmbeddingsStoreFactory;
    private BertEmbeddingsIndexService service;

    @BeforeEach
    void setUp() {
        bertEmbeddingsStore = new HashMapBertEmbeddingsStore();
        bertEmbeddingsStoreFactory = new BertEmbeddingsStoreFactory(
                java.util.List.of(bertEmbeddingsStore),
                "hashmap"
        );
        // Chunking desactive: le contenu est indexe en un seul chunk (sans tokenizer),
        // ce qui garde ce test unitaire focalise sur la logique d'indexation/serialisation.
        fr.vvlabs.recherche.service.index.embeddings.chunk.TextChunker textChunker =
                new fr.vvlabs.recherche.service.index.embeddings.chunk.TextChunker(bertEmbeddingsService, false, 256, 32);
        service = new BertEmbeddingsIndexService(
                bertEmbeddingsService,
                bertEmbeddingsStoreFactory,
                indexRepository,
                cipherService,
                luceneAutocompleteService,
                textChunker
        );
    }

    @Test
    void addDocumentToDocumentIndex_upsertsInMemoryStore() {
        DocumentDTO dto = new DocumentDTO()
                .setId(42L)
                .setTitre("Titre")
                .setAuteur("Auteur")
                .setCategorie("rapport")
                .setNomFichier("doc.pdf")
                .setDepotDateTime(LocalDateTime.of(2025, 12, 24, 10, 30, 15));

        when(bertEmbeddingsService.buildIndexText("Titre", "Auteur", "rapport", "doc.pdf", "contenu"))
                .thenReturn("Titre\n\nAuteur\n\nrapport\n\ndoc.pdf\n\ncontenu");
        when(bertEmbeddingsService.generateEmbedding("Titre\n\nAuteur\n\nrapport\n\ndoc.pdf\n\ncontenu"))
                .thenReturn(new float[]{1.0f, 2.0f});

        service.addDocumentToDocumentIndex(dto, "contenu");

        assertThat(bertEmbeddingsStore.count()).isEqualTo(1);
        BertEmbeddingDocument stored = bertEmbeddingsStore.findAll().getFirst();
        assertThat(stored.documentId()).isEqualTo(42L);
        assertThat(stored.title()).isEqualTo("Titre");
        assertThat(stored.author()).isEqualTo("Auteur");
        assertThat(stored.category()).isEqualTo("rapport");
        assertThat(stored.filename()).isEqualTo("doc.pdf");
        assertThat(stored.contentText()).isEqualTo("Titre\n\nAuteur\n\nrapport\n\ndoc.pdf\n\ncontenu");
        assertThat(stored.embedding()).containsExactly(1.0f, 2.0f);
    }

    @Test
    void failedEmbeddingDoesNotPurgePreviouslyIndexedDocument() {
        var original = new BertEmbeddingDocument(42L, "Original", "", "rapport", "doc.pdf",
                null, "original text", new float[]{1, 0});
        bertEmbeddingsStore.upsert(original);
        when(bertEmbeddingsService.buildIndexText("Updated", null, null, null, "updated text"))
                .thenReturn("updated text");
        when(bertEmbeddingsService.generateEmbedding("updated text"))
                .thenThrow(new IllegalStateException("inference failed"));
        assertThatThrownBy(() -> service.addDocumentToDocumentIndex(
                new DocumentDTO().setId(42L).setTitre("Updated"), "updated text"))
                .isInstanceOf(IllegalStateException.class);
        assertThat(bertEmbeddingsStore.findAll()).containsExactly(original);
    }

    @Test
    void snapshotWaitsForConcurrentDocumentIndexingToComplete() throws Exception {
        ReflectionTestUtils.setField(service, "useDatabase", true);
        CountDownLatch generating = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        CountDownLatch saving = new CountDownLatch(1);
        when(bertEmbeddingsService.buildIndexText("Title", null, null, null, "text")).thenReturn("text");
        when(bertEmbeddingsService.generateEmbedding("text")).thenAnswer(invocation -> {
            generating.countDown();
            if (!release.await(5, TimeUnit.SECONDS)) {
                throw new IllegalStateException("Test indexing timeout");
            }
            return new float[]{1, 0};
        });
        when(indexRepository.findByIndexName("bert_embeddings")).thenReturn(Optional.empty());
        when(cipherService.encrypt(any(byte[].class))).thenReturn(new byte[]{1});
        when(bertEmbeddingsService.serialize(any(float[].class))).thenReturn(new byte[]{1, 2});
        try (var executor = Executors.newFixedThreadPool(2)) {
            var indexing = executor.submit(() -> service.addDocumentToDocumentIndex(
                    new DocumentDTO().setId(42L).setTitre("Title"), "text"));
            assertThat(generating.await(5, TimeUnit.SECONDS)).isTrue();
            var snapshot = executor.submit(() -> {
                saving.countDown();
                service.saveDocumentIndexToDatabase();
                return null;
            });
            try {
                assertThat(saving.await(5, TimeUnit.SECONDS)).isTrue();
                assertThatThrownBy(() -> snapshot.get(100, TimeUnit.MILLISECONDS))
                        .isInstanceOf(TimeoutException.class);
                verifyNoInteractions(indexRepository);
            } finally {
                release.countDown();
            }
            indexing.get(5, TimeUnit.SECONDS);
            snapshot.get(5, TimeUnit.SECONDS);
        }
        verify(indexRepository).save(indexEntityCaptor.capture());
        assertThat(indexEntityCaptor.getValue().getDocumentCount()).isEqualTo(1L);
    }

    @Test
    void loadDocumentIndexFromDatabase_whenDisabled_clearsStore() throws Exception {
        bertEmbeddingsStore.upsert(new BertEmbeddingDocument(1L, "", "", "", "", null, null, new float[0]));
        ReflectionTestUtils.setField(service, "useDatabase", false);

        service.loadDocumentIndexFromDatabase();

        assertThat(bertEmbeddingsStore.count()).isZero();
        verifyNoInteractions(indexRepository);
        verifyNoInteractions(cipherService);
    }

    @Test
    void loadDocumentIndexFromDatabase_whenFound_rehydratesStore() throws Exception {
        ReflectionTestUtils.setField(service, "useDatabase", true);
        byte[] payload = serializeEmbedding(
                42L,
                "Titre",
                "Auteur",
                "rapport",
                "doc.pdf",
                LocalDateTime.of(2025, 12, 24, 10, 30, 15),
                "contenu",
                new byte[]{7, 8, 9, 10, 11, 12, 13, 14}
        );

        BertEmbeddingsIndexEntity entity = new BertEmbeddingsIndexEntity()
                .setIndexName("bert_embeddings")
                .setIndexData(new byte[]{1, 2, 3})
                .setDocumentCount(1L);

        when(indexRepository.findByIndexName("bert_embeddings")).thenReturn(Optional.of(entity));
        when(cipherService.decrypt(entity.getIndexData())).thenReturn(payload);
        when(bertEmbeddingsService.deserialize(new byte[]{7, 8, 9, 10, 11, 12, 13, 14}))
                .thenReturn(new float[]{7.0f, 8.0f});

        service.loadDocumentIndexFromDatabase();

        assertThat(bertEmbeddingsStore.count()).isEqualTo(1);
        BertEmbeddingDocument stored = bertEmbeddingsStore.findAll().getFirst();
        assertThat(stored.documentId()).isEqualTo(42L);
        assertThat(stored.title()).isEqualTo("Titre");
        assertThat(stored.contentText()).isEqualTo("contenu");
        assertThat(stored.embedding()).containsExactly(7.0f, 8.0f);
        verify(cipherService).decrypt(entity.getIndexData());
    }

    @Test
    void saveDocumentIndexToDatabase_persistsEncryptedSnapshot() throws Exception {
        ReflectionTestUtils.setField(service, "useDatabase", true);
        bertEmbeddingsStore.upsert(new BertEmbeddingDocument(
                42L,
                "Titre",
                "Auteur",
                "rapport",
                "doc.pdf",
                LocalDateTime.of(2025, 12, 24, 10, 30, 15),
                "contenu",
                new float[]{7.0f, 8.0f}
        ));

        when(indexRepository.findByIndexName("bert_embeddings")).thenReturn(Optional.empty());
        when(cipherService.encrypt(any(byte[].class))).thenReturn(new byte[]{9, 9, 9});
        when(bertEmbeddingsService.serialize(any(float[].class))).thenReturn(new byte[]{7, 8, 9});

        service.saveDocumentIndexToDatabase();

        verify(cipherService).encrypt(bytesCaptor.capture());
        assertThat(bytesCaptor.getValue().length).isGreaterThan(0);
        try (var input = new DataInputStream(new ByteArrayInputStream(bytesCaptor.getValue()))) {
            assertThat(input.readInt()).isEqualTo(0x42455254);
            assertThat(input.readInt()).isEqualTo(1);
            assertThat(input.readInt()).isEqualTo(1);
        }

        verify(indexRepository).save(indexEntityCaptor.capture());
        BertEmbeddingsIndexEntity saved = indexEntityCaptor.getValue();
        assertThat(saved.getIndexName()).isEqualTo("bert_embeddings");
        assertThat(saved.getIndexData()).isEqualTo(new byte[]{9, 9, 9});
        assertThat(saved.getDocumentCount()).isEqualTo(1L);
        assertThat(saved.getLastUpdated()).isNotNull();
    }

    @Test
    void clearDocumentsIndex_clearsStore() {
        bertEmbeddingsStore.upsert(new BertEmbeddingDocument(1L, "", "", "", "", null, null, new float[0]));

        service.clearDocumentsIndex();

        assertThat(bertEmbeddingsStore.count()).isZero();
    }

    @Test
    void legacySnapshotIsRejectedBeforeReplacingTheStore() throws Exception {
        byte[] payload = serializeEmbedding(42L, "Titre", "Auteur", "rapport", "doc.pdf",
                null, "contenu", new byte[8]);
        assertSnapshotRejected(java.util.Arrays.copyOfRange(payload, 8, payload.length));
    }

    @Test
    void unknownSnapshotVersionIsRejectedBeforeReplacingTheStore() throws Exception {
        try (var output = new ByteArrayOutputStream(); var data = new DataOutputStream(output)) {
            data.writeInt(0x42455254);
            data.writeInt(2);
            data.writeInt(0);
            assertSnapshotRejected(output.toByteArray());
        }
    }

    @Test
    void truncatedSnapshotIsRejectedBeforeReplacingTheStore() throws Exception {
        byte[] payload = serializeEmbedding(42L, "Titre", "Auteur", "rapport", "doc.pdf",
                null, "contenu", new byte[8]);
        assertSnapshotRejected(java.util.Arrays.copyOf(payload, payload.length - 1));
    }

    @Test
    void oversizedSnapshotFieldIsRejectedWithoutAllocatingItsDeclaredLength() throws Exception {
        try (var output = new ByteArrayOutputStream(); var data = new DataOutputStream(output)) {
            data.writeInt(0x42455254);
            data.writeInt(1);
            data.writeInt(1);
            data.writeLong(42L);
            data.writeInt(0);
            data.writeInt(1);
            data.writeInt(Integer.MAX_VALUE);
            assertSnapshotRejected(output.toByteArray());
        }
    }

    @Test
    void repositoryFailureIsNotTreatedAsAnEmptySnapshot() {
        ReflectionTestUtils.setField(service, "useDatabase", true);
        var original = new BertEmbeddingDocument(42L, "Original", "", "rapport", "doc.pdf",
                null, "original text", new float[]{1, 0});
        bertEmbeddingsStore.upsert(original);
        when(indexRepository.findByIndexName("bert_embeddings")).thenThrow(new IllegalStateException("Database unavailable"));

        assertThatThrownBy(service::loadDocumentIndexFromDatabase)
                .isInstanceOf(IllegalStateException.class).hasMessage("Database unavailable");
        assertThat(bertEmbeddingsStore.findAll()).containsExactly(original);
        verifyNoInteractions(cipherService);
    }

    private void assertSnapshotRejected(byte[] payload) throws Exception {
        ReflectionTestUtils.setField(service, "useDatabase", true);
        var original = new BertEmbeddingDocument(42L, "Original", "", "rapport", "doc.pdf",
                null, "original text", new float[]{1, 0});
        bertEmbeddingsStore.upsert(original);
        var entity = new BertEmbeddingsIndexEntity().setIndexName("bert_embeddings").setIndexData(new byte[]{1});
        when(indexRepository.findByIndexName("bert_embeddings")).thenReturn(Optional.of(entity));
        when(cipherService.decrypt(entity.getIndexData())).thenReturn(payload);

        assertThatThrownBy(service::loadDocumentIndexFromDatabase)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Snapshot BERT invalide ou incompatible")
                .hasCauseInstanceOf(IOException.class);
        assertThat(bertEmbeddingsStore.findAll()).containsExactly(original);
        verifyNoInteractions(bertEmbeddingsService);
    }

    private static byte[] serializeEmbedding(
            long documentId,
            String title,
            String author,
            String category,
            String filename,
            LocalDateTime depotDateTime,
            String contentText,
            byte[] embeddingData
    ) throws Exception {
        try (ByteArrayOutputStream baos = new ByteArrayOutputStream();
             DataOutputStream dos = new DataOutputStream(baos)) {
            dos.writeInt(0x42455254);
            dos.writeInt(1);
            dos.writeInt(1);
            dos.writeLong(documentId);
            dos.writeInt(0); // chunkIndex
            dos.writeInt(1); // chunkCount
            writeString(dos, title);
            writeString(dos, author);
            writeString(dos, category);
            writeString(dos, filename);
            dos.writeBoolean(depotDateTime != null);
            if (depotDateTime != null) {
                writeString(dos, depotDateTime.toString());
            }
            dos.writeBoolean(contentText != null);
            if (contentText != null) {
                writeString(dos, contentText);
            }
            dos.writeInt(embeddingData.length);
            dos.write(embeddingData);
            return baos.toByteArray();
        }
    }

    private static void writeString(DataOutputStream dos, String value) throws Exception {
        byte[] bytes = value.getBytes(StandardCharsets.UTF_8);
        dos.writeInt(bytes.length);
        dos.write(bytes);
    }
}
