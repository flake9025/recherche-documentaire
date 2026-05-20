package fr.vvlabs.recherche.service.index.embeddings.store.faiss;

import fr.vvlabs.recherche.service.index.embeddings.BertEmbeddingDocument;
import fr.vvlabs.recherche.service.index.embeddings.store.BertEmbeddingsStore.BertEmbeddingMatch;
import fr.vvlabs.recherche.service.index.embeddings.store.BertEmbeddingsStore.BertEmbeddingsStoreQuery;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.content;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

class FaissRemoteBertEmbeddingsStoreTest {

    @Test
    void search_callsRemoteServiceAndMapsResponse() {
        RestClient.Builder builder = RestClient.builder();
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        FaissRemoteBertEmbeddingsStore store = new FaissRemoteBertEmbeddingsStore(
                builder,
                "http://localhost:8090",
                true
        );

        server.expect(requestTo("http://localhost:8090/api/faiss/search"))
                .andExpect(method(HttpMethod.POST))
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
                .andRespond(withSuccess("""
                        {
                          "matches": [
                            {
                              "document": {
                                "documentId": 42,
                                "title": "Titre",
                                "author": "Auteur",
                                "category": "NOTE",
                                "filename": "doc.pdf",
                                "depotDateTime": "2025-01-10T09:00:00",
                                "contentText": "contenu",
                                "embedding": [1.0, 2.0]
                              },
                              "semanticScore": 0.87
                            }
                          ]
                        }
                        """, MediaType.APPLICATION_JSON));

        List<BertEmbeddingMatch> results = store.search(new BertEmbeddingsStoreQuery(
                new float[]{1.0f, 0.0f},
                "NOTE",
                "Auteur",
                LocalDate.of(2025, 1, 1),
                LocalDate.of(2025, 1, 31),
                5
        ));

        server.verify();
        assertThat(results).hasSize(1);
        assertThat(results.getFirst().document().documentId()).isEqualTo(42L);
        assertThat(results.getFirst().semanticScore()).isEqualTo(0.87f);
    }

    @Test
    void upsert_throwsWhenStoreDisabled() {
        FaissRemoteBertEmbeddingsStore store = new FaissRemoteBertEmbeddingsStore(
                RestClient.builder(),
                "http://localhost:8090",
                false
        );

        assertThatThrownBy(() -> store.upsert(new BertEmbeddingDocument(
                1L, "Titre", "Auteur", "NOTE", "doc.pdf", LocalDateTime.now(), "contenu", new float[]{1.0f}
        )))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("FAISS remote store is disabled");
    }
}
