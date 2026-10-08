package fr.vvlabs.recherche.service.index.embeddings.chunk;

import ai.djl.huggingface.tokenizers.jni.CharSpan;
import fr.vvlabs.recherche.service.index.embeddings.BertEmbeddingsService;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * Decoupe un texte en chunks alignes sur la fenetre de tokens du modele d'embedding.
 *
 * <p>Motivation: les modeles de type sentence-transformers tronquent silencieusement
 * les entrees au-dela de leur fenetre (~256 tokens pour all-MiniLM-L6-v2). Sans
 * decoupage, tout le contenu au-dela de cette limite est ignore lors de l'indexation
 * vectorielle. Le chunker produit des passages qui tiennent dans la fenetre, avec un
 * chevauchement pour ne pas couper le sens aux frontieres.</p>
 *
 * <p>Le decoupage s'appuie sur le tokenizer HuggingFace deja utilise pour l'embedding,
 * via les {@link CharSpan offsets de caracteres}, afin que le texte de chaque chunk
 * reste une sous-chaine exacte du contenu original (utile pour l'extrait affiche).</p>
 */
@Component
@Slf4j
public class TextChunker {

    private final BertEmbeddingsService bertEmbeddingsService;
    private final boolean enabled;
    private final int maxTokens;
    private final int overlapTokens;

    public TextChunker(BertEmbeddingsService service, boolean enabled, int maxTokens, int overlapTokens) {
        this(service, enabled, maxTokens, overlapTokens, 256);
    }

    @org.springframework.beans.factory.annotation.Autowired
    public TextChunker(
            BertEmbeddingsService bertEmbeddingsService,
            @Value("${app.embeddings.chunk.enabled:true}") boolean enabled,
            @Value("${app.embeddings.chunk.max-tokens:254}") int maxTokens,
            @Value("${app.embeddings.chunk.overlap-tokens:32}") int overlapTokens,
            @Value("${app.embeddings.model-max-tokens:256}") int modelMaxTokens
    ) {
        this.bertEmbeddingsService = bertEmbeddingsService;
        this.enabled = enabled;
        if (maxTokens < 1 || (enabled && maxTokens > modelMaxTokens - 2) || overlapTokens < 0 || overlapTokens >= maxTokens) {
            throw new IllegalArgumentException("Chunk tokens must fit the model window minus two special tokens; overlap must be in [0, maxTokens)");
        }
        this.maxTokens = maxTokens;
        this.overlapTokens = overlapTokens;
    }

    /**
     * Decoupe le contenu en passages exploitables pour l'embedding.
     *
     * <p>Un tokenizer indisponible interrompt l'indexation plutot que de tronquer
     * silencieusement le document.</p>
     *
     * @param text contenu a decouper
     * @return liste ordonnee de chunks (jamais vide si le texte n'est pas vide)
     */
    public List<TextChunk> chunk(String text) {
        String normalized = StringUtils.trimToEmpty(text);
        if (normalized.isBlank()) {
            return List.of();
        }
        if (!enabled) {
            return List.of(new TextChunk(0, 1, normalized));
        }

        CharSpan[] spans = bertEmbeddingsService.encodeCharSpans(normalized);

        // Les tokens speciaux ou non alignes sur des caracteres exposent un span null:
        // on ne conserve que les tokens porteurs d'une position dans le texte.
        List<CharSpan> tokenSpans = new ArrayList<>(spans.length);
        for (CharSpan span : spans) {
            if (span != null && span.getStart() >= 0 && span.getEnd() > span.getStart()) {
                tokenSpans.add(span);
            }
        }

        int tokenCount = tokenSpans.size();
        if (tokenCount == 0) {
            throw new IllegalStateException("Tokenizer returned no offsets for non-empty content");
        }
        if (spans.length <= maxTokens) {
            return List.of(new TextChunk(0, 1, normalized));
        }

        List<String> windows = new ArrayList<>();
        for (int start = 0; start < tokenCount;) {
            int end = Math.min(start + maxTokens, tokenCount);
            int charStart = start == 0 ? 0 : tokenSpans.get(start).getStart();
            String chunkText;
            while (true) {
                int charEnd = end == tokenCount ? normalized.length() : tokenSpans.get(end - 1).getEnd();
                chunkText = normalized.substring(charStart, charEnd).strip();
                int encodedTokens = bertEmbeddingsService.encodeCharSpans(chunkText).length;
                if (chunkText.isBlank() || encodedTokens == 0) {
                    throw new IllegalStateException("Tokenizer produced an empty chunk for non-empty content");
                }
                if (encodedTokens <= maxTokens) {
                    break;
                }
                // Un sous-mot en debut de passage peut se reencoder en plusieurs tokens.
                end -= Math.max(1, encodedTokens - maxTokens);
                if (end <= start) {
                    throw new IllegalStateException("Chunk token budget cannot represent this text boundary");
                }
            }
            windows.add(chunkText);
            if (end == tokenCount) {
                break;
            }
            start = Math.max(start + 1, end - overlapTokens);
        }

        int total = windows.size();
        List<TextChunk> chunks = new ArrayList<>(total);
        for (int i = 0; i < total; i++) {
            chunks.add(new TextChunk(i, total, windows.get(i)));
        }
        return chunks;
    }
}
