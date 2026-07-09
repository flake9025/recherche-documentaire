package fr.vvlabs.recherche.service.index.embeddings.faiss;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import java.time.LocalDateTime;

/**
 * Document (chunk) echange avec le service distant FAISS.
 *
 * <p>{@code chunkIndex}/{@code chunkCount} sont des objets (nullable) pour tolerer
 * des reponses distantes anterieures qui ne renseignaient pas ces champs.</p>
 *
 * @param documentId identifiant du document
 * @param chunkIndex position du chunk dans le document (0-based)
 * @param chunkCount nombre total de chunks du document
 * @param title titre documentaire
 * @param author auteur de depot
 * @param category categorie documentaire
 * @param filename nom du fichier source
 * @param depotDateTime date de depot
 * @param contentText texte du chunk
 * @param embedding vecteur du chunk
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record FaissRemoteStoreDocument(
        Long documentId,
        Integer chunkIndex,
        Integer chunkCount,
        String title,
        String author,
        String category,
        String filename,
        LocalDateTime depotDateTime,
        String contentText,
        float[] embedding
) {
}
