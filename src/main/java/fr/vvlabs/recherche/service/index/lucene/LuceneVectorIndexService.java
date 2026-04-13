package fr.vvlabs.recherche.service.index.lucene;

import fr.vvlabs.recherche.config.LuceneConfig;
import fr.vvlabs.recherche.dto.DocumentDTO;
import fr.vvlabs.recherche.model.LuceneVectorIndexEntity;
import fr.vvlabs.recherche.repository.LuceneVectorIndexRepository;
import fr.vvlabs.recherche.service.cipher.CipherService;
import fr.vvlabs.recherche.service.index.IndexService;
import fr.vvlabs.recherche.service.index.IndexType;
import fr.vvlabs.recherche.service.index.embeddings.BertEmbeddingsService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.apache.lucene.document.Document;
import org.apache.lucene.document.Field;
import org.apache.lucene.document.KnnFloatVectorField;
import org.apache.lucene.document.TextField;
import org.apache.lucene.index.IndexWriter;
import org.apache.lucene.index.IndexWriterConfig;
import org.apache.lucene.index.Term;
import org.apache.lucene.index.VectorSimilarityFunction;
import org.apache.lucene.store.ByteBuffersDirectory;
import org.apache.lucene.store.IOContext;
import org.apache.lucene.store.IndexInput;
import org.apache.lucene.store.IndexOutput;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Locale;
import java.util.Optional;

import static fr.vvlabs.recherche.config.IndexConstants.INDEX_KEY_AUTEUR;
import static fr.vvlabs.recherche.config.IndexConstants.INDEX_KEY_CATEGORIE;
import static fr.vvlabs.recherche.config.IndexConstants.INDEX_KEY_CONTENT;
import static fr.vvlabs.recherche.config.IndexConstants.INDEX_KEY_DATE_DEPOT;
import static fr.vvlabs.recherche.config.IndexConstants.INDEX_KEY_FICHIER;
import static fr.vvlabs.recherche.config.IndexConstants.INDEX_KEY_ID;
import static fr.vvlabs.recherche.config.IndexConstants.INDEX_KEY_NAME;

@Service
@ConditionalOnProperty(name = "app.indexer.default", havingValue = IndexType.LUCENE_VECTOR)
@RequiredArgsConstructor
@Slf4j
public class LuceneVectorIndexService implements IndexService<ByteBuffersDirectory> {

    public static final String INDEX_NAME = "lucene_vector_index";
    public static final String VECTOR_FIELD = "embedding_vector";
    private static final DateTimeFormatter FORMATTER = DateTimeFormatter.ofPattern("dd/MM/yyyy HH:mm:ss", Locale.FRANCE);

    private final LuceneVectorIndexRepository indexRepository;
    private final LuceneConfig luceneConfig;
    private final CipherService cipherService;
    private final LuceneAutocompleteService luceneAutocompleteService;
    private final BertEmbeddingsService bertEmbeddingsService;

    @Value("${app.indexer.use-database}")
    private boolean useDatabase;

    @Override
    public String getType() {
        return IndexType.LUCENE_VECTOR;
    }

    @Override
    public void addDocumentToDocumentIndex(DocumentDTO documentDTO, String data) throws IOException {
        log.info("Upsert doc {} in Lucene vector index", documentDTO.getId());

        String indexedText = bertEmbeddingsService.buildIndexText(
                documentDTO.getTitre(),
                documentDTO.getAuteur(),
                documentDTO.getCategorie(),
                documentDTO.getNomFichier(),
                data
        );
        float[] vector = bertEmbeddingsService.generateEmbedding(indexedText);

        IndexWriterConfig config = new IndexWriterConfig(luceneConfig.getDocumentsAnalyzer());
        synchronized (luceneConfig.getDocumentsIndexLock()) {
            try (IndexWriter writer = new IndexWriter(luceneConfig.getDocumentsIndex(), config)) {
                writer.deleteDocuments(new Term(INDEX_KEY_ID, documentDTO.getId().toString()));

                Document doc = new Document();
                doc.add(new TextField(INDEX_KEY_ID, documentDTO.getId().toString(), Field.Store.YES));
                doc.add(new TextField(INDEX_KEY_NAME, documentDTO.getTitre(), Field.Store.YES));
                doc.add(new TextField(INDEX_KEY_AUTEUR, documentDTO.getAuteur(), Field.Store.YES));
                doc.add(new TextField(INDEX_KEY_CATEGORIE, documentDTO.getCategorie(), Field.Store.YES));
                doc.add(new TextField(INDEX_KEY_FICHIER, documentDTO.getNomFichier(), Field.Store.YES));
                doc.add(new TextField(INDEX_KEY_DATE_DEPOT, documentDTO.getDepotDateTime().format(FORMATTER), Field.Store.YES));
                doc.add(new TextField(INDEX_KEY_CONTENT, data, Field.Store.YES));
                doc.add(new KnnFloatVectorField(VECTOR_FIELD, vector, VectorSimilarityFunction.COSINE));

                writer.addDocument(doc);
                writer.commit();
            }
        }
    }

    @Override
    public void addAuthorToAucompleteIndex(String author) {
        try {
            if (StringUtils.isNotBlank(author)) {
                luceneAutocompleteService.addAuthor(author, 1L);
                luceneAutocompleteService.refresh();
            }
        } catch (Exception e) {
            log.error("Failed to update author autocomplete for '{}': {}", author, e.getMessage(), e);
        }
    }

    @Override
    public ByteBuffersDirectory loadDocumentIndexFromDatabase() throws Exception {
        if (!useDatabase) {
            log.debug("Indexer Database disabled, creating empty Lucene vector index ...");
            return new ByteBuffersDirectory();
        }

        log.debug("Loading Lucene vector index from Database ...");
        Optional<LuceneVectorIndexEntity> entityOpt = Optional.empty();
        try {
            entityOpt = indexRepository.findByIndexName(INDEX_NAME);
        } catch (Exception e) {
            log.warn("Loading Lucene vector index from Database KO : {}", e.getMessage());
        }
        if (entityOpt.isEmpty()) {
            log.debug("Lucene vector index not found, creating empty index ...");
            return new ByteBuffersDirectory();
        }

        ByteBuffersDirectory directory = new ByteBuffersDirectory();
        byte[] indexData = cipherService.decrypt(entityOpt.get().getIndexData());

        try (DataInputStream dis = new DataInputStream(new ByteArrayInputStream(indexData))) {
            int fileCount = dis.readInt();
            for (int i = 0; i < fileCount; i++) {
                String fileName = dis.readUTF();
                int fileSize = dis.readInt();
                byte[] fileData = new byte[fileSize];
                dis.readFully(fileData);
                writeFileToDirectory(directory, fileName, fileData);
            }
        }

        log.info("Lucene vector index {} with {} documents loaded from database", INDEX_NAME, entityOpt.get().getDocumentCount());
        return directory;
    }

    @Override
    public void saveDocumentIndexToDatabase() throws Exception {
        if (!useDatabase) {
            log.debug("Indexer Database disabled, save skipped");
            return;
        }

        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        synchronized (luceneConfig.getDocumentsIndexLock()) {
            String[] files = luceneConfig.getDocumentsIndex().listAll();
            try (DataOutputStream dos = new DataOutputStream(baos)) {
                dos.writeInt(files.length);
                for (String fileName : files) {
                    byte[] fileData = readFileFromDirectory(luceneConfig.getDocumentsIndex(), fileName);
                    dos.writeUTF(fileName);
                    dos.writeInt(fileData.length);
                    dos.write(fileData);
                }
            }
        }

        LuceneVectorIndexEntity entity = indexRepository.findByIndexName(INDEX_NAME).orElse(new LuceneVectorIndexEntity());
        entity.setIndexName(INDEX_NAME);
        entity.setIndexData(cipherService.encrypt(baos.toByteArray()));
        entity.setDocumentCount(countIndexedDocuments());
        entity.setLastUpdated(LocalDateTime.now());

        indexRepository.save(entity);
        log.info("Lucene vector index {} saved to database", INDEX_NAME);
    }

    @Override
    public void clearDocumentsIndex() throws IOException {
        IndexWriterConfig config = new IndexWriterConfig(luceneConfig.getDocumentsAnalyzer());
        synchronized (luceneConfig.getDocumentsIndexLock()) {
            try (IndexWriter writer = new IndexWriter(luceneConfig.getDocumentsIndex(), config)) {
                writer.deleteAll();
                writer.commit();
            }
        }
        log.info("Lucene vector index cleared");
    }

    private long countIndexedDocuments() throws IOException {
        if (!org.apache.lucene.index.DirectoryReader.indexExists(luceneConfig.getDocumentsIndex())) {
            return 0L;
        }
        try (org.apache.lucene.index.DirectoryReader reader = org.apache.lucene.index.DirectoryReader.open(luceneConfig.getDocumentsIndex())) {
            return reader.numDocs();
        }
    }

    private byte[] readFileFromDirectory(ByteBuffersDirectory directory, String fileName) throws IOException {
        try (IndexInput input = directory.openInput(fileName, IOContext.DEFAULT)) {
            byte[] data = new byte[(int) input.length()];
            input.readBytes(data, 0, data.length);
            return data;
        }
    }

    private void writeFileToDirectory(ByteBuffersDirectory directory, String fileName, byte[] data) throws IOException {
        try (IndexOutput output = directory.createOutput(fileName, IOContext.DEFAULT)) {
            output.writeBytes(data, 0, data.length);
        }
    }
}
