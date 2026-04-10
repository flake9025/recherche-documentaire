package fr.vvlabs.recherche.dto;

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
}
