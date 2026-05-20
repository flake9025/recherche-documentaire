package fr.vvlabs.recherche.service.search;

import fr.vvlabs.recherche.dto.SearchRequestDTO;
import fr.vvlabs.recherche.dto.SearchResultDTO;

public interface SearchService {

    String getType();

    SearchResultDTO search(SearchRequestDTO request) throws Exception;

    boolean isSearchStoreEmpty() throws Exception;
}
