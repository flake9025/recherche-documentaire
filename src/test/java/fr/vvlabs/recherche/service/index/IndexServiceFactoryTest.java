package fr.vvlabs.recherche.service.index;

import fr.vvlabs.recherche.dto.DocumentDTO;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class IndexServiceFactoryTest {

    @Test
    void getDefaultIndexService_returnsConfiguredService() {
        IndexService<Void> service = new StubIndexService("bert");
        IndexServiceFactory factory = new IndexServiceFactory(List.of(service), "bert");

        assertThat(factory.getDefaultIndexService()).isSameAs(service);
    }

    @Test
    void getIndexService_throwsWhenUnknown() {
        IndexServiceFactory factory = new IndexServiceFactory(List.of(new StubIndexService("lucene")), "lucene");

        assertThatThrownBy(() -> factory.getIndexService("bert"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Unknown Index");
    }

    private record StubIndexService(String type) implements IndexService<Void> {
        @Override
        public String getType() {
            return type;
        }

        @Override
        public void addDocumentToDocumentIndex(DocumentDTO documentDTO, String data) {
        }

        @Override
        public void addAuthorToAucompleteIndex(String author) {
        }

        @Override
        public Void loadDocumentIndexFromDatabase() {
            return null;
        }

        @Override
        public void saveDocumentIndexToDatabase() {
        }

        @Override
        public void clearDocumentsIndex() {
        }
    }
}
