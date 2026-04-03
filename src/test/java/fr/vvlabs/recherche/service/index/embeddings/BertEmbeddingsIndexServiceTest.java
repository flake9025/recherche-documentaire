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
import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
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
        service = new BertEmbeddingsIndexService(
                bertEmbeddingsService,
                bertEmbeddingsStoreFactory,
                indexRepository,
                cipherService,
                luceneAutocompleteService
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

        when(bertEmbeddingsService.buildIndexText("Titre", "contenu")).thenReturn("Titre\n\ncontenu");
        when(bertEmbeddingsService.generateEmbedding("Titre\n\ncontenu")).thenReturn(new float[]{1.0f, 2.0f});

        service.addDocumentToDocumentIndex(dto, "contenu");

        assertThat(bertEmbeddingsStore.count()).isEqualTo(1);
        BertEmbeddingDocument stored = bertEmbeddingsStore.findAll().getFirst();
        assertThat(stored.documentId()).isEqualTo(42L);
        assertThat(stored.title()).isEqualTo("Titre");
        assertThat(stored.author()).isEqualTo("Auteur");
        assertThat(stored.category()).isEqualTo("rapport");
        assertThat(stored.filename()).isEqualTo("doc.pdf");
        assertThat(stored.contentText()).isEqualTo("contenu");
        assertThat(stored.embedding()).containsExactly(1.0f, 2.0f);
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
                new byte[]{7, 8, 9}
        );

        BertEmbeddingsIndexEntity entity = new BertEmbeddingsIndexEntity()
                .setIndexName("bert_embeddings")
                .setIndexData(new byte[]{1, 2, 3})
                .setDocumentCount(1L);

        when(indexRepository.findByIndexName("bert_embeddings")).thenReturn(Optional.of(entity));
        when(cipherService.decrypt(entity.getIndexData())).thenReturn(payload);
        when(bertEmbeddingsService.deserialize(new byte[]{7, 8, 9})).thenReturn(new float[]{7.0f, 8.0f});

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
            dos.writeInt(1);
            dos.writeLong(documentId);
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
