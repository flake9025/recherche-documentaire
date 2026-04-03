package fr.vvlabs.recherche.service.index.embeddings.faiss;

import java.util.List;

/**
 * Reponse du service distant FAISS.
 *
 * @param matches candidats trouves
 */
public record FaissRemoteSearchResponse(
        List<FaissRemoteSearchMatch> matches
) {
}
