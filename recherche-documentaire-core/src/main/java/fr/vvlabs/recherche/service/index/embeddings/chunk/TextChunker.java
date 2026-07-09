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

    public TextChunker(
            BertEmbeddingsService bertEmbeddingsService,
            @Value("${app.embeddings.chunk.enabled:true}") boolean enabled,
            @Value("${app.embeddings.chunk.max-tokens:256}") int maxTokens,
            @Value("${app.embeddings.chunk.overlap-tokens:32}") int overlapTokens
    ) {
        this.bertEmbeddingsService = bertEmbeddingsService;
        this.enabled = enabled;
        this.maxTokens = Math.max(1, maxTokens);
        this.overlapTokens = Math.max(0, Math.min(overlapTokens, this.maxTokens - 1));
    }

    /**
     * Decoupe le contenu en passages exploitables pour l'embedding.
     *
     * <p>Si le chunking est desactive, si le texte est vide, ou si le tokenizer n'est
     * pas disponible, le texte complet est retourne comme un unique chunk.</p>
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

        CharSpan[] spans;
        try {
            spans = bertEmbeddingsService.encodeCharSpans(normalized);
        } catch (RuntimeException e) {
            log.warn("Token-based chunking unavailable, indexing text as a single chunk: {}", e.getMessage());
            return List.of(new TextChunk(0, 1, normalized));
        }

        // Les tokens speciaux ou non alignes sur des caracteres exposent un span null:
        // on ne conserve que les tokens porteurs d'une position dans le texte.
        List<CharSpan> tokenSpans = new ArrayList<>(spans.length);
        for (CharSpan span : spans) {
            if (span != null && span.getStart() >= 0 && span.getEnd() > span.getStart()) {
                tokenSpans.add(span);
            }
        }

        int tokenCount = tokenSpans.size();
        if (tokenCount <= maxTokens) {
            return List.of(new TextChunk(0, 1, normalized));
        }

        // Fenetre glissante: on avance de (maxTokens - overlapTokens) tokens a chaque pas.
        int step = maxTokens - overlapTokens;
        List<int[]> windows = new ArrayList<>();
        for (int start = 0; start < tokenCount; start += step) {
            int end = Math.min(start + maxTokens, tokenCount);
            int charStart = tokenSpans.get(start).getStart();
            int charEnd = tokenSpans.get(end - 1).getEnd();
            windows.add(new int[]{charStart, charEnd});
            if (end == tokenCount) {
                break;
            }
        }

        int total = windows.size();
        List<TextChunk> chunks = new ArrayList<>(total);
        for (int i = 0; i < total; i++) {
            int[] window = windows.get(i);
            String chunkText = normalized.substring(window[0], window[1]).strip();
            if (!chunkText.isBlank()) {
                chunks.add(new TextChunk(i, total, chunkText));
            }
        }
        // Renumerote proprement si des passages vides ont ete ecartes.
        if (chunks.size() != total) {
            int corrected = chunks.size();
            List<TextChunk> renumbered = new ArrayList<>(corrected);
            for (int i = 0; i < corrected; i++) {
                renumbered.add(new TextChunk(i, corrected, chunks.get(i).text()));
            }
            return renumbered.isEmpty() ? List.of(new TextChunk(0, 1, normalized)) : renumbered;
        }
        return chunks;
    }
}
