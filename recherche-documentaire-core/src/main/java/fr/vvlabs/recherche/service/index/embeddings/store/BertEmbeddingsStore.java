package fr.vvlabs.recherche.service.index.embeddings.store;

import fr.vvlabs.recherche.service.index.embeddings.BertEmbeddingDocument;

import java.time.LocalDate;
import java.util.Collection;
import java.util.List;
import java.util.stream.Collectors;

/**
 * Abstraction du store d'embeddings BERT.
 *
 * <p>Le store raisonne au niveau <b>chunk</b>: chaque {@link BertEmbeddingDocument}
 * represente un fragment de document et est identifie par sa cle de point
 * ({@link BertEmbeddingDocument#pointId()}). Un meme {@code documentId} peut donc
 * apparaitre dans plusieurs entrees. Le regroupement par document (best-chunk) est
 * effectue par la couche de recherche.</p>
 */
public interface BertEmbeddingsStore {

    /**
     * Retourne le type technique du store.
     *
     * @return type du store
     */
    String getType();

    /**
     * Insere ou remplace un chunk indexe (cle = {@link BertEmbeddingDocument#pointId()}).
     *
     * @param document chunk a enregistrer
     */
    void upsert(BertEmbeddingDocument document);

    /**
     * Supprime tous les chunks d'un document. Necessaire avant la reindexation d'un
     * document pour eviter des chunks obsoletes si son nombre de chunks a diminue.
     *
     * @param documentId identifiant du document a purger
     */
    void deleteByDocumentId(Long documentId);

    /**
     * Retourne tous les chunks connus du store.
     *
     * @return chunks stockes
     */
    List<BertEmbeddingDocument> findAll();

    /**
     * Retourne le nombre de chunks stockes.
     *
     * @return cardinalite du store (en chunks)
     */
    long count();

    /**
     * Retourne le nombre de documents distincts stockes.
     *
     * @return nombre de documents distincts
     */
    default long countDocuments() {
        return findAll().stream()
                .map(BertEmbeddingDocument::documentId)
                .distinct()
                .count();
    }

    /**
     * Vide completement le store.
     */
    void clear();

    /**
     * Remplace l'ensemble du contenu du store.
     *
     * @param entities nouveaux chunks
     */
    void replaceAll(Collection<BertEmbeddingDocument> entities);

    /**
     * Recherche des chunks candidats semantiques dans le store.
     *
     * <p>Le store renvoie des correspondances au niveau chunk; plusieurs peuvent
     * partager le meme {@code documentId}. Le sur-echantillonnage necessaire pour
     * compenser cette multiplicite est porte par {@link BertEmbeddingsStoreQuery#limit()}.</p>
     *
     * @param query requete semantique
     * @return candidats avec score semantique
     */
    List<BertEmbeddingMatch> search(BertEmbeddingsStoreQuery query);

    /**
     * Reduit une liste de correspondances chunk en gardant le meilleur chunk par document.
     *
     * @param matches correspondances au niveau chunk
     * @return meilleure correspondance par documentId, triee par score decroissant
     */
    static List<BertEmbeddingMatch> bestChunkPerDocument(List<BertEmbeddingMatch> matches) {
        return matches.stream()
                .collect(Collectors.groupingBy(match -> match.document().documentId()))
                .values().stream()
                .map(group -> group.stream()
                        .max((a, b) -> Float.compare(a.semanticScore(), b.semanticScore()))
                        .orElseThrow())
                .sorted((a, b) -> Float.compare(b.semanticScore(), a.semanticScore()))
                .toList();
    }

    /**
     * Criteres de recherche delegues au store.
     *
     * @param queryVector vecteur de la requete
     * @param category filtre categorie
     * @param author filtre auteur
     * @param dateFrom borne basse incluse
     * @param dateTo borne haute incluse
     * @param limit nombre max de chunks candidats, 0 pour illimite
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
     * @param document chunk trouve
     * @param semanticScore score semantique brut
     */
    record BertEmbeddingMatch(
            BertEmbeddingDocument document,
            float semanticScore
    ) {
    }
}
