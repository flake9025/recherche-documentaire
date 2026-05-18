package fr.vvlabs.recherche.service.index.embeddings.store.qdrant;

import fr.vvlabs.recherche.service.index.embeddings.BertEmbeddingDocument;
import fr.vvlabs.recherche.service.index.embeddings.BertEmbeddingsStoreType;
import fr.vvlabs.recherche.service.index.embeddings.store.BertEmbeddingsStore.BertEmbeddingMatch;
import fr.vvlabs.recherche.service.index.embeddings.store.BertEmbeddingsStore.BertEmbeddingsStoreQuery;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
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
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

class QdrantBertEmbeddingsStoreTest {

    @Test
    void getType_returnsQdrant() {
        assertThat(newStore(RestClient.builder(), true).getType()).isEqualTo(BertEmbeddingsStoreType.QDRANT);
    }

    @Test
    void upsert_createsCollectionAndStoresPoint() {
        RestClient.Builder builder = RestClient.builder();
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        QdrantBertEmbeddingsStore store = newStore(builder, true);

        server.expect(requestTo("http://localhost:6333/collections/test-embeddings"))
                .andExpect(method(HttpMethod.GET))
                .andRespond(withStatus(HttpStatus.NOT_FOUND));
        server.expect(requestTo("http://localhost:6333/collections/test-embeddings"))
                .andExpect(method(HttpMethod.PUT))
                .andExpect(content().json("""
                        {
                          "vectors": {
                            "size": 2,
                            "distance": "Cosine"
                          }
                        }
                        """, true))
                .andRespond(withSuccess("{}", MediaType.APPLICATION_JSON));
        server.expect(requestTo("http://localhost:6333/collections/test-embeddings/points?wait=true"))
                .andExpect(method(HttpMethod.PUT))
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
                .andExpect(content().json("""
                        {
                          "points": [
                            {
                              "id": 12,
                              "vector": [0.1, 0.9],
                              "payload": {
                                "documentId": 12,
                                "title": "Titre",
                                "author": "Marie-France FROMAGE",
                                "category": "NOTE",
                                "filename": "doc.pdf",
                                "contentText": "contenu",
                                "authorNormalized": "marie-france fromage",
                                "categoryNormalized": "note"
                              }
                            }
                          ]
                        }
                        """, false))
                .andRespond(withSuccess("{}", MediaType.APPLICATION_JSON));

        store.upsert(new BertEmbeddingDocument(
                12L,
                "Titre",
                "Marie-France FROMAGE",
                "NOTE",
                "doc.pdf",
                LocalDateTime.of(2025, 1, 10, 9, 0),
                "contenu",
                new float[]{0.1f, 0.9f}
        ));

        server.verify();
    }

    @Test
    void search_callsRemoteServiceAndMapsResponse() {
        RestClient.Builder builder = RestClient.builder();
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        QdrantBertEmbeddingsStore store = newStore(builder, true);

        server.expect(requestTo("http://localhost:6333/collections/test-embeddings"))
                .andExpect(method(HttpMethod.GET))
                .andRespond(withSuccess("{}", MediaType.APPLICATION_JSON));
        server.expect(requestTo("http://localhost:6333/collections/test-embeddings/points/search"))
                .andExpect(method(HttpMethod.POST))
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
                .andExpect(content().json("""
                        {
                          "vector": [1.0, 0.0],
                          "limit": 5,
                          "filter": {
                            "must": [
                              {"key": "categoryNormalized", "match": {"value": "note"}},
                              {"key": "authorNormalized", "match": {"value": "auteur"}},
                              {"key": "depotEpochMillis", "range": {"gte": 1735689600000, "lte": 1738367999999}}
                            ]
                          },
                          "with_payload": true,
                          "with_vector": true
                        }
                        """, true))
                .andRespond(withSuccess("""
                        {
                          "result": [
                            {
                              "id": 42,
                              "score": 0.87,
                              "payload": {
                                "documentId": 42,
                                "title": "Titre",
                                "author": "Auteur",
                                "category": "NOTE",
                                "filename": "doc.pdf",
                                "depotDateTime": "2025-01-10T09:00:00",
                                "contentText": "contenu"
                              },
                              "vector": [1.0, 2.0]
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
        assertThat(results.getFirst().document().author()).isEqualTo("Auteur");
        assertThat(results.getFirst().semanticScore()).isEqualTo(0.87f);
    }

    @Test
    void findAll_returnsEmptyWhenCollectionDoesNotExist() {
        RestClient.Builder builder = RestClient.builder();
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        QdrantBertEmbeddingsStore store = newStore(builder, true);

        server.expect(requestTo("http://localhost:6333/collections/test-embeddings"))
                .andExpect(method(HttpMethod.GET))
                .andRespond(withStatus(HttpStatus.NOT_FOUND));

        assertThat(store.findAll()).isEmpty();
        server.verify();
    }

    @Test
    void upsert_throwsWhenStoreDisabled() {
        QdrantBertEmbeddingsStore store = newStore(RestClient.builder(), false);
        BertEmbeddingDocument document = new BertEmbeddingDocument(1L, "Titre", "Auteur", "NOTE", "doc.pdf", null, null, new float[]{1.0f});

        assertThatThrownBy(() -> store.upsert(document))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Qdrant store is disabled");
    }

    private static QdrantBertEmbeddingsStore newStore(RestClient.Builder builder, boolean enabled) {
        return new QdrantBertEmbeddingsStore(
                builder,
                "http://localhost:6333",
                "",
                "test-embeddings",
                enabled,
                64
        );
    }
}
