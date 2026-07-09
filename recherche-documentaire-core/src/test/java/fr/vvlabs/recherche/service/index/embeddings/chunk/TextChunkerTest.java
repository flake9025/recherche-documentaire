package fr.vvlabs.recherche.service.index.embeddings.chunk;

import ai.djl.huggingface.tokenizers.jni.CharSpan;
import fr.vvlabs.recherche.service.index.embeddings.BertEmbeddingsService;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

class TextChunkerTest {

    private final BertEmbeddingsService bertEmbeddingsService = Mockito.mock(BertEmbeddingsService.class);

    /**
     * Construit un CharSpan par "mot" separe par des espaces, comme le ferait un tokenizer
     * simplifie (un token = un mot), pour piloter le decoupage de facon deterministe.
     */
    private CharSpan[] wordSpans(String text) {
        List<CharSpan> spans = new java.util.ArrayList<>();
        int i = 0;
        int n = text.length();
        while (i < n) {
            while (i < n && text.charAt(i) == ' ') {
                i++;
            }
            int start = i;
            while (i < n && text.charAt(i) != ' ') {
                i++;
            }
            if (i > start) {
                spans.add(new CharSpan(start, i));
            }
        }
        return spans.toArray(new CharSpan[0]);
    }

    @Test
    void chunk_returnsEmptyForBlankText() {
        TextChunker chunker = new TextChunker(bertEmbeddingsService, true, 4, 1);
        assertThat(chunker.chunk("   ")).isEmpty();
    }

    @Test
    void chunk_disabled_returnsSingleChunkWithoutTokenizer() {
        TextChunker chunker = new TextChunker(bertEmbeddingsService, false, 4, 1);

        List<TextChunk> chunks = chunker.chunk("un deux trois quatre cinq six");

        assertThat(chunks).hasSize(1);
        assertThat(chunks.getFirst().index()).isZero();
        assertThat(chunks.getFirst().total()).isEqualTo(1);
        assertThat(chunks.getFirst().text()).isEqualTo("un deux trois quatre cinq six");
        Mockito.verifyNoInteractions(bertEmbeddingsService);
    }

    @Test
    void chunk_shortText_returnsSingleChunk() {
        String text = "un deux trois";
        when(bertEmbeddingsService.encodeCharSpans(text)).thenReturn(wordSpans(text));

        TextChunker chunker = new TextChunker(bertEmbeddingsService, true, 4, 1);
        List<TextChunk> chunks = chunker.chunk(text);

        assertThat(chunks).hasSize(1);
        assertThat(chunks.getFirst().text()).isEqualTo(text);
    }

    @Test
    void chunk_longText_slidesWindowWithOverlap() {
        // 6 tokens (mots), fenetre 4, overlap 1 -> pas de 3 tokens.
        // Fenetres attendues: [0..4) et [3..6) => 2 chunks.
        String text = "un deux trois quatre cinq six";
        when(bertEmbeddingsService.encodeCharSpans(text)).thenReturn(wordSpans(text));

        TextChunker chunker = new TextChunker(bertEmbeddingsService, true, 4, 1);
        List<TextChunk> chunks = chunker.chunk(text);

        assertThat(chunks).hasSize(2);
        assertThat(chunks.get(0).index()).isZero();
        assertThat(chunks.get(0).total()).isEqualTo(2);
        assertThat(chunks.get(0).text()).isEqualTo("un deux trois quatre");
        assertThat(chunks.get(1).index()).isEqualTo(1);
        // Chevauchement: le token "quatre" est repris au debut du 2e chunk.
        assertThat(chunks.get(1).text()).isEqualTo("quatre cinq six");
    }

    @Test
    void chunk_fallsBackToSingleChunkWhenTokenizerFails() {
        String text = "un deux trois quatre cinq six sept huit";
        when(bertEmbeddingsService.encodeCharSpans(text))
                .thenThrow(new IllegalStateException("tokenizer indisponible"));

        TextChunker chunker = new TextChunker(bertEmbeddingsService, true, 4, 1);
        List<TextChunk> chunks = chunker.chunk(text);

        assertThat(chunks).hasSize(1);
        assertThat(chunks.getFirst().text()).isEqualTo(text);
    }
}
