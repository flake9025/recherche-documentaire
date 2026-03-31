package fr.vvlabs.recherche.service.business.index.embeddings;

import fr.vvlabs.recherche.dto.DocumentDTO;
import fr.vvlabs.recherche.model.IndexEntity;
import fr.vvlabs.recherche.repository.IndexRepository;
import fr.vvlabs.recherche.service.business.index.IndexService;
import fr.vvlabs.recherche.service.business.index.IndexType;
import fr.vvlabs.recherche.service.business.index.lucene.LuceneAutocompleteService;
import fr.vvlabs.recherche.service.cipher.CipherService;
import io.micrometer.common.util.StringUtils;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

@Service
@RequiredArgsConstructor
@Slf4j
public class BertEmbeddingsIndexService implements IndexService<Void> {

    // Le snapshot complet du store BERT est persiste dans la meme table que Lucene,
    // mais avec un nom d'index dedie.
    private static final String INDEX_NAME = "bert_embeddings";

    private final BertEmbeddingsService bertEmbeddingsService;
    private final BertEmbeddingsStore bertEmbeddingsStore;
    private final IndexRepository indexRepository;
    private final CipherService cipherService;
    private final LuceneAutocompleteService luceneAutocompleteService;

    @Value("${app.indexer.use-database}")
    private boolean useDatabase;

    @Override
    public String getType() {
        return IndexType.BERT;
    }

    @Override
    @Transactional
    public void addDocumentToDocumentIndex(DocumentDTO documentDTO, String data) {
        log.info("Upsert doc {} in embeddings store", documentDTO.getId());

        // Un embedding est un vecteur de flottants qui represente le sens global
        // d'un texte dans un espace numerique. Deux textes proches par le sens
        // doivent produire des vecteurs proches.
        String indexedText = bertEmbeddingsService.buildIndexText(documentDTO.getTitre(), data);
        float[] vector = bertEmbeddingsService.generateEmbedding(indexedText);

        BertEmbeddingDocument document = new BertEmbeddingDocument(
                documentDTO.getId(),
                documentDTO.getTitre(),
                documentDTO.getAuteur(),
                documentDTO.getCategorie(),
                documentDTO.getNomFichier(),
                documentDTO.getDepotDateTime(),
                data,
                vector
        );

        bertEmbeddingsStore.upsert(document);
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
    public Void loadDocumentIndexFromDatabase() throws Exception {
        if (!useDatabase) {
            log.debug("Indexer Database disabled, creating empty embeddings store ...");
            bertEmbeddingsStore.clear();
            return null;
        }

        log.debug("Loading embeddings index from Database ...");
        Optional<IndexEntity> entityOpt = Optional.empty();
        try {
            entityOpt = indexRepository.findByIndexName(INDEX_NAME);
        } catch (Exception e) {
            log.warn("Loading embeddings index from Database KO : {}", e.getMessage());
        }
        if (entityOpt.isEmpty()) {
            log.debug("Embeddings index not found, creating empty store ...");
            bertEmbeddingsStore.clear();
            return null;
        }

        byte[] indexData = cipherService.decrypt(entityOpt.get().getIndexData());
        List<BertEmbeddingDocument> entities = new ArrayList<>();
        try (DataInputStream dis = new DataInputStream(new ByteArrayInputStream(indexData))) {
            // On recharge tout le store en RAM pour que la recherche BERT
            // n'ait pas a relire la base a chaque requete.
            int documentCount = dis.readInt();
            for (int i = 0; i < documentCount; i++) {
                entities.add(readEmbedding(dis));
            }
        }

        bertEmbeddingsStore.replaceAll(entities);
        log.info("Embeddings index {} with {} documents loaded from database", INDEX_NAME, entities.size());
        return null;
    }

    @Override
    public void saveDocumentIndexToDatabase() throws Exception {
        if (!useDatabase) {
            log.debug("Indexer Database disabled, save skipped");
            return;
        }

        // Le store memoire est serialise puis chiffre pour conserver
        // un etat redemarrable sans stocker l'index en clair en base.
        List<BertEmbeddingDocument> entities = bertEmbeddingsStore.findAll();
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        try (DataOutputStream dos = new DataOutputStream(baos)) {
            dos.writeInt(entities.size());
            for (BertEmbeddingDocument entity : entities) {
                writeEmbedding(dos, entity);
            }
        }

        IndexEntity entity = indexRepository.findByIndexName(INDEX_NAME).orElse(new IndexEntity());
        entity.setIndexName(INDEX_NAME);
        entity.setIndexData(cipherService.encrypt(baos.toByteArray()));
        entity.setDocumentCount((long) entities.size());
        entity.setLastUpdated(LocalDateTime.now());

        indexRepository.save(entity);
        log.info("Embeddings index {} saved to database", INDEX_NAME);
    }

    @Override
    @Transactional
    public void clearDocumentsIndex() {
        bertEmbeddingsStore.clear();
        log.info("Embeddings store cleared");
    }

    private void writeEmbedding(DataOutputStream dos, BertEmbeddingDocument entity) throws IOException {
        dos.writeLong(entity.documentId());
        writeString(dos, entity.title());
        writeString(dos, entity.author());
        writeString(dos, entity.category());
        writeString(dos, entity.filename());
        dos.writeBoolean(entity.depotDateTime() != null);
        if (entity.depotDateTime() != null) {
            writeString(dos, entity.depotDateTime().toString());
        }
        writeNullableString(dos, entity.contentText());
        byte[] embeddingData = entity.embedding() == null ? new byte[0] : bertEmbeddingsService.serialize(entity.embedding());
        dos.writeInt(embeddingData.length);
        dos.write(embeddingData);
    }

    private BertEmbeddingDocument readEmbedding(DataInputStream dis) throws IOException {
        long documentId = dis.readLong();
        String title = readString(dis);
        String author = readString(dis);
        String category = readString(dis);
        String filename = readString(dis);
        LocalDateTime depotDateTime = null;
        if (dis.readBoolean()) {
            depotDateTime = LocalDateTime.parse(readString(dis));
        }
        String contentText = readNullableString(dis);
        byte[] embeddingData = new byte[dis.readInt()];
        dis.readFully(embeddingData);
        return new BertEmbeddingDocument(
                documentId,
                title,
                author,
                category,
                filename,
                depotDateTime,
                contentText,
                bertEmbeddingsService.deserialize(embeddingData)
        );
    }

    private void writeNullableString(DataOutputStream dos, String value) throws IOException {
        dos.writeBoolean(value != null);
        if (value != null) {
            writeString(dos, value);
        }
    }

    private String readNullableString(DataInputStream dis) throws IOException {
        return dis.readBoolean() ? readString(dis) : null;
    }

    private void writeString(DataOutputStream dos, String value) throws IOException {
        byte[] bytes = (value == null ? "" : value).getBytes(StandardCharsets.UTF_8);
        dos.writeInt(bytes.length);
        dos.write(bytes);
    }

    private String readString(DataInputStream dis) throws IOException {
        byte[] bytes = new byte[dis.readInt()];
        dis.readFully(bytes);
        return new String(bytes, StandardCharsets.UTF_8);
    }
}
