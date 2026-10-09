package fr.vvlabs.recherche.dto;

import com.fasterxml.jackson.annotation.JsonIgnore;
import lombok.Data;

import java.util.ArrayList;
import java.util.List;

/**
 * Agrege les resultats renvoyes par un moteur de recherche.
 */
@Data
public class SearchResultDTO {

    private int nbResults = 0;
    private List<SearchFragmentDTO> fragments = new ArrayList<>();
    private SearchMetricsDTO metrics;
    private SummaryDTO summary;
    private String summaryError;

    public record SummaryDTO(String text, String model, List<SummarySourceDTO> sources) { }
    public record SummarySourceDTO(int number, String documentId, String title, String fileUrl, boolean partial) { }

    /** Temps de génération de l'embedding BERT (ms). Carrier interne, non exposé en API. */
    @JsonIgnore
    private long embeddingTimeMs;
}
