package fr.vvlabs.recherche.service.index.embeddings.chunk;

import ai.djl.huggingface.tokenizers.jni.CharSpan;
import fr.vvlabs.recherche.service.index.embeddings.BertEmbeddingsService;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

class TextChunkerTest {
    @org.junit.jupiter.api.io.TempDir
    java.nio.file.Path temporaryDirectory;

    private final BertEmbeddingsService bertEmbeddingsService = Mockito.mock(BertEmbeddingsService.class);

    @org.junit.jupiter.api.BeforeEach
    void defaultTokenizer() {
        Mockito.lenient().when(bertEmbeddingsService.encodeCharSpans(Mockito.anyString()))
                .thenAnswer(invocation -> wordSpans(invocation.getArgument(0)));
    }

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
    void chunk_rejectsIndexingWhenTokenizerFails() {
        String text = "un deux trois quatre cinq six sept huit";
        when(bertEmbeddingsService.encodeCharSpans(text))
                .thenThrow(new IllegalStateException("tokenizer indisponible"));

        TextChunker chunker = new TextChunker(bertEmbeddingsService, true, 4, 1);
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> chunker.chunk(text))
                .isInstanceOf(IllegalStateException.class).hasMessage("tokenizer indisponible");
    }

    @Test
    void chunk_coversTheEndOfALongDocumentWithinTheBudget() {
        String text = java.util.stream.IntStream.range(0, 1200)
                .mapToObj(i -> "mot" + i).collect(java.util.stream.Collectors.joining(" "));
        when(bertEmbeddingsService.encodeCharSpans(text)).thenReturn(wordSpans(text));
        var chunks = new TextChunker(bertEmbeddingsService, true, 254, 32).chunk(text);
        assertThat(chunks).hasSizeGreaterThan(4);
        assertThat(chunks.getLast().text()).endsWith("mot1199");
        assertThat(chunks).allSatisfy(chunk -> assertThat(wordSpans(chunk.text()).length).isLessThanOrEqualTo(254));
        for (int i = 0; i < 1200; i++) {
            final String token = "mot" + i;
            assertThat(chunks.stream().anyMatch(chunk -> java.util.Arrays.asList(chunk.text().split(" ")).contains(token))).isTrue();
        }
    }

    @Test
    void chunk_rejectsInvalidBudgetAndOverlap() {
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> new TextChunker(bertEmbeddingsService, true, 256, 32))
                .isInstanceOf(IllegalArgumentException.class);
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> new TextChunker(bertEmbeddingsService, true, 4, 4))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void realTokenizerWithTruncationDisabledCoversLongContentAndRespectsEmbeddingWindow() throws Exception {
        java.nio.file.Path tokenizerFile = temporaryDirectory.resolve("tokenizer.json");
        java.nio.file.Files.writeString(tokenizerFile, """
                {
                  "version":"1.0",
                  "truncation":{"direction":"Right","max_length":256,"strategy":"LongestFirst","stride":0},
                  "padding":null,
                  "added_tokens":[],
                  "normalizer":null,
                  "pre_tokenizer":{"type":"Whitespace"},
                  "post_processor":null,
                  "decoder":null,
                  "model":{"type":"WordLevel","vocab":{"[UNK]":0,"mot":1,"fin":2},"unk_token":"[UNK]"}
                }
                """);
        try (var tokenizer = ai.djl.huggingface.tokenizers.HuggingFaceTokenizer.builder()
                .optTokenizerPath(tokenizerFile).optTruncation(false).optPadding(false).build()) {
            String text = "mot ".repeat(1200) + "fin";
            when(bertEmbeddingsService.encodeCharSpans(Mockito.anyString()))
                    .thenAnswer(invocation -> tokenizer.encode(invocation.<String>getArgument(0), false, false).getCharTokenSpans());
            var chunks = new TextChunker(bertEmbeddingsService, true, 254, 32).chunk(text);
            assertThat(tokenizer.encode(text, false, false).getIds()).hasSize(1201);
            assertThat(chunks.getLast().text()).endsWith("fin");
            assertThat(chunks).allSatisfy(chunk -> assertThat(tokenizer.encode(chunk.text(), false, false).getIds().length)
                    .isLessThanOrEqualTo(254));
        }
    }

    @Test
    void reencodedWordPiecesFitTheBudgetWithoutSkippingTokensWhenOverlapIsZero() throws Exception {
        java.nio.file.Path tokenizerFile = temporaryDirectory.resolve("wordpiece.json");
        java.nio.file.Files.writeString(tokenizerFile, """
                {
                  "version":"1.0","truncation":null,"padding":null,"added_tokens":[],
                  "normalizer":null,"pre_tokenizer":{"type":"Whitespace"},"post_processor":null,
                  "decoder":{"type":"WordPiece","prefix":"##","cleanup":true},
                  "model":{"type":"WordPiece","vocab":{"[UNK]":0,"hello":1,"##ing":2,"world":3,"i":4,"##ng":5},
                           "unk_token":"[UNK]","continuing_subword_prefix":"##","max_input_chars_per_word":100}
                }
                """);
        try (var tokenizer = ai.djl.huggingface.tokenizers.HuggingFaceTokenizer.builder()
                .optTokenizerPath(tokenizerFile).optTruncation(false).optPadding(false).build()) {
            when(bertEmbeddingsService.encodeCharSpans(Mockito.anyString()))
                    .thenAnswer(invocation -> tokenizer.encode(invocation.<String>getArgument(0), false, false).getCharTokenSpans());
            String text = "helloing world helloing world helloing world";
            var chunks = new TextChunker(bertEmbeddingsService, true, 4, 0).chunk(text);
            assertThat(chunks).extracting(TextChunk::text)
                    .containsExactly("helloing world hello", "ing world hello", "ing world");
            assertThat(chunks).allSatisfy(chunk ->
                    assertThat(tokenizer.encode(chunk.text(), false, false).getIds().length).isLessThanOrEqualTo(4));
            assertThat(chunks.stream().map(TextChunk::text).collect(java.util.stream.Collectors.joining())).isEqualTo(text);
        }
    }

    @Test
    void nativeCodePointOffsetsAreConvertedToUtf16WithoutSplittingSurrogates() throws Exception {
        java.nio.file.Path tokenizerFile = temporaryDirectory.resolve("unicode.json");
        java.nio.file.Files.writeString(tokenizerFile, """
                {
                  "version":"1.0","truncation":null,"padding":null,"added_tokens":[],
                  "normalizer":null,"pre_tokenizer":{"type":"Whitespace"},"post_processor":null,"decoder":null,
                  "model":{"type":"WordLevel","vocab":{"[UNK]":0,"alpha":1,"beta":2},"unk_token":"[UNK]"}
                }
                """);
        try (var tokenizer = ai.djl.huggingface.tokenizers.HuggingFaceTokenizer.builder()
                .optTokenizerPath(tokenizerFile).optTruncation(false).optPadding(false).build()) {
            var service = new BertEmbeddingsService("local-test");
            org.springframework.test.util.ReflectionTestUtils.setField(service, "tokenizer", tokenizer);
            var chunks = new TextChunker(service, true, 1, 0).chunk("\uD83D\uDE00 alpha beta");
            assertThat(chunks).extracting(TextChunk::text).containsExactly("\uD83D\uDE00", "alpha", "beta");
            assertThat(chunks).allSatisfy(chunk -> assertThat(chunk.text()).doesNotContain("\uFFFD"));
        }
    }
}
