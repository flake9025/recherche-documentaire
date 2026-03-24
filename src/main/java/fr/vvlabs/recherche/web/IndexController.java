package fr.vvlabs.recherche.web;

import fr.vvlabs.recherche.dto.DocumentDTO;
import fr.vvlabs.recherche.dto.IndexRequestDTO;
import fr.vvlabs.recherche.service.business.document.DocumentService;
import fr.vvlabs.recherche.service.business.index.IndexServiceFactory;
import fr.vvlabs.recherche.service.storage.StorageServiceFactory;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.ModelAttribute;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.time.LocalDateTime;

@RestController
@RequestMapping("/api/index")
@Tag(name = "Indexation", description = "API d'indexation documentaire")
@RequiredArgsConstructor
@Slf4j
public class IndexController {

    private final DocumentService documentService;
    private final StorageServiceFactory storageServiceFactory;
    private final IndexServiceFactory indexServiceFactory;

    @PostMapping(path = "/addFromOCR", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    @Operation(summary = "Indexer un document avec OCR")
    public void addToIndex(@ModelAttribute IndexRequestDTO request) throws Exception {

        log.info("addToIndex : fileName={}, titre={}, auteur={}, categorie={}, ocrType={}, dateDepot={}",
                request.getFile().getOriginalFilename(),
                request.getTitre(),
                request.getAuteur(),
                request.getCategorie(),
                request.getOcrType(),
                request.getDateDepot());

        LocalDateTime depotDateTime = null;
        if (request.getDateDepot() != null && !request.getDateDepot().isBlank()) {
            depotDateTime = LocalDate.parse(request.getDateDepot()).atStartOfDay();
        }

        //-----------------------------------------------
        // Ã‰TAPE 1 : Stockage du fichier
        //-----------------------------------------------
        Path documentFilePath = storageServiceFactory.getDefaultStorageService()
                .storeFile(request.getFile(), request.getTitre());
        log.info("File stored at: {}", documentFilePath);

        //-----------------------------------------------
        // Ã‰TAPE 2 : Stockage des metadonnees
        //-----------------------------------------------
        DocumentDTO documentDTO = new DocumentDTO();
        documentDTO.setTitre(request.getTitre())
                .setAuteur(request.getAuteur())
                .setCategorie(request.getCategorie())
                .setNomFichier(documentFilePath.getFileName().toString())
                .setTailleFichier(Files.size(documentFilePath))
                .setDepotDateTime(depotDateTime);

        Long documentId = documentService.save(documentDTO);
        log.info("Document id in database : {}", documentId);

        //-----------------------------------------------
        // Ã‰TAPE 3 : Lecture du fichier pour OCR
        //-----------------------------------------------
        String documentFileText = documentService.getFileText(documentDTO, request.getOcrType());

        //-----------------------------------------------
        // Ã‰TAPE 4 : Indexation
        //-----------------------------------------------
        indexServiceFactory.getDefaultIndexService().addDocumentToDocumentIndex(documentDTO, documentFileText);
        indexServiceFactory.getDefaultIndexService().addAuthorToAucompleteIndex(documentDTO.getAuteur());
        log.info("Document id in index : {}", documentDTO.getId());

        //-----------------------------------------------
        // Ã‰TAPE 5 : Sauvegarde
        //-----------------------------------------------
        indexServiceFactory.getDefaultIndexService().saveDocumentIndexToDatabase();
    }
}

