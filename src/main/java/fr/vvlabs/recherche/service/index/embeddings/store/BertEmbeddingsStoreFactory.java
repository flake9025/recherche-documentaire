package fr.vvlabs.recherche.service.index.embeddings.store;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * Resout le store d'embeddings BERT a utiliser.
 */
@Component
@Slf4j
public class BertEmbeddingsStoreFactory {

    private final Map<String, BertEmbeddingsStore> stores;
    private final String defaultStore;

    public BertEmbeddingsStoreFactory(
            List<BertEmbeddingsStore> stores,
            @Value("${app.embeddings.store.default:hashmap}") String defaultStore
    ) {
        this.stores = stores.stream()
                .peek(store -> log.info("BERT embeddings store detected: {}", store.getType()))
                .collect(Collectors.toMap(BertEmbeddingsStore::getType, Function.identity()));
        this.defaultStore = defaultStore;
    }

    /**
     * Retourne le store BERT par defaut.
     *
     * @return store selectionne
     */
    public BertEmbeddingsStore getDefaultStore() {
        return getStore(defaultStore);
    }

    /**
     * Retourne le store BERT associe au type demande.
     *
     * @param type type technique du store
     * @return store selectionne
     */
    public BertEmbeddingsStore getStore(String type) {
        BertEmbeddingsStore store = stores.get(type);
        if (store == null) {
            throw new IllegalStateException("Unknown BERT embeddings store: " + type);
        }
        return store;
    }
}
