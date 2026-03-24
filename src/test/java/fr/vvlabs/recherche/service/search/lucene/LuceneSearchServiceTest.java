package fr.vvlabs.recherche.service.search.lucene;

import fr.vvlabs.recherche.config.IndexConstants;
import fr.vvlabs.recherche.config.LuceneConfig;
import fr.vvlabs.recherche.dto.SearchFragmentDTO;
import fr.vvlabs.recherche.dto.SearchRequestDTO;
import fr.vvlabs.recherche.dto.SearchResultDTO;
import org.apache.lucene.analysis.standard.StandardAnalyzer;
import org.apache.lucene.document.Document;
import org.apache.lucene.document.Field;
import org.apache.lucene.document.TextField;
import org.apache.lucene.index.IndexWriter;
import org.apache.lucene.index.IndexWriterConfig;
import org.apache.lucene.store.ByteBuffersDirectory;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.io.IOException;
import java.time.LocalDate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class LuceneSearchServiceTest {

    @Mock
    private LuceneConfig luceneConfig;

    @Test
    void searchFuzzy_returnsMatchingFragments() throws Exception {
        StandardAnalyzer analyzer = new StandardAnalyzer();
        ByteBuffersDirectory directory = new ByteBuffersDirectory();
        addDocument(directory, analyzer, "1", "Rapport", "Alice", "note", "01/01/2026 10:15:00", "doc.pdf", "dolor sit amet");

        when(luceneConfig.getDocumentsAnalyzer()).thenReturn(analyzer);
        when(luceneConfig.getDocumentsIndex()).thenReturn(directory);

        LuceneSearchService service = new LuceneSearchService(luceneConfig);

        SearchRequestDTO request = new SearchRequestDTO();
        request.setQuery("dolor");
        SearchResultDTO result = service.search(request);

        assertThat(result.getNbResults()).isEqualTo(1);
        assertThat(result.getFragments()).hasSize(1);
        SearchFragmentDTO fragment = result.getFragments().get(0);
        assertThat(fragment.getId()).isEqualTo("1");
        assertThat(fragment.getName()).isEqualTo("Rapport");
        assertThat(fragment.getAuthor()).isEqualTo("Alice");
        assertThat(fragment.getCategory()).isEqualTo("note");
        assertThat(fragment.getDate()).isEqualTo("01/01/2026 10:15:00");
        assertThat(fragment.getFilename()).isEqualTo("doc.pdf");
        assertThat(fragment.getFileUrl()).isEqualTo("/api/documents/1/file");
        assertThat(fragment.getFragment()).containsIgnoringCase("dolor");
        assertThat(fragment.getScore()).isGreaterThan(0.0f);
    }

    @Test
    void searchFuzzy_usesContentWhenNoHighlight() throws Exception {
        StandardAnalyzer analyzer = new StandardAnalyzer();
        ByteBuffersDirectory directory = new ByteBuffersDirectory();
        addDocument(directory, analyzer, "2", "SpecialTitle", "Bob", "rapport", "02/02/2026 09:00:00", "file.pdf", "alpha beta");

        when(luceneConfig.getDocumentsAnalyzer()).thenReturn(analyzer);
        when(luceneConfig.getDocumentsIndex()).thenReturn(directory);

        LuceneSearchService service = new LuceneSearchService(luceneConfig);

        SearchRequestDTO request = new SearchRequestDTO();
        request.setQuery("SpecialTitle");
        SearchResultDTO result = service.search(request);

        assertThat(result.getNbResults()).isEqualTo(1);
        assertThat(result.getFragments()).hasSize(1);
        SearchFragmentDTO fragment = result.getFragments().get(0);
        assertThat(fragment.getFragment()).isEqualTo("alpha beta");
    }

    @Test
    void searchFuzzy_whenIndexEmpty_returnsEmptyResult() throws Exception {
        StandardAnalyzer analyzer = new StandardAnalyzer();
        ByteBuffersDirectory directory = new ByteBuffersDirectory();
        createEmptyIndex(directory, analyzer);

        when(luceneConfig.getDocumentsAnalyzer()).thenReturn(analyzer);
        when(luceneConfig.getDocumentsIndex()).thenReturn(directory);

        LuceneSearchService service = new LuceneSearchService(luceneConfig);

        SearchRequestDTO request = new SearchRequestDTO();
        request.setQuery("anything");
        SearchResultDTO result = service.search(request);

        assertThat(result.getNbResults()).isZero();
        assertThat(result.getFragments()).isEmpty();
    }

    @Test
    void search_withCategoryFilter_returnsMatchingFragments() throws Exception {
        StandardAnalyzer analyzer = new StandardAnalyzer();
        ByteBuffersDirectory directory = new ByteBuffersDirectory();
        addDocument(directory, analyzer, "3", "Doc A", "Alice", "note", "03/03/2026 09:00:00", "a.pdf", "dolor sit amet");
        addDocument(directory, analyzer, "4", "Doc B", "Bob", "rapport", "03/03/2026 09:05:00", "b.pdf", "dolor sit amet");

        when(luceneConfig.getDocumentsAnalyzer()).thenReturn(analyzer);
        when(luceneConfig.getDocumentsIndex()).thenReturn(directory);

        LuceneSearchService service = new LuceneSearchService(luceneConfig);

        SearchRequestDTO request = new SearchRequestDTO();
        request.setQuery("dolor");
        request.setCategory("note");
        SearchResultDTO result = service.search(request);

        assertThat(result.getNbResults()).isEqualTo(1);
        assertThat(result.getFragments()).hasSize(1);
        assertThat(result.getFragments().get(0).getId()).isEqualTo("3");
    }

    @Test
    void search_withDateRange_returnsMatchingFragments() throws Exception {
        StandardAnalyzer analyzer = new StandardAnalyzer();
        ByteBuffersDirectory directory = new ByteBuffersDirectory();
        addDocument(directory, analyzer, "5", "Doc C", "Alice", "note", "01/03/2026 10:00:00", "c.pdf", "dolor sit amet");
        addDocument(directory, analyzer, "6", "Doc D", "Alice", "note", "15/04/2026 10:00:00", "d.pdf", "dolor sit amet");

        when(luceneConfig.getDocumentsAnalyzer()).thenReturn(analyzer);
        when(luceneConfig.getDocumentsIndex()).thenReturn(directory);

        LuceneSearchService service = new LuceneSearchService(luceneConfig);

        SearchRequestDTO request = new SearchRequestDTO();
        request.setQuery("dolor");
        request.setDateFrom(LocalDate.of(2026, 3, 1));
        request.setDateTo(LocalDate.of(2026, 3, 31));
        SearchResultDTO result = service.search(request);

        assertThat(result.getNbResults()).isEqualTo(1);
        assertThat(result.getFragments()).hasSize(1);
        assertThat(result.getFragments().get(0).getId()).isEqualTo("5");
    }

    private static void addDocument(ByteBuffersDirectory directory,
                                    StandardAnalyzer analyzer,
                                    String id,
                                    String name,
                                    String author,
                                    String category,
                                    String date,
                                    String filename,
                                    String content) throws IOException {
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
            writer.addDocument(doc);
            writer.commit();
        }
    }

    private static void createEmptyIndex(ByteBuffersDirectory directory, StandardAnalyzer analyzer) throws IOException {
        IndexWriterConfig config = new IndexWriterConfig(analyzer);
        try (IndexWriter writer = new IndexWriter(directory, config)) {
            writer.commit();
        }
    }
}

