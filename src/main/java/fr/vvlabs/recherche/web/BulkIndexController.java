package fr.vvlabs.recherche.web;

import fr.vvlabs.recherche.dto.BulkUploadResultDTO;
import fr.vvlabs.recherche.dto.DocumentDTO;
import fr.vvlabs.recherche.service.document.DocumentService;
import fr.vvlabs.recherche.service.index.IndexServiceFactory;
import fr.vvlabs.recherche.service.storage.StorageServiceFactory;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Duration;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.HashMap;
import java.util.Map;

/**
 * Expose les endpoints techniques utilises pour les tests de charge.
 */
@RestController
@RequestMapping("/api/bulk")
@Tag(name = "Tests de charge", description = "API pour les tests de montee en charge")
@RequiredArgsConstructor
@Slf4j
public class BulkIndexController {

    private final DocumentService documentService;
    private final StorageServiceFactory storageServiceFactory;
    private final IndexServiceFactory indexServiceFactory;

    /**
     * Duplique un document source puis l'indexe en masse.
     *
     * @param file fichier source
     * @param titre titre de base
     * @param auteur auteur de depot
     * @param categorie categorie documentaire
     * @param count nombre de duplications a produire
     * @return resultat consolide du traitement
     * @throws Exception si une erreur bloquante survient
     */
    @PostMapping(path = "/uploadMass", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    @Operation(summary = "Upload en masse d'un document (500 copies)")
    public BulkUploadResultDTO bulkUpload(
            @Parameter(description = "Fichier a dupliquer 500 fois", required = true)
            @RequestPart("file") MultipartFile file,
            @Parameter(description = "Titre de base du document", required = true)
            @RequestPart("titre") String titre,
            @Parameter(description = "Auteur du depot", required = true)
            @RequestPart("auteur") String auteur,
            @Parameter(description = "Type de document (rapport, facture, contrat)", required = true)
            @RequestPart("categorie") String categorie,
            @Parameter(description = "Nombre de copies a creer (par defaut: 500)")
            @RequestParam(value = "count", defaultValue = "500") int count
    ) throws Exception {

        log.info("Debut de l'upload en masse : {} copies de '{}'", count, file.getOriginalFilename());

        LocalTime startTime = LocalTime.now();
        int successCount = 0;
        int errorCount = 0;

        for (int i = 1; i <= count; i++) {
            try {
                String uniqueTitre = titre + " - Copie " + i;
                String originalFilename = file.getOriginalFilename();
                String uniqueFilename;
                if (originalFilename == null || originalFilename.isEmpty()) {
                    uniqueFilename = "document_" + i;
                } else {
                    int lastDot = originalFilename.lastIndexOf('.');
                    if (lastDot > 0) {
                        uniqueFilename = originalFilename.substring(0, lastDot) + "_copy_" + i + originalFilename.substring(lastDot);
                    } else {
                        uniqueFilename = originalFilename + "_copy_" + i;
                    }
                }

                log.debug("Traitement document {}/{}: {}", i, count, uniqueTitre);

                Path documentFilePath = storageServiceFactory.getDefaultStorageService().storeFile(file, uniqueTitre);
                Path uniquePath = documentFilePath.getParent().resolve(uniqueFilename);
                Files.move(documentFilePath, uniquePath, StandardCopyOption.REPLACE_EXISTING);

                log.debug("Fichier {} stocke a: {}", i, uniquePath);

                DocumentDTO documentDTO = new DocumentDTO()
                        .setTitre(uniqueTitre)
                        .setAuteur(auteur)
                        .setCategorie(categorie)
                        .setNomFichier(uniquePath.getFileName().toString())
                        .setTailleFichier(Files.size(uniquePath))
                        .setDepotDateTime(LocalDateTime.now());

                Long documentId = documentService.save(documentDTO);
                log.debug("Document {}/{} - ID en base : {}", i, count, documentId);

                String documentFileText = documentService.getFileText(documentDTO);

                indexServiceFactory.getDefaultIndexService().addDocumentToDocumentIndex(documentDTO, documentFileText);
                log.debug("Document {}/{} - ID indexe : {}", i, count, documentDTO.getId());

                successCount++;

                if (i % 50 == 0) {
                    log.info("Progression : {}/{} documents traites", i, count);
                }

            } catch (Exception e) {
                errorCount++;
                log.error("Erreur lors du traitement du document {}/{}: {}", i, count, e.getMessage(), e);
            }
        }

        log.info("Sauvegarde de l'index...");
        indexServiceFactory.getDefaultIndexService().saveDocumentIndexToDatabase();

        Duration duration = Duration.between(startTime, LocalTime.now());

        log.info("Upload en masse termine : {} succes, {} erreurs en {} ms",
                successCount, errorCount, duration.toMillis());

        return new BulkUploadResultDTO(
                count,
                successCount,
                errorCount,
                duration.toMillis(),
                duration.toSeconds(),
                successCount > 0 ? duration.toMillis() / successCount : 0
        );
    }

    /**
     * Retourne des statistiques basiques sur les donnees chargees.
     *
     * @return statistiques de volume et d'etat
     */
    @GetMapping("/stats")
    @Operation(summary = "Obtenir des statistiques sur les tests de charge")
    public Map<String, Object> getStats() {
        Map<String, Object> stats = new HashMap<>();

        try {
            long documentCount = documentService.findAll().size();
            stats.put("totalDocuments", documentCount);
            stats.put("status", "OK");
        } catch (Exception e) {
            stats.put("status", "ERROR");
            stats.put("error", e.getMessage());
        }

        return stats;
    }
}
