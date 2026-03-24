package fr.vvlabs.recherche.service.business.document;

import fr.vvlabs.recherche.config.DataType;
import fr.vvlabs.recherche.dto.DocumentDTO;
import fr.vvlabs.recherche.model.DocumentEntity;
import fr.vvlabs.recherche.repository.DocumentRepository;
import fr.vvlabs.recherche.service.cipher.CipherService;
import fr.vvlabs.recherche.service.event.DocumentCreatedEvent;
import fr.vvlabs.recherche.service.parser.ocr.OCRService;
import fr.vvlabs.recherche.service.parser.ocr.OCRServiceFactory;
import fr.vvlabs.recherche.service.storage.StorageServiceFactory;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.core.io.FileSystemResource;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.util.AbstractMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

@Service
@RequiredArgsConstructor
@Slf4j
public class DocumentService {

    private final DocumentRepository repository;
    private final CipherService cipherService;
    private final StorageServiceFactory storageServiceFactory;
    private final OCRServiceFactory ocrServiceFactory;
    private final ApplicationEventPublisher eventPublisher;

    @Transactional
    public Long save(DocumentDTO documentDTO) throws Exception {
        log.info("Sauvegarde des mÃ©tadonnÃ©es pour le document: {}", documentDTO.getTitre());

        LocalDateTime depotDateTime = documentDTO.getDepotDateTime();
        if (depotDateTime == null) {
            depotDateTime = LocalDateTime.now();
        }

        DocumentEntity metadata = new DocumentEntity()
                .setTitreDocument(cipherService.encrypt(documentDTO.getTitre()))
                .setAuteurDepot(cipherService.encrypt(documentDTO.getAuteur()))
                .setCategoriesEns(cipherService.encrypt(documentDTO.getCategorie()))
                .setNomFichier(cipherService.encrypt(documentDTO.getNomFichier()))
                .setTailleFichier(documentDTO.getTailleFichier())
                .setDepotDateTime(depotDateTime)
                .setOcrIndexDone(false);

        DocumentEntity saved = repository.save(metadata);
        Long documentId = saved.getId();
        log.info("MÃ©tadonnÃ©es sauvegardÃ©es avec l'ID: {}", documentId);
        documentDTO.setId(documentId);
        eventPublisher.publishEvent(new DocumentCreatedEvent(documentId));
        return documentId;
    }

    @Transactional(readOnly = true)
    public List<DocumentDTO> findAll() {
        return repository.findAll().stream()
                .map(this::mapToDto)
                .filter(Objects::nonNull)
                .toList();
    }

    @Transactional(readOnly = true)
    public List<DocumentDTO> findAllPendingOcrIndexing() {
        return repository.findByOcrIndexDoneFalse().stream()
                .map(this::mapToDto)
                .filter(Objects::nonNull)
                .toList();
    }

    @Transactional
    public void markOcrIndexDone(Long id, boolean done) {
        repository.findById(id).ifPresent(entity -> entity.setOcrIndexDone(done));
    }

    @Transactional(readOnly = true)
    public Map.Entry<String, FileSystemResource> getFileResource(Long id) throws Exception {
        DocumentEntity doc = repository.findById(id).orElseThrow();
        String docFileName = cipherService.decrypt(doc.getNomFichier());
        Path path = getDocumentPath(docFileName);
        return new AbstractMap.SimpleEntry<>(docFileName, new FileSystemResource(path));
    }

    public String getFileText(DocumentDTO documentDTO) throws IOException {
        if(!ocrServiceFactory.isOcrEnabled()) {
            log.info("OCR is disabled");
            return "";
        }
        return getFileText(documentDTO, ocrServiceFactory.getDefaultOCRService().getType());
    }

    public String getFileText(DocumentDTO documentDTO, String ocrType) throws IOException {
        if(!ocrServiceFactory.isOcrEnabled()) {
            log.info("OCR is disabled");
            return "";
        }
        DataType dataType = DataType.valueOf(documentDTO.getCategorie());
        Path documentPath = getDocumentPath(documentDTO.getNomFichier());
        OCRService ocrService = ocrServiceFactory.getOCRService(ocrType);
        try (InputStream stream = Files.newInputStream(documentPath)) {
            return ocrService.getDocumentDatas(documentDTO.getNomFichier(), stream, dataType);
        }
    }

    private Path getDocumentPath(String documentFileName) {
        return storageServiceFactory.getDefaultStorageService().getPath(documentFileName);
    }

    private DocumentEntity mapToEntity(DocumentDTO documentDTO) throws Exception {
        return new DocumentEntity()
                .setTitreDocument(cipherService.encrypt(documentDTO.getTitre()))
                .setAuteurDepot(cipherService.encrypt(documentDTO.getAuteur()))
                .setCategoriesEns(cipherService.encrypt(documentDTO.getCategorie()))
                .setNomFichier(cipherService.encrypt(documentDTO.getNomFichier()))
                .setTailleFichier(documentDTO.getTailleFichier())
                .setOcrIndexDone(documentDTO.isOcrIndexDone());
    }

    private DocumentDTO mapToDto(DocumentEntity documentEntity) {
        try {
            return new DocumentDTO()
                    .setId(documentEntity.getId())
                    .setTitre(cipherService.decrypt(documentEntity.getTitreDocument()))
                    .setAuteur(cipherService.decrypt(documentEntity.getAuteurDepot()))
                    .setCategorie(cipherService.decrypt(documentEntity.getCategoriesEns()))
                    .setNomFichier(cipherService.decrypt(documentEntity.getNomFichier()))
                    .setTailleFichier(documentEntity.getTailleFichier())
                    .setDepotDateTime(documentEntity.getDepotDateTime())
                    .setOcrIndexDone(documentEntity.isOcrIndexDone());
        } catch (Exception e) {
            log.error("mapToDto KO : {}", e.getMessage(), e);
            return null;
        }
    }
}

