package fr.vvlabs.recherche.service.index.embeddings.faiss;

import java.time.LocalDateTime;

/**
 * Document echange avec le service distant FAISS.
 *
 * @param documentId identifiant du document
 * @param title titre documentaire
 * @param author auteur de depot
 * @param category categorie documentaire
 * @param filename nom du fichier source
 * @param depotDateTime date de depot
 * @param contentText contenu indexe
 * @param embedding vecteur du document
 */
public record FaissRemoteStoreDocument(
        Long documentId,
        String title,
        String author,
        String category,
        String filename,
        LocalDateTime depotDateTime,
        String contentText,
        float[] embedding
) {
}
