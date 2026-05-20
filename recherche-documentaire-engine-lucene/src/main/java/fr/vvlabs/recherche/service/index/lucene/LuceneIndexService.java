package fr.vvlabs.recherche.service.index.lucene;

import fr.vvlabs.recherche.config.LuceneConfig;
import fr.vvlabs.recherche.dto.DocumentDTO;
import fr.vvlabs.recherche.model.IndexEntity;
import fr.vvlabs.recherche.repository.IndexRepository;
import fr.vvlabs.recherche.service.index.IndexService;
import fr.vvlabs.recherche.service.index.IndexType;
import fr.vvlabs.recherche.service.cipher.CipherService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.apache.lucene.document.Document;
import org.apache.lucene.document.Field;
import org.apache.lucene.document.TextField;
import org.apache.lucene.index.IndexWriter;
import org.apache.lucene.index.IndexWriterConfig;
import org.apache.lucene.index.Term;
import org.apache.lucene.store.ByteBuffersDirectory;
import org.apache.lucene.store.IOContext;
import org.apache.lucene.store.IndexInput;
import org.apache.lucene.store.IndexOutput;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;

import java.io.*;
import java.time.Duration;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.util.Locale;
import java.util.Optional;

import static fr.vvlabs.recherche.config.IndexConstants.*;

@Service
@ConditionalOnProperty(name = "app.indexer.default", havingValue = IndexType.LUCENE)
@RequiredArgsConstructor
@Slf4j
public class LuceneIndexService implements IndexService<ByteBuffersDirectory> {

    private final IndexRepository indexRepository;
    private final LuceneConfig luceneConfig;
    private final CipherService cipherService;
    private final LuceneAutocompleteService luceneAutocompleteService;

    private static final String indexName = LuceneConfig.DEFAULT_INDEX;
    private static final DateTimeFormatter formatter = DateTimeFormatter.ofPattern("dd/MM/yyyy HH:mm:ss", Locale.FRANCE);

    @Value("${app.indexer.use-database}")
    private boolean useDatabase;

    @Override
    public String getType() { return IndexType.LUCENE; }

    @Override
    public void addDocumentToDocumentIndex(DocumentDTO documentDTO, String data) throws IOException {
        log.info("Upsert doc {} in index", documentDTO.getId());

        IndexWriterConfig config = new IndexWriterConfig(luceneConfig.getDocumentsAnalyzer());
        synchronized (luceneConfig.getDocumentsIndexLock()) {
            try (IndexWriter writer = new IndexWriter(luceneConfig.getDocumentsIndex(), config)) {
                // 1. DELETE ancien (si existe)
                Term idTerm = new Term(INDEX_KEY_ID, documentDTO.getId().toString());
                writer.deleteDocuments(idTerm);

                // 2. ADD nouveau
                Document doc = new Document();
                doc.add(new TextField(INDEX_KEY_ID, documentDTO.getId().toString(), Field.Store.YES));  // TextField partout !
                doc.add(new TextField(INDEX_KEY_NAME, documentDTO.getTitre(), Field.Store.YES));
                doc.add(new TextField(INDEX_KEY_AUTEUR, documentDTO.getAuteur(), Field.Store.YES));
                doc.add(new TextField(INDEX_KEY_CATEGORIE, documentDTO.getCategorie(), Field.Store.YES));
                doc.add(new TextField(INDEX_KEY_FICHIER, documentDTO.getNomFichier(), Field.Store.YES));
                doc.add(new TextField(INDEX_KEY_DATE_DEPOT, documentDTO.getDepotDateTime().format(formatter), Field.Store.YES));
                doc.add(new TextField(INDEX_KEY_CONTENT, data, Field.Store.YES));

                writer.addDocument(doc);
                writer.commit();
            }
        }
    }


    public void addAuthorToAucompleteIndex(String auteur) {
        // Mettre Ã  jour l'index d'autocomplÃ©tion pour l'auteur
        try {
            if (StringUtils.isNotBlank(auteur)) {
                // On ajoute l'auteur avec un poids de 1
                // Si l'auteur existe dÃ©jÃ , le poids sera cumulÃ© lors du rebuild
                luceneAutocompleteService.addAuthor(auteur, 1L);
                luceneAutocompleteService.refresh();
                log.debug("Author '{}' added to autocomplete index", auteur);
            }
        } catch (Exception e) {
            log.error("Failed to update author autocomplete for '{}': {}", auteur, e.getMessage());
        }
    }

    @Override
    public ByteBuffersDirectory loadDocumentIndexFromDatabase() throws Exception {
        if(!useDatabase) {
            log.debug("Indexer Database disabled, Creating empty index ...");
            return new ByteBuffersDirectory();
        }
        log.debug("Loading index from Database ...");

        LocalTime t1 = LocalTime.now();
        Optional<IndexEntity> entityOpt = Optional.empty();
        try {
            entityOpt = indexRepository.findByIndexName(indexName);
        } catch (Exception e) {
            log.warn("Loading index from Database KO : {}", e.getMessage());
        }
        if (entityOpt.isEmpty()) {
            log.debug("Index not found, creating empty index ...");
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

        log.info("Index {} with {} documents loaded from database", indexName, entityOpt.get().getDocumentCount());
        LocalTime t2 = LocalTime.now();
        Duration d = Duration.between(t1, t2);
        log.info("Millis Ã©coulÃ©s pour le chargement : {}" , d.toMillis());
        return directory;
    }

    @Override
    public void saveDocumentIndexToDatabase() throws Exception {
        if(!useDatabase) {
            log.debug("Indexer Database disabled, save skipped");
            return;
        }

        ByteArrayOutputStream baos = new ByteArrayOutputStream();

        // Serialize all index files
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

        IndexEntity entity = indexRepository.findByIndexName(indexName).orElse(new IndexEntity());
        entity.setIndexName(indexName);
        entity.setIndexData(cipherService.encrypt(baos.toByteArray()));

        entity.setDocumentCount(entity.getDocumentCount()+1);
        entity.setLastUpdated(LocalDateTime.now());

        indexRepository.save(entity);
        log.info("Index {} save to database", indexName);
    }


    /**
     * Supprime tous les documents de l'index
     */
    @Override
    public void clearDocumentsIndex() throws IOException {
        IndexWriterConfig config = new IndexWriterConfig(luceneConfig.getDocumentsAnalyzer());
        synchronized (luceneConfig.getDocumentsIndexLock()) {
            try (IndexWriter writer = new IndexWriter(luceneConfig.getDocumentsIndex(), config)) {
                writer.deleteAll();
                writer.commit();
                log.info("Lucene index cleared");
            } catch (Exception e) {
                log.error("Failed to clear lucene index : {}", e.getMessage(), e);
            }
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

