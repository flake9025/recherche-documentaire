package fr.vvlabs.recherche.service.index.embeddings.store.milvus;

import fr.vvlabs.recherche.service.index.embeddings.BertEmbeddingDocument;
import fr.vvlabs.recherche.service.index.embeddings.BertEmbeddingsStoreType;
import fr.vvlabs.recherche.service.index.embeddings.store.BertEmbeddingsStore.BertEmbeddingsStoreQuery;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class MilvusBertEmbeddingsStoreTest {

    @Test
    void getType_returnsMilvus() {
        assertThat(new MilvusBertEmbeddingsStore().getType()).isEqualTo(BertEmbeddingsStoreType.MILVUS);
    }

    @Test
    void allOperations_throwUnsupportedOperation() {
        MilvusBertEmbeddingsStore store = new MilvusBertEmbeddingsStore();
        BertEmbeddingDocument document = new BertEmbeddingDocument(1L, "", "", "", "", null, null, new float[0]);

        assertThatThrownBy(() -> store.upsert(document)).isInstanceOf(UnsupportedOperationException.class);
        assertThatThrownBy(store::findAll).isInstanceOf(UnsupportedOperationException.class);
        assertThatThrownBy(store::count).isInstanceOf(UnsupportedOperationException.class);
        assertThatThrownBy(store::clear).isInstanceOf(UnsupportedOperationException.class);
        assertThatThrownBy(() -> store.replaceAll(java.util.List.of(document))).isInstanceOf(UnsupportedOperationException.class);
        assertThatThrownBy(() -> store.search(new BertEmbeddingsStoreQuery(new float[0], null, null, null, null, 0)))
                .isInstanceOf(UnsupportedOperationException.class);
    }
}
