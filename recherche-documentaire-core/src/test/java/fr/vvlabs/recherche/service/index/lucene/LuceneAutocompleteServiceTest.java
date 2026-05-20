package fr.vvlabs.recherche.service.index.lucene;

import fr.vvlabs.recherche.config.LuceneConfig;
import fr.vvlabs.recherche.dto.DocumentDTO;
import fr.vvlabs.recherche.service.document.DocumentService;
import fr.vvlabs.recherche.service.index.lucene.LuceneAutocompleteService;
import org.apache.lucene.search.suggest.InputIterator;
import org.apache.lucene.search.suggest.Lookup;
import org.apache.lucene.search.suggest.analyzing.AnalyzingInfixSuggester;
import org.apache.lucene.util.BytesRef;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Captor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class LuceneAutocompleteServiceTest {

    @Mock
    private LuceneConfig luceneConfig;

    @Mock
    private DocumentService documentService;

    @Mock
    private AnalyzingInfixSuggester authorsSuggester;

    @Captor
    private ArgumentCaptor<InputIterator> inputIteratorCaptor;

    private LuceneAutocompleteService service;

    @BeforeEach
    void setUp() throws IOException {
        lenient().when(luceneConfig.getAuthorsSuggester()).thenReturn(authorsSuggester);
        lenient().doNothing().when(authorsSuggester).commit();
        service = new LuceneAutocompleteService(luceneConfig, documentService);
    }

    @Test
    void canonicalizeAuthor_normalizesCaseAccentsAndSeparators() {
        assertThat(service.canonicalizeAuthor("  MAGGI, Marie-France  ")).isEqualTo("maggi marie france");
        assertThat(service.canonicalizeAuthor("màggi   marie france")).isEqualTo("maggi marie france");
    }

    @Test
    void buildAuthorIndex_groupsVariantsUnderOneSuggestion() throws Exception {
        when(documentService.findAll()).thenReturn(List.of(
                new DocumentDTO().setAuteur("MAGGI, Marie-France"),
                new DocumentDTO().setAuteur("Maggi Marie France"),
                new DocumentDTO().setAuteur("màggi marie-france")
        ));

        service.buildAuthorIndex();

        verify(authorsSuggester).build(inputIteratorCaptor.capture());
        List<SuggestionEntry> entries = readEntries(inputIteratorCaptor.getValue());
        assertThat(entries).hasSize(1);
        assertThat(entries.getFirst().text()).isEqualTo("Maggi Marie France");
        assertThat(entries.getFirst().weight()).isEqualTo(3L);
    }

    @Test
    void suggest_deduplicatesVariantsReturnedBySuggester() throws Exception {
        List<Lookup.LookupResult> lookupResults = List.of(
                new Lookup.LookupResult("MAGGI, Marie-France", 2L),
                new Lookup.LookupResult("Maggi Marie France", 3L),
                new Lookup.LookupResult("Martin Alice", 1L)
        );
        when(authorsSuggester.lookup("mag", false, 10)).thenReturn(lookupResults);

        List<LuceneAutocompleteService.AuthorSuggestion> suggestions = service.suggest("mag", 10);

        assertThat(suggestions).hasSize(2);
        assertThat(suggestions.getFirst().getAuthor()).isEqualTo("Maggi Marie France");
        assertThat(suggestions.getFirst().getWeight()).isEqualTo(5L);
    }

    private static List<SuggestionEntry> readEntries(InputIterator iterator) throws IOException {
        List<SuggestionEntry> entries = new ArrayList<>();
        BytesRef next;
        while ((next = iterator.next()) != null) {
            entries.add(new SuggestionEntry(next.utf8ToString(), iterator.weight()));
        }
        return entries;
    }

    private record SuggestionEntry(String text, long weight) {
    }
}
