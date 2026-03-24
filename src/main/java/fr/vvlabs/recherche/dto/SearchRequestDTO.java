package fr.vvlabs.recherche.dto;

import lombok.Data;

import java.time.LocalDate;

@Data
public class SearchRequestDTO {
    private String query;
    private String category;
    private String author;
    private LocalDate dateFrom;
    private LocalDate dateTo;
    private String sort;
}

