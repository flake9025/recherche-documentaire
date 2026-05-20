package fr.vvlabs.recherche.service.index.embeddings.store.milvus;

import fr.vvlabs.recherche.service.index.embeddings.BertEmbeddingDocument;
import fr.vvlabs.recherche.service.index.embeddings.BertEmbeddingsStoreType;
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

class MilvusBertEmbeddingsStoreTest {

    @Test
    void getType_returnsMilvus() {
        assertThat(newStore(RestClient.builder(), true).getType()).isEqualTo(BertEmbeddingsStoreType.MILVUS);
    }

    @Test
    void upsert_createsCollectionAndStoresEntity() {
        RestClient.Builder builder = RestClient.builder();
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        MilvusBertEmbeddingsStore store = newStore(builder, true);

        server.expect(requestTo("http://localhost:19530/v2/vectordb/collections/has"))
                .andExpect(method(HttpMethod.POST))
                .andExpect(content().json("""
                        {"collectionName":"test-embeddings"}
                        """, true))
                .andRespond(withSuccess("""
                        {"code":0,"data":{"has":false}}
                        """, MediaType.APPLICATION_JSON));
        server.expect(requestTo("http://localhost:19530/v2/vectordb/collections/create"))
                .andExpect(method(HttpMethod.POST))
                .andExpect(content().json("""
                        {
                          "collectionName": "test-embeddings",
                          "dimension": 2,
                          "metricType": "COSINE",
                          "primaryFieldName": "documentId",
                          "idType": "Int64",
                          "vectorFieldName": "embedding",
                          "autoId": false,
                          "enableDynamicField": true
                        }
                        """, true))
                .andRespond(withSuccess("""
                        {"code":0}
                        """, MediaType.APPLICATION_JSON));
        server.expect(requestTo("http://localhost:19530/v2/vectordb/entities/upsert"))
                .andExpect(method(HttpMethod.POST))
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
                .andExpect(content().json("""
                        {
                          "collectionName": "test-embeddings",
                          "data": [
                            {
                              "documentId": 12,
                              "title": "Titre",
                              "author": "Marie-France FROMAGE",
                              "category": "NOTE",
                              "filename": "doc.pdf",
                              "depotDateTime": "2025-01-10T09:00:00",
                              "contentText": "contenu",
                              "authorNormalized": "marie-france fromage",
                              "categoryNormalized": "note",
                              "embedding": [0.1, 0.9]
                            }
                          ]
                        }
                        """, false))
                .andRespond(withSuccess("""
                        {"code":0}
                        """, MediaType.APPLICATION_JSON));

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
        MilvusBertEmbeddingsStore store = newStore(builder, true);

        server.expect(requestTo("http://localhost:19530/v2/vectordb/collections/has"))
                .andExpect(method(HttpMethod.POST))
                .andRespond(withSuccess("""
                        {"code":0,"data":{"has":true}}
                        """, MediaType.APPLICATION_JSON));
        server.expect(requestTo("http://localhost:19530/v2/vectordb/entities/search"))
                .andExpect(method(HttpMethod.POST))
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
                .andExpect(content().json("""
                        {
                          "collectionName": "test-embeddings",
                          "data": [[1.0, 0.0]],
                          "annsField": "embedding",
                          "limit": 5,
                          "outputFields": ["documentId", "title", "author", "category", "filename", "depotDateTime", "contentText", "embedding"],
                          "filter": "categoryNormalized == \\\"note\\\" && authorNormalized == \\\"auteur\\\" && depotEpochMillis >= 1735689600000 && depotEpochMillis <= 1738367999999"
                        }
                        """, true))
                .andRespond(withSuccess("""
                        {
                          "code": 0,
                          "data": [
                            {
                              "documentId": 42,
                              "title": "Titre",
                              "author": "Auteur",
                              "category": "NOTE",
                              "filename": "doc.pdf",
                              "depotDateTime": "2025-01-10T09:00:00",
                              "contentText": "contenu",
                              "embedding": [1.0, 2.0],
                              "distance": 0.87
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
        assertThat(results.getFirst().document().embedding()).containsExactly(1.0f, 2.0f);
        assertThat(results.getFirst().semanticScore()).isEqualTo(0.87f);
    }

    @Test
    void findAll_queriesMilvusAndMapsDocuments() {
        RestClient.Builder builder = RestClient.builder();
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        MilvusBertEmbeddingsStore store = newStore(builder, true);

        server.expect(requestTo("http://localhost:19530/v2/vectordb/collections/has"))
                .andExpect(method(HttpMethod.POST))
                .andRespond(withSuccess("""
                        {"code":0,"data":{"has":true}}
                        """, MediaType.APPLICATION_JSON));
        server.expect(requestTo("http://localhost:19530/v2/vectordb/entities/query"))
                .andExpect(method(HttpMethod.POST))
                .andExpect(content().json("""
                        {
                          "collectionName": "test-embeddings",
                          "filter": "documentId >= 0",
                          "outputFields": ["documentId", "title", "author", "category", "filename", "depotDateTime", "contentText", "embedding"],
                          "limit": 256,
                          "offset": 0
                        }
                        """, true))
                .andRespond(withSuccess("""
                        {
                          "code": 0,
                          "data": [
                            {
                              "documentId": 99,
                              "title": "Titre scroll",
                              "author": "Auteur scroll",
                              "category": "NOTE",
                              "filename": "scroll.pdf",
                              "depotDateTime": "2025-05-19T08:15:00",
                              "contentText": "contenu scroll",
                              "embedding": [0.5, 0.7]
                            }
                          ]
                        }
                        """, MediaType.APPLICATION_JSON));

        List<BertEmbeddingDocument> results = store.findAll();

        server.verify();
        assertThat(results).hasSize(1);
        assertThat(results.getFirst().documentId()).isEqualTo(99L);
        assertThat(results.getFirst().title()).isEqualTo("Titre scroll");
        assertThat(results.getFirst().filename()).isEqualTo("scroll.pdf");
        assertThat(results.getFirst().embedding()).containsExactly(0.5f, 0.7f);
    }

    @Test
    void count_queriesMilvusAggregate() {
        RestClient.Builder builder = RestClient.builder();
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        MilvusBertEmbeddingsStore store = newStore(builder, true);

        server.expect(requestTo("http://localhost:19530/v2/vectordb/collections/has"))
                .andExpect(method(HttpMethod.POST))
                .andRespond(withSuccess("""
                        {"code":0,"data":{"has":true}}
                        """, MediaType.APPLICATION_JSON));
        server.expect(requestTo("http://localhost:19530/v2/vectordb/entities/query"))
                .andExpect(method(HttpMethod.POST))
                .andExpect(content().json("""
                        {
                          "collectionName": "test-embeddings",
                          "filter": "documentId >= 0",
                          "outputFields": ["count(*)"],
                          "limit": 1,
                          "offset": 0
                        }
                        """, true))
                .andRespond(withSuccess("""
                        {
                          "code": 0,
                          "data": [
                            {"count(*)": "7"}
                          ]
                        }
                        """, MediaType.APPLICATION_JSON));

        assertThat(store.count()).isEqualTo(7L);
        server.verify();
    }

    @Test
    void upsert_throwsWhenStoreDisabled() {
        MilvusBertEmbeddingsStore store = newStore(RestClient.builder(), false);
        BertEmbeddingDocument document = new BertEmbeddingDocument(1L, "Titre", "Auteur", "NOTE", "doc.pdf", null, null, new float[]{1.0f});

        assertThatThrownBy(() -> store.upsert(document))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Milvus store is disabled");
    }

    private static MilvusBertEmbeddingsStore newStore(RestClient.Builder builder, boolean enabled) {
        return new MilvusBertEmbeddingsStore(
                builder,
                "http://localhost:19530",
                "",
                "test-embeddings",
                enabled,
                64
        );
    }
}
