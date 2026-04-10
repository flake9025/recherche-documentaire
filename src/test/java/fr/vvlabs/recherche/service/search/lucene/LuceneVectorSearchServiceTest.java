package fr.vvlabs.recherche.service.search.lucene;

import fr.vvlabs.recherche.config.IndexConstants;
import fr.vvlabs.recherche.config.LuceneConfig;
import fr.vvlabs.recherche.dto.SearchRequestDTO;
import fr.vvlabs.recherche.dto.SearchResultDTO;
import fr.vvlabs.recherche.service.index.embeddings.BertEmbeddingsService;
import org.apache.lucene.analysis.standard.StandardAnalyzer;
import org.apache.lucene.document.Document;
import org.apache.lucene.document.Field;
import org.apache.lucene.document.KnnFloatVectorField;
import org.apache.lucene.document.TextField;
import org.apache.lucene.index.IndexWriter;
import org.apache.lucene.index.IndexWriterConfig;
import org.apache.lucene.index.VectorSimilarityFunction;
import org.apache.lucene.store.ByteBuffersDirectory;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.LocalDate;

import static fr.vvlabs.recherche.service.index.lucene.LuceneVectorIndexService.VECTOR_FIELD;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class LuceneVectorSearchServiceTest {

    @Mock
    private LuceneConfig luceneConfig;

    @Mock
    private BertEmbeddingsService bertEmbeddingsService;

    @Test
    void search_returnsNearestVectorMatches() throws Exception {
        StandardAnalyzer analyzer = new StandardAnalyzer();
        ByteBuffersDirectory directory = new ByteBuffersDirectory();
        addDocument(directory, analyzer, "1", "Rapport Alpha", "Alice", "note", "01/03/2026 10:00:00", "a.pdf", "contenu alpha", new float[]{1.0f, 0.0f});
        addDocument(directory, analyzer, "2", "Rapport Beta", "Bob", "rapport", "15/04/2026 10:00:00", "b.pdf", "contenu beta", new float[]{0.0f, 1.0f});

        when(luceneConfig.getDocumentsIndex()).thenReturn(directory);
        when(bertEmbeddingsService.generateEmbedding("alpha")).thenReturn(new float[]{1.0f, 0.0f});

        LuceneVectorSearchService service = new LuceneVectorSearchService(luceneConfig, bertEmbeddingsService);
        ReflectionTestUtils.setField(service, "maxResults", 5);
        ReflectionTestUtils.setField(service, "candidateMultiplier", 2);
        ReflectionTestUtils.setField(service, "minScore", 0.0f);
        ReflectionTestUtils.setField(service, "minQueryLength", 3);

        SearchRequestDTO request = new SearchRequestDTO();
        request.setQuery("alpha");
        SearchResultDTO result = service.search(request);

        assertThat(result.getNbResults()).isEqualTo(2);
        assertThat(result.getFragments().getFirst().getId()).isEqualTo("1");
        assertThat(result.getFragments().getFirst().getName()).isEqualTo("Rapport Alpha");
        assertThat(result.getFragments().getFirst().getFragment()).isEqualTo("contenu alpha");
        assertThat(result.getFragments().getFirst().getScore()).isGreaterThanOrEqualTo(result.getFragments().get(1).getScore());
    }

    @Test
    void search_appliesMetadataAndDateFilters() throws Exception {
        StandardAnalyzer analyzer = new StandardAnalyzer();
        ByteBuffersDirectory directory = new ByteBuffersDirectory();
        addDocument(directory, analyzer, "1", "Rapport Alpha", "Alice", "note", "01/03/2026 10:00:00", "a.pdf", "contenu alpha", new float[]{1.0f, 0.0f});
        addDocument(directory, analyzer, "2", "Rapport Beta", "Alice", "note", "15/04/2026 10:00:00", "b.pdf", "contenu beta", new float[]{0.9f, 0.1f});
        addDocument(directory, analyzer, "3", "Rapport Gamma", "Bob", "rapport", "02/03/2026 10:00:00", "c.pdf", "contenu gamma", new float[]{0.8f, 0.2f});

        when(luceneConfig.getDocumentsAnalyzer()).thenReturn(analyzer);
        when(luceneConfig.getDocumentsIndex()).thenReturn(directory);
        when(bertEmbeddingsService.generateEmbedding("alpha")).thenReturn(new float[]{1.0f, 0.0f});

        LuceneVectorSearchService service = new LuceneVectorSearchService(luceneConfig, bertEmbeddingsService);
        ReflectionTestUtils.setField(service, "maxResults", 5);
        ReflectionTestUtils.setField(service, "candidateMultiplier", 3);
        ReflectionTestUtils.setField(service, "minScore", 0.0f);
        ReflectionTestUtils.setField(service, "minQueryLength", 3);

        SearchRequestDTO request = new SearchRequestDTO();
        request.setQuery("alpha");
        request.setAuthor("Alice");
        request.setCategory("note");
        request.setDateFrom(LocalDate.of(2026, 3, 1));
        request.setDateTo(LocalDate.of(2026, 3, 31));

        SearchResultDTO result = service.search(request);

        assertThat(result.getNbResults()).isEqualTo(1);
        assertThat(result.getFragments().getFirst().getId()).isEqualTo("1");
    }

    @Test
    void search_returnsNoResultWhenQueryIsTooWeakWithoutFilters() throws Exception {
        LuceneVectorSearchService service = new LuceneVectorSearchService(luceneConfig, bertEmbeddingsService);
        ReflectionTestUtils.setField(service, "maxResults", 5);
        ReflectionTestUtils.setField(service, "candidateMultiplier", 2);
        ReflectionTestUtils.setField(service, "minScore", 0.0f);
        ReflectionTestUtils.setField(service, "minQueryLength", 3);

        SearchRequestDTO request = new SearchRequestDTO();
        request.setQuery("a");

        SearchResultDTO result = service.search(request);

        assertThat(result.getNbResults()).isZero();
        assertThat(result.getFragments()).isEmpty();
    }

    @Test
    void search_filtersOutHitsBelowMinScore() throws Exception {
        StandardAnalyzer analyzer = new StandardAnalyzer();
        ByteBuffersDirectory directory = new ByteBuffersDirectory();
        addDocument(directory, analyzer, "1", "Rapport Alpha", "Alice", "note", "01/03/2026 10:00:00", "a.pdf", "contenu alpha", new float[]{1.0f, 0.0f});
        addDocument(directory, analyzer, "2", "Rapport Beta", "Bob", "rapport", "15/04/2026 10:00:00", "b.pdf", "contenu beta", new float[]{0.0f, 1.0f});

        when(luceneConfig.getDocumentsIndex()).thenReturn(directory);
        when(bertEmbeddingsService.generateEmbedding("alpha")).thenReturn(new float[]{1.0f, 0.0f});

        LuceneVectorSearchService service = new LuceneVectorSearchService(luceneConfig, bertEmbeddingsService);
        ReflectionTestUtils.setField(service, "maxResults", 5);
        ReflectionTestUtils.setField(service, "candidateMultiplier", 2);
        ReflectionTestUtils.setField(service, "minScore", 0.95f);
        ReflectionTestUtils.setField(service, "minQueryLength", 3);

        SearchRequestDTO request = new SearchRequestDTO();
        request.setQuery("alpha");
        SearchResultDTO result = service.search(request);

        assertThat(result.getNbResults()).isEqualTo(1);
        assertThat(result.getFragments().getFirst().getId()).isEqualTo("1");
    }

    private static void addDocument(
            ByteBuffersDirectory directory,
            StandardAnalyzer analyzer,
            String id,
            String name,
            String author,
            String category,
            String date,
            String filename,
            String content,
            float[] vector
    ) throws Exception {
        IndexWriterConfig config = new IndexWriterConfig(analyzer);
        try (IndexWriter writer = new IndexWriter(directory, config)) {
            Document doc = new Document();
            doc.add(new TextField(IndexConstants.INDEX_KEY_ID, id, Field.Store.YES));
            doc.add(new TextField(IndexConstants.INDEX_KEY_NAME, name, Field.Store.YES));
            doc.add(new TextField(IndexConstants.INDEX_KEY_AUTEUR, author, Field.Store.YES));
            doc.add(new TextField(IndexConstants.INDEX_KEY_CATEGORIE, category, Field.Store.YES));
            doc.add(new TextField(IndexConstants.INDEX_KEY_DATE_DEPOT, date, Field.Store.YES));
            doc.add(new TextField(IndexConstants.INDEX_KEY_FICHIER, filename, Field.Store.YES));
            doc.add(new TextField(IndexConstants.INDEX_KEY_CONTENT, content, Field.Store.YES));
            doc.add(new KnnFloatVectorField(VECTOR_FIELD, vector, VectorSimilarityFunction.COSINE));
            writer.addDocument(doc);
            writer.commit();
        }
    }
}
