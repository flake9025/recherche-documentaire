package fr.vvlabs.recherche.service.index.embeddings;

import ai.djl.MalformedModelException;
import ai.djl.huggingface.tokenizers.HuggingFaceTokenizer;
import ai.djl.huggingface.translator.TextEmbeddingTranslator;
import ai.djl.inference.Predictor;
import ai.djl.repository.zoo.Criteria;
import ai.djl.repository.zoo.ModelNotFoundException;
import ai.djl.repository.zoo.ZooModel;
import ai.djl.translate.TranslateException;
import jakarta.annotation.PreDestroy;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.ByteBuffer;

@Service
@Slf4j
public class BertEmbeddingsService {

    // Ce modele donne un bon compromis pour un POC:
    // taille modeste, chargement rapide et embeddings suffisamment pertinents
    // pour de la recherche documentaire generaliste.
    private final String modelId;
    private ZooModel<String, float[]> model;
    private Predictor<String, float[]> predictor;

    public BertEmbeddingsService(@Value("${app.embeddings.model-id:sentence-transformers/all-MiniLM-L6-v2}") String modelId)
            throws ModelNotFoundException, MalformedModelException, IOException {
        this.modelId = modelId;
    }

    public String getModelId() {
        return modelId;
    }

    public boolean isModelLoaded() {
        return predictor != null;
    }

    public float[] generateEmbedding(String text) {
        String normalizedText = StringUtils.trimToEmpty(text);
        if (normalizedText.isBlank()) {
            return new float[0];
        }
        try {
            ensureModelLoaded();
            return predictor.predict(normalizedText);
        } catch (TranslateException e) {
            throw new IllegalStateException("Failed to generate embedding with model " + modelId, e);
        } catch (ModelNotFoundException | MalformedModelException | IOException e) {
            throw new IllegalStateException("Failed to load embedding model " + modelId, e);
        }
    }

    public byte[] serialize(float[] vector) {
        ByteBuffer buffer = ByteBuffer.allocate(Float.BYTES * vector.length);
        for (float value : vector) {
            buffer.putFloat(value);
        }
        return buffer.array();
    }

    public float[] deserialize(byte[] bytes) {
        if (bytes == null || bytes.length == 0) {
            return new float[0];
        }
        ByteBuffer buffer = ByteBuffer.wrap(bytes);
        float[] vector = new float[bytes.length / Float.BYTES];
        for (int i = 0; i < vector.length; i++) {
            vector[i] = buffer.getFloat();
        }
        return vector;
    }

    public String buildIndexText(String title, String author, String category, String filename, String content) {
        // On aligne le texte projete en embedding sur les champs textuels
        // indexes en Lucene pour eviter qu'un moteur retrouve moins
        // d'information qu'un autre.
        return java.util.stream.Stream.of(title, author, category, filename, content)
                .map(StringUtils::trimToEmpty)
                .filter(StringUtils::isNotBlank)
                .collect(java.util.stream.Collectors.joining("\n\n"));
    }

    private synchronized void ensureModelLoaded() throws ModelNotFoundException, MalformedModelException, IOException {
        if (predictor != null) {
            return;
        }

        // DJL charge le modele Sentence-Transformers depuis Hugging Face et expose
        // un predictor qui transforme un texte libre en vecteur numerique.
        HuggingFaceTokenizer tokenizer = HuggingFaceTokenizer.newInstance(modelId);
        TextEmbeddingTranslator translator = TextEmbeddingTranslator.builder(tokenizer).build();
        Criteria<String, float[]> criteria = Criteria.builder()
                .setTypes(String.class, float[].class)
                .optModelUrls("djl://ai.djl.huggingface.pytorch/" + modelId)
                .optTranslator(translator)
                .build();

        this.model = criteria.loadModel();
        this.predictor = model.newPredictor();
        log.info("Embeddings model loaded: {}", modelId);
    }

    @PreDestroy
    public void cleanup() throws IOException {
        if (predictor != null) {
            predictor.close();
        }
        if (model != null) {
            model.close();
        }
    }
}
