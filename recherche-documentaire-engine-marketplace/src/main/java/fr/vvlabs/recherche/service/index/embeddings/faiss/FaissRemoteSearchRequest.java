package fr.vvlabs.recherche.service.index.embeddings.faiss;

import java.time.LocalDate;

/**
 * Requete envoyee au service distant FAISS.
 *
 * @param queryVector vecteur de requete
 * @param category filtre categorie
 * @param author filtre auteur
 * @param dateFrom borne basse incluse
 * @param dateTo borne haute incluse
 * @param limit nombre maximum de candidats
 */
public record FaissRemoteSearchRequest(
        float[] queryVector,
        String category,
        String author,
        LocalDate dateFrom,
        LocalDate dateTo,
        int limit
) {
}
