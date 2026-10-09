package fr.vvlabs.recherche.service.index.embeddings;

import fr.vvlabs.recherche.dto.DocumentDTO;
import fr.vvlabs.recherche.model.BertEmbeddingsIndexEntity;
import fr.vvlabs.recherche.repository.BertEmbeddingsIndexRepository;
import fr.vvlabs.recherche.service.index.IndexService;
import fr.vvlabs.recherche.service.index.IndexType;
import fr.vvlabs.recherche.service.index.embeddings.store.BertEmbeddingsStore;
import fr.vvlabs.recherche.service.index.embeddings.store.BertEmbeddingsStoreFactory;
import fr.vvlabs.recherche.service.index.embeddings.chunk.TextChunk;
import fr.vvlabs.recherche.service.index.embeddings.chunk.TextChunker;
import fr.vvlabs.recherche.service.index.lucene.LuceneAutocompleteService;
import fr.vvlabs.recherche.service.cipher.CipherService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
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
@ConditionalOnProperty(name = "app.indexer.default", havingValue = IndexType.BERT)
@RequiredArgsConstructor
@Slf4j
public class BertEmbeddingsIndexService implements IndexService<Void> {

    // Le snapshot complet du store BERT est persiste dans la meme table que Lucene,
    // mais avec un nom d'index dedie.
    private static final String INDEX_NAME = "bert_embeddings";
    private static final int SNAPSHOT_MAGIC = 0x42455254;
    private static final int SNAPSHOT_VERSION = 1;

    private final BertEmbeddingsService bertEmbeddingsService;
    private final BertEmbeddingsStoreFactory bertEmbeddingsStoreFactory;
    private final BertEmbeddingsIndexRepository indexRepository;
    private final CipherService cipherService;
    private final LuceneAutocompleteService luceneAutocompleteService;
    private final TextChunker textChunker;

    @Value("${app.indexer.use-database}")
    private boolean useDatabase;

    @Override
    public String getType() {
        return IndexType.BERT;
    }

    @Override
    @Transactional
    public synchronized void addDocumentToDocumentIndex(DocumentDTO documentDTO, String data) {
        log.info("Upsert doc {} in embeddings store", documentDTO.getId());

        // Un embedding est un vecteur de flottants qui represente le sens global
        // d'un texte dans un espace numerique. Deux textes proches par le sens
        // doivent produire des vecteurs proches.
        //
        // Le contenu est decoupe en chunks alignes sur la fenetre de tokens du modele:
        // chaque chunk est embedde separement (avec les metadonnees du document) et
        // stocke comme une entree distincte, pour ne pas perdre le contenu au-dela de
        // la limite de tokens et affiner la pertinence par passage.
        BertEmbeddingsStore store = bertEmbeddingsStoreFactory.getDefaultStore();
        // Decouper le texte final (metadonnees incluses), pas ajouter un prefixe apres le decoupage.
        List<TextChunk> chunks = textChunker.chunk(bertEmbeddingsService.buildIndexText(
                documentDTO.getTitre(), documentDTO.getAuteur(), documentDTO.getCategorie(),
                documentDTO.getNomFichier(), data));
        if (chunks.isEmpty()) {
            // Document sans contenu exploitable: on indexe tout de meme les metadonnees.
            chunks = List.of(new TextChunk(0, 1, ""));
        }

        int chunkCount = chunks.size();
        if (chunkCount > BertEmbeddingDocument.POINT_ID_FACTOR) {
            throw new IllegalArgumentException("Document exceeds the maximum of 10000 chunks");
        }
        List<BertEmbeddingDocument> prepared = new ArrayList<>(chunkCount);
        for (TextChunk chunk : chunks) {
            float[] vector = bertEmbeddingsService.generateEmbedding(chunk.text());

            BertEmbeddingDocument document = new BertEmbeddingDocument(
                    documentDTO.getId(),
                    chunk.index(),
                    chunkCount,
                    documentDTO.getTitre(),
                    documentDTO.getAuteur(),
                    documentDTO.getCategorie(),
                    documentDTO.getNomFichier(),
                    documentDTO.getDepotDateTime(),
                    chunk.text(),
                    vector
            );
            prepared.add(document);
        }
        // Ne purger l'ancien index qu'une fois tous les embeddings generes avec succes.
        store.replaceDocument(documentDTO.getId(), prepared);
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
            bertEmbeddingsStoreFactory.getDefaultStore().clear();
            return null;
        }

        log.debug("Loading embeddings index from Database ...");
        Optional<BertEmbeddingsIndexEntity> entityOpt = indexRepository.findByIndexName(INDEX_NAME);
        if (entityOpt.isEmpty()) {
            log.debug("Embeddings index not found, creating empty store ...");
            bertEmbeddingsStoreFactory.getDefaultStore().clear();
            return null;
        }

        byte[] indexData = cipherService.decrypt(entityOpt.get().getIndexData());
        List<BertEmbeddingDocument> entities = new ArrayList<>();
        try (DataInputStream dis = new DataInputStream(new ByteArrayInputStream(indexData))) {
            if (dis.readInt() != SNAPSHOT_MAGIC || dis.readInt() != SNAPSHOT_VERSION) {
                throw new IOException("Unsupported BERT snapshot format");
            }
            // On recharge tout le store en RAM pour que la recherche BERT
            // n'ait pas a relire la base a chaque requete.
            int chunkCount = dis.readInt();
            if (chunkCount < 0 || chunkCount > dis.available() / Integer.BYTES) {
                throw new IOException("Invalid BERT snapshot chunk count");
            }
            for (int i = 0; i < chunkCount; i++) {
                BertEmbeddingDocument document = readEmbedding(dis);
                if (!entities.isEmpty() && document.embedding().length != entities.getFirst().embedding().length) {
                    throw new IOException("Inconsistent BERT snapshot vector dimensions");
                }
                entities.add(document);
            }
            if (dis.available() != 0) {
                throw new IOException("Unexpected trailing BERT snapshot data");
            }
        } catch (IOException exception) {
            throw new IllegalStateException(
                    "Snapshot BERT invalide ou incompatible. Supprimer le snapshot 'bert_embeddings'"
                            + " de bert_embeddings_index, puis reconstruire l'index.",
                    exception);
        }

        BertEmbeddingsStore store = bertEmbeddingsStoreFactory.getDefaultStore();
        store.replaceAll(entities);
        log.info("Embeddings index {} with {} chunks ({} documents) loaded from database",
                INDEX_NAME, entities.size(), store.countDocuments());
        return null;
    }

    @Override
    public synchronized void saveDocumentIndexToDatabase() throws Exception {
        if (!useDatabase) {
            log.debug("Indexer Database disabled, save skipped");
            return;
        }

        // Le store memoire est serialise puis chiffre pour conserver
        // un etat redemarrable sans stocker l'index en clair en base.
        BertEmbeddingsStore store = bertEmbeddingsStoreFactory.getDefaultStore();
        List<BertEmbeddingDocument> entities = store.findAll();
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        try (DataOutputStream dos = new DataOutputStream(baos)) {
            dos.writeInt(SNAPSHOT_MAGIC);
            dos.writeInt(SNAPSHOT_VERSION);
            dos.writeInt(entities.size());
            for (BertEmbeddingDocument entity : entities) {
                writeEmbedding(dos, entity);
            }
        }

        BertEmbeddingsIndexEntity entity = indexRepository.findByIndexName(INDEX_NAME).orElse(new BertEmbeddingsIndexEntity());
        entity.setIndexName(INDEX_NAME);
        entity.setIndexData(cipherService.encrypt(baos.toByteArray()));
        entity.setDocumentCount(store.countDocuments());
        entity.setLastUpdated(LocalDateTime.now());

        indexRepository.save(entity);
        log.info("Embeddings index {} saved to database ({} chunks, {} documents)",
                INDEX_NAME, entities.size(), store.countDocuments());
    }

    @Override
    @Transactional
    public void clearDocumentsIndex() {
        bertEmbeddingsStoreFactory.getDefaultStore().clear();
        log.info("Embeddings store cleared");
    }

    private void writeEmbedding(DataOutputStream dos, BertEmbeddingDocument entity) throws IOException {
        dos.writeLong(entity.documentId());
        dos.writeInt(entity.chunkIndex());
        dos.writeInt(entity.chunkCount());
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
        int chunkIndex = dis.readInt();
        int chunkCount = dis.readInt();
        if (documentId <= 0 || chunkCount < 1 || chunkCount > BertEmbeddingDocument.POINT_ID_FACTOR
                || chunkIndex < 0 || chunkIndex >= chunkCount) {
            throw new IOException("Invalid BERT snapshot chunk identity");
        }
        String title = readString(dis);
        String author = readString(dis);
        String category = readString(dis);
        String filename = readString(dis);
        LocalDateTime depotDateTime = null;
        if (dis.readBoolean()) {
            depotDateTime = LocalDateTime.parse(readString(dis));
        }
        String contentText = readNullableString(dis);
        byte[] embeddingData = readBytes(dis);
        if (embeddingData.length == 0 || embeddingData.length % Float.BYTES != 0) {
            throw new IOException("Invalid BERT snapshot vector size");
        }
        return new BertEmbeddingDocument(
                documentId,
                chunkIndex,
                chunkCount,
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
        return new String(readBytes(dis), StandardCharsets.UTF_8);
    }

    private byte[] readBytes(DataInputStream dis) throws IOException {
        int length = dis.readInt();
        if (length < 0 || length > dis.available()) {
            throw new IOException("Invalid BERT snapshot field length");
        }
        byte[] bytes = new byte[length];
        dis.readFully(bytes);
        return bytes;
    }
}
