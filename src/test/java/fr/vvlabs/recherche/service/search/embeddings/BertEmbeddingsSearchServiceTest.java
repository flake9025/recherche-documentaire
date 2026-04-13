package fr.vvlabs.recherche.service.search.embeddings;

import fr.vvlabs.recherche.dto.SearchRequestDTO;
import fr.vvlabs.recherche.dto.SearchResultDTO;
import fr.vvlabs.recherche.service.index.embeddings.BertEmbeddingDocument;
import fr.vvlabs.recherche.service.index.embeddings.BertEmbeddingsService;
import fr.vvlabs.recherche.service.index.embeddings.store.BertEmbeddingsStoreFactory;
import fr.vvlabs.recherche.service.index.embeddings.store.BertEmbeddingsStore;
import fr.vvlabs.recherche.service.index.embeddings.store.hashmap.HashMapBertEmbeddingsStore;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.LocalDateTime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class BertEmbeddingsSearchServiceTest {

    @Mock
    private BertEmbeddingsService bertEmbeddingsService;

    private BertEmbeddingsStore bertEmbeddingsStore;
    private BertEmbeddingsStoreFactory bertEmbeddingsStoreFactory;
    private BertEmbeddingsSearchService service;

    @BeforeEach
    void setUp() {
        bertEmbeddingsStore = new HashMapBertEmbeddingsStore();
        bertEmbeddingsStoreFactory = new BertEmbeddingsStoreFactory(
                java.util.List.of(bertEmbeddingsStore),
                "hashmap"
        );
        service = new BertEmbeddingsSearchService(bertEmbeddingsService, bertEmbeddingsStoreFactory);
        ReflectionTestUtils.setField(service, "maxResults", 10);
        ReflectionTestUtils.setField(service, "minScore", 0.35d);
        ReflectionTestUtils.setField(service, "semanticWeight", 0.75d);
        ReflectionTestUtils.setField(service, "lexicalWeight", 0.25d);
        ReflectionTestUtils.setField(service, "candidateLimit", 0);
    }

    @Test
    void search_reranksExactLexicalMatchAheadOfLooseSemanticMatch() {
        when(bertEmbeddingsService.generateEmbedding("contrat travail")).thenReturn(new float[]{1.0f, 0.0f});

        bertEmbeddingsStore.upsert(new BertEmbeddingDocument(
                1L,
                "Contrat de travail CDI",
                "Alice",
                "CONTRAT",
                "contrat.pdf",
                LocalDateTime.of(2025, 1, 10, 9, 0),
                "Le contrat de travail de reference pour le poste.",
                new float[]{0.82f, 0.18f}
        ));
        bertEmbeddingsStore.upsert(new BertEmbeddingDocument(
                2L,
                "Document social generique",
                "Bob",
                "NOTE",
                "note.pdf",
                LocalDateTime.of(2025, 1, 11, 9, 0),
                "Texte proche semantiquement mais sans les termes attendus.",
                new float[]{0.92f, 0.08f}
        ));

        SearchResultDTO result = service.search(request("contrat travail"));

        assertThat(result.getNbResults()).isEqualTo(2);
        assertThat(result.getFragments().get(0).getId()).isEqualTo("1");
        assertThat(result.getFragments().get(0).getScore())
                .isGreaterThan(result.getFragments().get(1).getScore());
    }

    @Test
    void search_filtersOutLowScoreNoise() {
        when(bertEmbeddingsService.generateEmbedding("contrat travail")).thenReturn(new float[]{1.0f, 0.0f});

        bertEmbeddingsStore.upsert(new BertEmbeddingDocument(
                1L,
                "Contrat de travail CDI",
                "Alice",
                "CONTRAT",
                "contrat.pdf",
                LocalDateTime.of(2025, 1, 10, 9, 0),
                "Le contrat de travail de reference pour le poste.",
                new float[]{0.60f, 0.40f}
        ));
        bertEmbeddingsStore.upsert(new BertEmbeddingDocument(
                2L,
                "Recette cuisine",
                "Bob",
                "NOTE",
                "cuisine.pdf",
                LocalDateTime.of(2025, 1, 11, 9, 0),
                "Texte hors sujet.",
                new float[]{0.10f, 0.99f}
        ));

        SearchResultDTO result = service.search(request("contrat travail"));

        assertThat(result.getNbResults()).isEqualTo(1);
        assertThat(result.getFragments().getFirst().getId()).isEqualTo("1");
    }

    @Test
    void search_boostsExactBusinessCodeMatch() {
        when(bertEmbeddingsService.generateEmbedding("adr 324")).thenReturn(new float[]{1.0f, 0.0f});

        bertEmbeddingsStore.upsert(new BertEmbeddingDocument(
                1L,
                "ADR 0348",
                "Alice",
                "NOTE",
                "adr-0348.pdf",
                LocalDateTime.of(2025, 1, 10, 9, 0),
                "Document ADR 0348",
                new float[]{0.90f, 0.10f}
        ));
        bertEmbeddingsStore.upsert(new BertEmbeddingDocument(
                2L,
                "ADR 0324",
                "Bob",
                "NOTE",
                "adr-0324.pdf",
                LocalDateTime.of(2025, 1, 11, 9, 0),
                "Document ADR 0324",
                new float[]{0.82f, 0.18f}
        ));

        SearchResultDTO result = service.search(request("adr 324"));

        assertThat(result.getNbResults()).isEqualTo(2);
        assertThat(result.getFragments().get(0).getId()).isEqualTo("2");
        assertThat(result.getFragments().get(0).getName()).isEqualTo("ADR 0324");
    }

    @Test
    void search_withProductionThresholdCanDropPureLexicalContentMatch() {
        when(bertEmbeddingsService.generateEmbedding("procedure")).thenReturn(new float[]{1.0f, 0.0f});

        bertEmbeddingsStore.upsert(new BertEmbeddingDocument(
                1L,
                "Guide interne",
                "Alice",
                "NOTE",
                "guide.pdf",
                LocalDateTime.of(2025, 1, 10, 9, 0),
                "Cette procedure doit etre appliquee.",
                new float[]{0.0f, 1.0f}
        ));

        SearchResultDTO result = service.search(request("procedure"));

        assertThat(result.getNbResults()).isZero();

        ReflectionTestUtils.setField(service, "minScore", 0.10d);

        SearchResultDTO relaxedResult = service.search(request("procedure"));

        assertThat(relaxedResult.getNbResults()).isEqualTo(1);
        assertThat(relaxedResult.getFragments().getFirst().getId()).isEqualTo("1");
        assertThat(relaxedResult.getFragments().getFirst().getScore()).isGreaterThanOrEqualTo(0.10f);
    }

    @Test
    void search_canFindDocumentFromAuthorTextEmbeddedInStore() {
        when(bertEmbeddingsService.generateEmbedding("marie")).thenReturn(new float[]{1.0f, 0.0f});

        bertEmbeddingsStore.upsert(new BertEmbeddingDocument(
                1L,
                "Guide interne",
                "Marie-France FROMAGE",
                "NOTE",
                "guide.pdf",
                LocalDateTime.of(2025, 1, 10, 9, 0),
                "Contenu divers",
                new float[]{1.0f, 0.0f}
        ));

        ReflectionTestUtils.setField(service, "minScore", 0.10d);

        SearchResultDTO result = service.search(request("marie"));

        assertThat(result.getNbResults()).isEqualTo(1);
        assertThat(result.getFragments().getFirst().getId()).isEqualTo("1");
        assertThat(result.getFragments().getFirst().getAuthor()).isEqualTo("Marie-France FROMAGE");
    }

    private static SearchRequestDTO request(String query) {
        SearchRequestDTO request = new SearchRequestDTO();
        request.setQuery(query);
        return request;
    }
}
