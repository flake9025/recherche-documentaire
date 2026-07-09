package fr.vvlabs.recherche.service.index.embeddings.chunk;

/**
 * Fragment de texte issu du decoupage d'un document en vue de l'embedding.
 *
 * @param index    position du chunk dans le document (0-based)
 * @param total    nombre total de chunks du document
 * @param text     texte du passage (sous-chaine du contenu original)
 */
public record TextChunk(int index, int total, String text) {
}
