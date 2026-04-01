package fr.vvlabs.recherche.dto;

/**
 * Resultat de reconstruction de l'index d'autocompletion.
 *
 * @param success indique si la reconstruction a abouti
 * @param message message de synthese
 * @param authorCount nombre d'auteurs indexes
 * @param durationMs duree de reconstruction en millisecondes
 */
public record AutocompleteRebuildResultDTO(
        boolean success,
        String message,
        long authorCount,
        long durationMs
) {
}
