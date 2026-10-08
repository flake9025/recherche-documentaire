package fr.vvlabs.recherche.service.index.embeddings;

import ai.djl.MalformedModelException;
import ai.djl.huggingface.tokenizers.HuggingFaceTokenizer;
import ai.djl.huggingface.tokenizers.jni.CharSpan;
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
    private HuggingFaceTokenizer tokenizer;
    private HuggingFaceTokenizer embeddingTokenizer;
    @Value("${app.embeddings.model-max-tokens:256}")
    private int modelMaxTokens = 256;

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

    public synchronized float[] generateEmbedding(String text) {
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

    /**
     * Encode un texte et retourne les positions (offsets de caracteres) de chaque token,
     * sans tokens speciaux. Utilise par le decoupage en chunks pour aligner les passages
     * sur la fenetre de tokens reellement consommee par le modele.
     *
     * @param text texte a encoder
     * @return spans de caracteres par token (peut contenir des entrees nulles pour les tokens sans position)
     */
    public synchronized CharSpan[] encodeCharSpans(String text) {
        String normalizedText = StringUtils.trimToEmpty(text);
        if (normalizedText.isBlank()) {
            return new CharSpan[0];
        }
        try {
            ensureTokenizerLoaded();
            // addSpecialTokens=false: on ne veut que les tokens de contenu, avec leurs offsets.
            CharSpan[] spans = tokenizer.encode(normalizedText, false, false).getCharTokenSpans();
            int codePoints = normalizedText.codePointCount(0, normalizedText.length());
            if (codePoints == normalizedText.length()) {
                return spans;
            }
            // Les offsets natifs comptent les code points, substring() utilise des unites UTF-16.
            int[] utf16Offsets = new int[codePoints + 1];
            for (int index = 0, offset = 0; index < codePoints; index++) {
                utf16Offsets[index] = offset;
                offset += Character.charCount(normalizedText.codePointAt(offset));
            }
            utf16Offsets[codePoints] = normalizedText.length();
            CharSpan[] converted = new CharSpan[spans.length];
            for (int index = 0; index < spans.length; index++) {
                CharSpan span = spans[index];
                if (span != null && span.getStart() >= 0 && span.getEnd() >= span.getStart()) {
                    if (span.getEnd() > codePoints) {
                        throw new IllegalStateException("Tokenizer offsets exceed content length");
                    }
                    converted[index] = new CharSpan(utf16Offsets[span.getStart()], utf16Offsets[span.getEnd()]);
                }
            }
            return converted;
        } catch (IOException e) {
            throw new IllegalStateException("Failed to load tokenizer " + modelId, e);
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
        HuggingFaceTokenizer huggingFaceTokenizer = HuggingFaceTokenizer.builder().optTokenizerName(modelId)
                .optTruncation(true).optPadding(false).optMaxLength(modelMaxTokens).build();
        TextEmbeddingTranslator translator = TextEmbeddingTranslator.builder(huggingFaceTokenizer).build();
        Criteria<String, float[]> criteria = Criteria.builder()
                .setTypes(String.class, float[].class)
                .optModelUrls("djl://ai.djl.huggingface.pytorch/" + modelId)
                .optTranslator(translator)
                .build();

        this.model = criteria.loadModel();
        this.predictor = model.newPredictor();
        this.embeddingTokenizer = huggingFaceTokenizer;
        log.info("Embeddings model loaded: {}", modelId);
    }

    private void ensureTokenizerLoaded() throws IOException {
        if (tokenizer == null) {
            // Le tokenizer du modele peut tronquer par defaut : celui du chunker doit lire tout le document.
            tokenizer = HuggingFaceTokenizer.builder().optTokenizerName(modelId)
                    .optTruncation(false).optPadding(false).build();
        }
    }

    @PreDestroy
    public synchronized void cleanup() throws IOException {
        if (predictor != null) {
            predictor.close();
        }
        if (model != null) {
            model.close();
        }
        if (tokenizer != null) {
            tokenizer.close();
        }
        if (embeddingTokenizer != null) {
            embeddingTokenizer.close();
        }
    }
}
