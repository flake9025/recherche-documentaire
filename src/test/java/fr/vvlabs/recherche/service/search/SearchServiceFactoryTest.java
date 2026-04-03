package fr.vvlabs.recherche.service.search;

import fr.vvlabs.recherche.dto.SearchRequestDTO;
import fr.vvlabs.recherche.dto.SearchResultDTO;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class SearchServiceFactoryTest {

    @Test
    void getDefaultSearchService_returnsConfiguredService() {
        SearchService service = new StubSearchService("bert");
        SearchServiceFactory factory = new SearchServiceFactory(List.of(service), "bert");

        assertThat(factory.getDefaultSearchService()).isSameAs(service);
    }

    @Test
    void getSearchService_throwsWhenUnknown() {
        SearchServiceFactory factory = new SearchServiceFactory(List.of(new StubSearchService("lucene")), "lucene");

        assertThatThrownBy(() -> factory.getSearchService("bert"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Unknown search service");
    }

    private record StubSearchService(String type) implements SearchService {
        @Override
        public String getType() {
            return type;
        }

        @Override
        public SearchResultDTO search(SearchRequestDTO request) {
            return new SearchResultDTO();
        }

        @Override
        public boolean isSearchStoreEmpty() {
            return false;
        }
    }
}
