package fr.vvlabs.recherche.web;

import fr.vvlabs.recherche.dto.DocumentDTO;
import fr.vvlabs.recherche.service.business.document.DocumentService;
import fr.vvlabs.recherche.service.business.index.IndexServiceFactory;
import fr.vvlabs.recherche.service.storage.StorageServiceFactory;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Duration;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.HashMap;
import java.util.Map;

@RestController
@RequestMapping("/api/bulk")
@Tag(name = "Tests de charge", description = "API pour les tests de montÃ©e en charge")
@RequiredArgsConstructor
@Slf4j
public class BulkIndexController {

    private final DocumentService documentService;
    private final StorageServiceFactory storageServiceFactory;
    private final IndexServiceFactory indexServiceFactory;

    @PostMapping(path = "/uploadMass", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    @Operation(summary = "Upload en masse d'un document (500 copies)")
    public Map<String, Object> bulkUpload(
            @Parameter(description = "Fichier Ã  dupliquer 500 fois", required = true)
            @RequestPart("file") MultipartFile file,

            @Parameter(description = "Titre de base du document", required = true)
            @RequestPart("titre") String titre,

            @Parameter(description = "Auteur du dÃ©pÃ´t", required = true)
            @RequestPart("auteur") String auteur,

            @Parameter(description = "Type de document (rapport, facture, contrat)", required = true)
            @RequestPart("categorie") String categorie,

            @Parameter(description = "Nombre de copies Ã  crÃ©er (par dÃ©faut: 500)")
            @RequestParam(value = "count", defaultValue = "500") int count
    ) throws Exception {

        log.info("DÃ©but de l'upload en masse : {} copies de '{}'", count, file.getOriginalFilename());

        LocalTime startTime = LocalTime.now();
        Map<String, Object> result = new HashMap<>();

        int successCount = 0;
        int errorCount = 0;

        for (int i = 1; i <= count; i++) {
            try {
                // GÃ©nÃ©ration d'un titre et nom de fichier uniques
                String uniqueTitre = titre + " - Copie " + i;
                String uniqueFilename = generateUniqueFilename(file.getOriginalFilename(), i);

                log.debug("Traitement document {}/{}: {}", i, count, uniqueTitre);

                //-----------------------------------------------
                // Ã‰TAPE 1 : Stockage du fichier
                //-----------------------------------------------
                Path documentFilePath = storageServiceFactory.getDefaultStorageService()
                        .storeFile(file, uniqueTitre);

                // Renommer le fichier pour avoir un nom unique
                Path uniquePath = documentFilePath.getParent().resolve(uniqueFilename);
                Files.move(documentFilePath, uniquePath, StandardCopyOption.REPLACE_EXISTING);

                log.debug("Fichier {} stockÃ© Ã : {}", i, uniquePath);

                //-----------------------------------------------
                // Ã‰TAPE 2 : Stockage des mÃ©tadonnÃ©es
                //-----------------------------------------------
                DocumentDTO documentDTO = new DocumentDTO();
                documentDTO.setTitre(uniqueTitre)
                        .setAuteur(auteur)
                        .setCategorie(categorie)
                        .setNomFichier(uniquePath.getFileName().toString())
                        .setTailleFichier(Files.size(uniquePath))
                        .setDepotDateTime(LocalDateTime.now());;

                Long documentId = documentService.save(documentDTO);
                log.debug("Document {}/{} - ID en base : {}", i, count, documentId);

                //-----------------------------------------------
                // Ã‰TAPE 3 : Lecture du fichier pour OCR
                //-----------------------------------------------
                String documentFileText = documentService.getFileText(documentDTO);

                //-----------------------------------------------
                // Ã‰TAPE 4 : Indexation
                //-----------------------------------------------
                indexServiceFactory.getDefaultIndexService().addDocumentToDocumentIndex(documentDTO, documentFileText);
                log.debug("Document {}/{} - ID indexÃ© : {}", i, count, documentDTO.getId());

                successCount++;

                // Log de progression tous les 50 documents
                if (i % 50 == 0) {
                    log.info("Progression : {}/{} documents traitÃ©s", i, count);
                }

            } catch (Exception e) {
                errorCount++;
                log.error("Erreur lors du traitement du document {}/{}: {}", i, count, e.getMessage(), e);
            }
        }

        //-----------------------------------------------
        // Ã‰TAPE 5 : Sauvegarde de l'index
        //-----------------------------------------------
        log.info("Sauvegarde de l'index...");
        indexServiceFactory.getDefaultIndexService().saveDocumentIndexToDatabase();

        LocalTime endTime = LocalTime.now();
        Duration duration = Duration.between(startTime, endTime);

        result.put("totalRequested", count);
        result.put("successCount", successCount);
        result.put("errorCount", errorCount);
        result.put("durationMs", duration.toMillis());
        result.put("durationSeconds", duration.toSeconds());
        result.put("averageTimePerDocMs", successCount > 0 ? duration.toMillis() / successCount : 0);

        log.info("Upload en masse terminÃ© : {} succÃ¨s, {} erreurs en {} ms",
                successCount, errorCount, duration.toMillis());

        return result;
    }

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

    private String generateUniqueFilename(String originalFilename, int index) {
        if (originalFilename == null || originalFilename.isEmpty()) {
            return "document_" + index;
        }

        int lastDot = originalFilename.lastIndexOf('.');
        if (lastDot > 0) {
            String name = originalFilename.substring(0, lastDot);
            String extension = originalFilename.substring(lastDot);
            return name + "_copy_" + index + extension;
        } else {
            return originalFilename + "_copy_" + index;
        }
    }
}
