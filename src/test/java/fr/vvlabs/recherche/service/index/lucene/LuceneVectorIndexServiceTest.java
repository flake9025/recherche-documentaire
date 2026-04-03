package fr.vvlabs.recherche.service.index.lucene;

import fr.vvlabs.recherche.config.LuceneConfig;
import fr.vvlabs.recherche.dto.DocumentDTO;
import fr.vvlabs.recherche.model.LuceneVectorIndexEntity;
import fr.vvlabs.recherche.repository.LuceneVectorIndexRepository;
import fr.vvlabs.recherche.service.cipher.CipherService;
import fr.vvlabs.recherche.service.index.embeddings.BertEmbeddingsService;
import org.apache.lucene.analysis.standard.StandardAnalyzer;
import org.apache.lucene.document.Document;
import org.apache.lucene.index.DirectoryReader;
import org.apache.lucene.index.FloatVectorValues;
import org.apache.lucene.store.ByteBuffersDirectory;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Captor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.LocalDateTime;
import java.util.Optional;

import static fr.vvlabs.recherche.config.IndexConstants.INDEX_KEY_CONTENT;
import static fr.vvlabs.recherche.config.IndexConstants.INDEX_KEY_ID;
import static fr.vvlabs.recherche.service.index.lucene.LuceneVectorIndexService.INDEX_NAME;
import static fr.vvlabs.recherche.service.index.lucene.LuceneVectorIndexService.VECTOR_FIELD;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class LuceneVectorIndexServiceTest {

    @Mock
    private LuceneVectorIndexRepository indexRepository;

    @Mock
    private LuceneConfig luceneConfig;

    @Mock
    private CipherService cipherService;

    @Mock
    private LuceneAutocompleteService luceneAutocompleteService;

    @Mock
    private BertEmbeddingsService bertEmbeddingsService;

    @Captor
    private ArgumentCaptor<LuceneVectorIndexEntity> indexEntityCaptor;

    private StandardAnalyzer analyzer;
    private ByteBuffersDirectory directory;
    private LuceneVectorIndexService service;

    @BeforeEach
    void setUp() {
        analyzer = new StandardAnalyzer();
        directory = new ByteBuffersDirectory();
        lenient().when(luceneConfig.getDocumentsAnalyzer()).thenReturn(analyzer);
        lenient().when(luceneConfig.getDocumentsIndex()).thenReturn(directory);
        lenient().when(luceneConfig.getDocumentsIndexLock()).thenReturn(new Object());
        service = new LuceneVectorIndexService(
                indexRepository,
                luceneConfig,
                cipherService,
                luceneAutocompleteService,
                bertEmbeddingsService
        );
    }

    @Test
    void addDocumentToDocumentIndex_writesStoredFieldsAndVector() throws Exception {
        DocumentDTO dto = new DocumentDTO()
                .setId(42L)
                .setTitre("Titre")
                .setAuteur("Auteur")
                .setCategorie("rapport")
                .setNomFichier("doc.pdf")
                .setDepotDateTime(LocalDateTime.of(2025, 12, 24, 10, 30, 15));

        when(bertEmbeddingsService.buildIndexText("Titre", "contenu")).thenReturn("Titre\n\ncontenu");
        when(bertEmbeddingsService.generateEmbedding("Titre\n\ncontenu")).thenReturn(new float[]{1.0f, 2.0f, 3.0f});

        service.addDocumentToDocumentIndex(dto, "contenu");

        try (DirectoryReader reader = DirectoryReader.open(directory)) {
            assertThat(reader.numDocs()).isEqualTo(1);
            Document stored = reader.storedFields().document(0);
            assertThat(stored.get(INDEX_KEY_ID)).isEqualTo("42");
            assertThat(stored.get(INDEX_KEY_CONTENT)).isEqualTo("contenu");

            FloatVectorValues vectors = reader.leaves().getFirst().reader().getFloatVectorValues(VECTOR_FIELD);
            assertThat(vectors).isNotNull();
            assertThat(vectors.size()).isEqualTo(1);
            assertThat(vectors.dimension()).isEqualTo(3);
            assertThat(vectors.vectorValue(0)).containsExactly(1.0f, 2.0f, 3.0f);
        }
    }

    @Test
    void saveDocumentIndexToDatabase_persistsVectorIndexSnapshot() throws Exception {
        ReflectionTestUtils.setField(service, "useDatabase", true);
        DocumentDTO dto = new DocumentDTO()
                .setId(1L)
                .setTitre("Titre")
                .setAuteur("Auteur")
                .setCategorie("rapport")
                .setNomFichier("doc.pdf")
                .setDepotDateTime(LocalDateTime.of(2025, 12, 24, 10, 30, 15));

        when(bertEmbeddingsService.buildIndexText("Titre", "contenu")).thenReturn("Titre\n\ncontenu");
        when(bertEmbeddingsService.generateEmbedding("Titre\n\ncontenu")).thenReturn(new float[]{1.0f, 2.0f});
        service.addDocumentToDocumentIndex(dto, "contenu");

        when(indexRepository.findByIndexName(INDEX_NAME)).thenReturn(Optional.empty());
        when(cipherService.encrypt(any(byte[].class))).thenReturn(new byte[]{9, 9, 9});

        service.saveDocumentIndexToDatabase();

        verify(indexRepository).save(indexEntityCaptor.capture());
        LuceneVectorIndexEntity saved = indexEntityCaptor.getValue();
        assertThat(saved.getIndexName()).isEqualTo(INDEX_NAME);
        assertThat(saved.getIndexData()).isEqualTo(new byte[]{9, 9, 9});
        assertThat(saved.getDocumentCount()).isEqualTo(1L);
    }

    @Test
    void loadDocumentIndexFromDatabase_whenDisabled_returnsEmptyIndex() throws Exception {
        ReflectionTestUtils.setField(service, "useDatabase", false);

        ByteBuffersDirectory result = service.loadDocumentIndexFromDatabase();

        assertThat(result.listAll()).isEmpty();
        verifyNoInteractions(indexRepository);
        verifyNoInteractions(cipherService);
    }
}
