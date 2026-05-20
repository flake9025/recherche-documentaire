package fr.vvlabs.recherche.dto;

import lombok.Data;

/**
 * Represente un fragment de resultat de recherche.
 */
@Data
public class SearchFragmentDTO {
    private String id;
    private String name;
    private String author;
    private String category;
    private String date;
    private String filename;
    private String fileUrl;
    private String fragment;
    private float score;
}
