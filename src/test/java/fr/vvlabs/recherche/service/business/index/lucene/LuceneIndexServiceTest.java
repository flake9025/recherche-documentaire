package fr.vvlabs.recherche.service.business.index.lucene;

import fr.vvlabs.recherche.config.LuceneConfig;
import fr.vvlabs.recherche.dto.DocumentDTO;
import fr.vvlabs.recherche.model.IndexEntity;
import fr.vvlabs.recherche.repository.IndexRepository;
import fr.vvlabs.recherche.service.cipher.CipherService;
import org.apache.lucene.analysis.standard.StandardAnalyzer;
import org.apache.lucene.document.Document;
import org.apache.lucene.document.Field;
import org.apache.lucene.document.StringField;
import org.apache.lucene.document.TextField;
import org.apache.lucene.index.DirectoryReader;
import org.apache.lucene.index.IndexWriter;
import org.apache.lucene.index.IndexWriterConfig;
import org.apache.lucene.store.ByteBuffersDirectory;
import org.apache.lucene.store.IOContext;
import org.apache.lucene.store.IndexInput;
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
import java.io.IOException;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Locale;
import java.util.Optional;

import static fr.vvlabs.recherche.config.IndexConstants.*;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class LuceneIndexServiceTest {

    private static final DateTimeFormatter FORMATTER = DateTimeFormatter.ofPattern("dd/MM/yyyy HH:mm:ss", Locale.FRANCE);

    @Mock
    private IndexRepository indexRepository;

    @Mock
    private LuceneConfig luceneConfig;

    @Mock
    private CipherService cipherService;

    @Mock
    private LuceneAutocompleteService luceneAutocompleteService;

    @Captor
    private ArgumentCaptor<IndexEntity> indexEntityCaptor;

    @Captor
    private ArgumentCaptor<byte[]> bytesCaptor;

    private StandardAnalyzer analyzer;
    private ByteBuffersDirectory directory;
    private LuceneIndexService service;

    @BeforeEach
    void setUp() {
        analyzer = new StandardAnalyzer();
        directory = new ByteBuffersDirectory();
        lenient().when(luceneConfig.getDocumentsAnalyzer()).thenReturn(analyzer);
        lenient().when(luceneConfig.getDocumentsIndex()).thenReturn(directory);
        lenient().when(luceneConfig.getDocumentsIndexLock()).thenReturn(new Object());
        service = new LuceneIndexService(indexRepository, luceneConfig, cipherService, luceneAutocompleteService);
    }

    @Test
    void addDocumentToDocumentIndex_writesStoredFields() throws Exception {
        DocumentDTO dto = new DocumentDTO()
                .setId(42L)
                .setTitre("Titre")
                .setAuteur("Auteur")
                .setCategorie("rapport")
                .setNomFichier("doc.pdf")
                .setDepotDateTime(LocalDateTime.of(2025, 12, 24, 10, 30, 15));

        service.addDocumentToDocumentIndex(dto, "contenu");

        try (DirectoryReader reader = DirectoryReader.open(directory)) {
            assertThat(reader.numDocs()).isEqualTo(1);
            Document stored = reader.storedFields().document(0);
            assertThat(stored.get(INDEX_KEY_ID)).isEqualTo("42");
            assertThat(stored.get(INDEX_KEY_NAME)).isEqualTo("Titre");
            assertThat(stored.get(INDEX_KEY_AUTEUR)).isEqualTo("Auteur");
            assertThat(stored.get(INDEX_KEY_CATEGORIE)).isEqualTo("rapport");
            assertThat(stored.get(INDEX_KEY_FICHIER)).isEqualTo("doc.pdf");
            assertThat(stored.get(INDEX_KEY_DATE_DEPOT)).isEqualTo(dto.getDepotDateTime().format(FORMATTER));
            assertThat(stored.get(INDEX_KEY_CONTENT)).isEqualTo("contenu");
        }
    }

    @Test
    void addAuthorToAucompleteIndex_withBlankAuthor_noInteractions() {
        service.addAuthorToAucompleteIndex("   ");

        verifyNoInteractions(luceneAutocompleteService);
    }

    @Test
    void addAuthorToAucompleteIndex_withAuthor_updatesAutocomplete() throws Exception {
        service.addAuthorToAucompleteIndex("Marie Curie");

        verify(luceneAutocompleteService).addAuthor("Marie Curie", 1L);
        verify(luceneAutocompleteService).refresh();
    }

    @Test
    void loadDocumentIndexFromDatabase_whenDisabled_returnsEmptyIndex() throws Exception {
        ReflectionTestUtils.setField(service, "useDatabase", false);

        ByteBuffersDirectory result = service.loadDocumentIndexFromDatabase();

        assertThat(result.listAll()).isEmpty();
        verifyNoInteractions(indexRepository);
        verifyNoInteractions(cipherService);
    }

    @Test
    void loadDocumentIndexFromDatabase_whenNotFound_returnsEmptyIndex() throws Exception {
        ReflectionTestUtils.setField(service, "useDatabase", true);
        when(indexRepository.findByIndexName(LuceneConfig.DEFAULT_INDEX)).thenReturn(Optional.empty());

        ByteBuffersDirectory result = service.loadDocumentIndexFromDatabase();

        assertThat(result.listAll()).isEmpty();
        verifyNoInteractions(cipherService);
    }

    @Test
    void loadDocumentIndexFromDatabase_whenFound_loadsIndex() throws Exception {
        ReflectionTestUtils.setField(service, "useDatabase", true);

        ByteBuffersDirectory sourceDirectory = new ByteBuffersDirectory();
        addSampleDocument(sourceDirectory, analyzer);
        byte[] payload = serializeDirectory(sourceDirectory);

        IndexEntity entity = new IndexEntity();
        entity.setIndexName(LuceneConfig.DEFAULT_INDEX);
        entity.setIndexData(new byte[]{1, 2, 3});
        entity.setDocumentCount(1L);

        when(indexRepository.findByIndexName(LuceneConfig.DEFAULT_INDEX)).thenReturn(Optional.of(entity));
        when(cipherService.decrypt(entity.getIndexData())).thenReturn(payload);

        ByteBuffersDirectory result = service.loadDocumentIndexFromDatabase();

        try (DirectoryReader reader = DirectoryReader.open(result)) {
            assertThat(reader.numDocs()).isEqualTo(1);
        }
        verify(cipherService).decrypt(entity.getIndexData());
    }

    @Test
    void saveDocumentIndexToDatabase_whenDisabled_skips() throws Exception {
        ReflectionTestUtils.setField(service, "useDatabase", false);

        service.saveDocumentIndexToDatabase();

        verifyNoInteractions(indexRepository);
        verifyNoInteractions(cipherService);
    }

    @Test
    void saveDocumentIndexToDatabase_persistsEncryptedIndex() throws Exception {
        ReflectionTestUtils.setField(service, "useDatabase", true);
        addSampleDocument(directory, analyzer);

        when(indexRepository.findByIndexName(LuceneConfig.DEFAULT_INDEX)).thenReturn(Optional.empty());
        when(cipherService.encrypt(any(byte[].class))).thenReturn(new byte[]{9, 9, 9});

        service.saveDocumentIndexToDatabase();

        verify(cipherService).encrypt(bytesCaptor.capture());
        assertThat(bytesCaptor.getValue().length).isGreaterThan(0);

        verify(indexRepository).save(indexEntityCaptor.capture());
        IndexEntity saved = indexEntityCaptor.getValue();
        assertThat(saved.getIndexName()).isEqualTo(LuceneConfig.DEFAULT_INDEX);
        assertThat(saved.getIndexData()).isEqualTo(new byte[]{9, 9, 9});
        assertThat(saved.getDocumentCount()).isEqualTo(1L);
        assertThat(saved.getLastUpdated()).isNotNull();
    }

    @Test
    void clearDocumentsIndex_deletesAllDocuments() throws Exception {
        addSampleDocument(directory, analyzer);

        service.clearDocumentsIndex();

        try (DirectoryReader reader = DirectoryReader.open(directory)) {
            assertThat(reader.numDocs()).isZero();
        }
    }

    private static void addSampleDocument(ByteBuffersDirectory target, StandardAnalyzer analyzer) throws IOException {
        IndexWriterConfig config = new IndexWriterConfig(analyzer);
        try (IndexWriter writer = new IndexWriter(target, config)) {
            Document doc = new Document();
            doc.add(new StringField("id", "1", Field.Store.YES));
            doc.add(new TextField("content", "sample", Field.Store.YES));
            writer.addDocument(doc);
            writer.commit();
        }
    }

    private static byte[] serializeDirectory(ByteBuffersDirectory source) throws IOException {
        String[] files = source.listAll();
        try (ByteArrayOutputStream baos = new ByteArrayOutputStream();
             DataOutputStream dos = new DataOutputStream(baos)) {
            dos.writeInt(files.length);
            for (String fileName : files) {
                byte[] fileData = readFileFromDirectory(source, fileName);
                dos.writeUTF(fileName);
                dos.writeInt(fileData.length);
                dos.write(fileData);
            }
            return baos.toByteArray();
        }
    }

    private static byte[] readFileFromDirectory(ByteBuffersDirectory directory, String fileName) throws IOException {
        try (IndexInput input = directory.openInput(fileName, IOContext.DEFAULT)) {
            byte[] data = new byte[(int) input.length()];
            input.readBytes(data, 0, data.length);
            return data;
        }
    }
}

