package fr.vvlabs.recherche.service.index.embeddings.store;

import fr.vvlabs.recherche.service.index.embeddings.BertEmbeddingDocument;

import java.time.LocalDate;
import java.util.Collection;
import java.util.List;

/**
 * Abstraction du store d'embeddings BERT.
 */
public interface BertEmbeddingsStore {

    /**
     * Retourne le type technique du store.
     *
     * @return type du store
     */
    String getType();

    /**
     * Insere ou remplace un document indexe.
     *
     * @param document document a enregistrer
     */
    void upsert(BertEmbeddingDocument document);

    /**
     * Retourne tous les documents connus du store.
     *
     * @return documents stockes
     */
    List<BertEmbeddingDocument> findAll();

    /**
     * Retourne le nombre de documents stockes.
     *
     * @return cardinalite du store
     */
    long count();

    /**
     * Vide completement le store.
     */
    void clear();

    /**
     * Remplace l'ensemble du contenu du store.
     *
     * @param entities nouveaux documents
     */
    void replaceAll(Collection<BertEmbeddingDocument> entities);

    /**
     * Recherche des candidats semantiques dans le store.
     *
     * @param query requete semantique
     * @return candidats avec score semantique
     */
    List<BertEmbeddingMatch> search(BertEmbeddingsStoreQuery query);

    /**
     * Criteres de recherche delegues au store.
     *
     * @param queryVector vecteur de la requete
     * @param category filtre categorie
     * @param author filtre auteur
     * @param dateFrom borne basse incluse
     * @param dateTo borne haute incluse
     * @param limit nombre max de candidats, 0 pour illimite
     */
    record BertEmbeddingsStoreQuery(
            float[] queryVector,
            String category,
            String author,
            LocalDate dateFrom,
            LocalDate dateTo,
            int limit
    ) {
    }

    /**
     * Candidat retourne par le store avec son score semantique.
     *
     * @param document document trouve
     * @param semanticScore score semantique brut
     */
    record BertEmbeddingMatch(
            BertEmbeddingDocument document,
            float semanticScore
    ) {
    }
}
