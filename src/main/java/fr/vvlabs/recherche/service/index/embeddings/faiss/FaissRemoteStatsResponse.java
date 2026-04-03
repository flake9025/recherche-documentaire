package fr.vvlabs.recherche.service.index.embeddings.faiss;

/**
 * Reponse de statistiques du service distant FAISS.
 *
 * @param count nombre de documents indexes
 */
public record FaissRemoteStatsResponse(
        long count
) {
}
