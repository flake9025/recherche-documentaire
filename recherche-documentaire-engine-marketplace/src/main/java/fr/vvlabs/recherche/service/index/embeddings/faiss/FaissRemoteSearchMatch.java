package fr.vvlabs.recherche.service.index.embeddings.faiss;

/**
 * Candidat retourne par le service distant FAISS.
 *
 * @param document document trouve
 * @param semanticScore score semantique
 */
public record FaissRemoteSearchMatch(
        FaissRemoteStoreDocument document,
        float semanticScore
) {
}
