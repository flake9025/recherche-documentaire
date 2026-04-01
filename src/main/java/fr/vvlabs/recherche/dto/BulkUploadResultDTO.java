package fr.vvlabs.recherche.dto;

/**
 * Resultat d'un upload de masse.
 *
 * @param totalRequested nombre de documents demandes
 * @param successCount nombre de documents traites avec succes
 * @param errorCount nombre de documents en erreur
 * @param durationMs duree totale en millisecondes
 * @param durationSeconds duree totale en secondes
 * @param averageTimePerDocMs duree moyenne par document en millisecondes
 */
public record BulkUploadResultDTO(
        int totalRequested,
        int successCount,
        int errorCount,
        long durationMs,
        long durationSeconds,
        long averageTimePerDocMs
) {
}
