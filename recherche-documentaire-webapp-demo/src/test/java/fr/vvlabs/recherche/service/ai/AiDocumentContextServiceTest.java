package fr.vvlabs.recherche.service.ai;

import fr.vvlabs.recherche.dto.DocumentDTO;
import fr.vvlabs.recherche.dto.SearchFragmentDTO;
import fr.vvlabs.recherche.service.document.DocumentAccessService;
import fr.vvlabs.recherche.service.document.DocumentService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

import java.io.IOException;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class AiDocumentContextServiceTest {
    private final DocumentService documents = mock(DocumentService.class);
    private final DocumentAccessService access = mock(DocumentAccessService.class);
    private final AiDocumentContextService context = new AiDocumentContextService(documents, access);
    private final DocumentDTO metadata = new DocumentDTO().setId(10L).setTitre("Rapport fictif");

    @BeforeEach
    void setup() throws Exception {
        when(documents.findByIds(Set.of(10L))).thenReturn(List.of(metadata));
        when(documents.getFileText(metadata)).thenReturn("Texte integral du rapport fictif.");
    }

    @Test
    void readsAuthoritativeContentOnlyForDistinctSelectedDocuments() throws Exception {
        var sources = context.readSources(List.of(fragment("10"), fragment("10"), fragment("20")), 1);
        assertThat(sources).containsExactly(new AiDocumentContextService.DocumentText(
                10L, "Rapport fictif", "Texte integral du rapport fictif."));
        verify(documents).findByIds(Set.of(10L));
        verify(documents, times(1)).getFileText(metadata);
        verify(access, never()).requireRead(20L);
    }

    @Test
    void deniesBeforeAnyMetadataOrFileRead() {
        doThrow(new ResponseStatusException(HttpStatus.NOT_FOUND)).when(access).requireRead(10L);
        assertThatThrownBy(() -> context.readSources(List.of(fragment("10")), 1))
                .isInstanceOf(ResponseStatusException.class);
        verifyNoInteractions(documents);
    }

    @Test
    void rechecksAccessAfterMetadataLookupBeforeExtraction() throws Exception {
        when(documents.findByIds(Set.of(10L))).thenAnswer(call -> {
            doThrow(new ResponseStatusException(HttpStatus.NOT_FOUND)).when(access).requireRead(10L);
            return List.of(metadata);
        });
        assertThatThrownBy(() -> context.readSources(List.of(fragment("10")), 1))
                .isInstanceOf(ResponseStatusException.class);
        verify(documents, never()).getFileText(any(DocumentDTO.class));
    }

    @Test
    void missingMetadataAndEmptyExtractionAreExplicit() throws Exception {
        when(documents.findByIds(Set.of(10L))).thenReturn(List.of());
        assertThatThrownBy(() -> context.readSources(List.of(fragment("10")), 1))
                .hasMessageContaining("Metadonnees sources indisponibles");
        when(documents.findByIds(Set.of(10L))).thenReturn(List.of(metadata));
        when(documents.getFileText(metadata)).thenReturn("");
        assertThatThrownBy(() -> context.readSources(List.of(fragment("10")), 1))
                .hasMessageContaining("Texte source inexploitable");
    }

    @Test
    void shortSourceKeepsTablesNegationDatesAndQualificationsIntact() throws Exception {
        String text = "Rapport fictif du 12/05/2030\nControle du circuit : NEGATIF\n"
                + "Indice < 1,00 : absence de fuite\nConclusion sous reserve des conditions du controle.";
        var selection = context.select(text, "est-ce que le circuit fuit ?", 1000);
        assertThat(selection.text()).isEqualTo(text);
        assertThat(selection.partial()).isFalse();
    }

    @Test
    void selectsRelevantPassagesFarBeyondTheFirstPreviewAndMarksOmissions() throws Exception {
        String text = "Introduction generale. ".repeat(250)
                + "\nControle du circuit : NEGATIF. Indice 0,42. Conclusion sous reserve des conditions du controle.\n"
                + "Conseils generaux de prevention. ".repeat(250);
        var selection = context.select(text, "resultat controle circuit", 1500);
        assertThat(selection.text()).hasSizeLessThanOrEqualTo(1500)
                .contains("Controle du circuit : NEGATIF", "0,42", "sous reserve");
        assertThat(selection.partial()).isTrue();
    }

    @Test
    void includesSeparatedRelevantPassagesInSourceOrder() throws Exception {
        String text = "Controle du circuit : CONFORME.\n" + "Note sans rapport. ".repeat(180)
                + "\nControle du circuit : NON CONFORME lors de la seconde verification.\n";
        var selection = context.select(text, "controle circuit", 1200);
        assertThat(selection.text()).hasSizeLessThanOrEqualTo(1200)
                .contains("CONFORME", "NON CONFORME", "[Passage non transmis]");
        assertThat(selection.text().indexOf("CONFORME")).isLessThan(selection.text().indexOf("NON CONFORME"));
    }

    @Test
    void noLexicalMatchRetainsOpeningAndEndingRatherThanOnlyThePrefix() throws Exception {
        String text = "ENTETE_FICTIF\n" + "Informations generales sans rapport. ".repeat(100)
                + "\nCONCLUSION_FICTIVE\n";
        var selection = context.select(text, "", 1000);
        assertThat(selection.text()).hasSizeLessThanOrEqualTo(1000).contains("ENTETE_FICTIF", "CONCLUSION_FICTIVE");
    }

    @Test
    void pathologicalWhitespaceFreeAndUnicodeInputsStayWithinBudget() throws IOException {
        for (String text : List.of("x".repeat(4000), "\uD83D\uDE80".repeat(2000), " ligne \n".repeat(600))) {
            for (int budget : List.of(50, 200, 501, 1000)) {
                var selection = context.select(text, "question", budget);
                assertThat(selection.text()).hasSizeLessThanOrEqualTo(budget).isNotBlank();
            }
        }
    }

    private SearchFragmentDTO fragment(String id) {
        var fragment = new SearchFragmentDTO();
        fragment.setId(id);
        fragment.setFragment("Apercu qui ne doit pas servir de contenu source");
        return fragment;
    }
}
