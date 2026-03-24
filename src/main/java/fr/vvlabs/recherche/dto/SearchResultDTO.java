package fr.vvlabs.recherche.dto;

import lombok.Data;

import java.util.ArrayList;
import java.util.List;

@Data
public class SearchResultDTO {

    private int nbResults = 0;
    private List<SearchFragmentDTO> fragments = new ArrayList<>();
}

