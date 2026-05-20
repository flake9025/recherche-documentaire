package fr.vvlabs.recherche.service.index.embeddings.store;

import fr.vvlabs.recherche.service.index.embeddings.BertEmbeddingsStoreType;
import fr.vvlabs.recherche.service.index.embeddings.store.hashmap.HashMapBertEmbeddingsStore;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class BertEmbeddingsStoreFactoryTest {

    @Test
    void getDefaultStore_returnsConfiguredStore() {
        BertEmbeddingsStore store = new HashMapBertEmbeddingsStore();
        BertEmbeddingsStoreFactory factory = new BertEmbeddingsStoreFactory(
                java.util.List.of(store),
                BertEmbeddingsStoreType.HASHMAP
        );

        assertThat(factory.getDefaultStore()).isSameAs(store);
    }

    @Test
    void getStore_throwsWhenTypeUnknown() {
        BertEmbeddingsStoreFactory factory = new BertEmbeddingsStoreFactory(
                java.util.List.of(new HashMapBertEmbeddingsStore()),
                BertEmbeddingsStoreType.HASHMAP
        );

        assertThatThrownBy(() -> factory.getStore("unknown"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Unknown BERT embeddings store");
    }
}
