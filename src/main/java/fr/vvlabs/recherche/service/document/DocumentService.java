package fr.vvlabs.recherche.service.document;

import fr.vvlabs.recherche.config.DataType;
import fr.vvlabs.recherche.dto.DocumentDTO;
import fr.vvlabs.recherche.mapper.DocumentMapper;
import fr.vvlabs.recherche.model.DocumentEntity;
import fr.vvlabs.recherche.repository.DocumentRepository;
import fr.vvlabs.recherche.service.parser.OCRService;
import fr.vvlabs.recherche.service.parser.OCRServiceFactory;
import fr.vvlabs.recherche.service.storage.StorageServiceFactory;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
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

/**
 * Orchestre le stockage des metadonnees documentaires et l'acces au contenu source.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class DocumentService {

    private final DocumentRepository repository;
    private final DocumentMapper documentMapper;
    private final StorageServiceFactory storageServiceFactory;
    private final OCRServiceFactory ocrServiceFactory;

    /**
     * Persiste les metadonnees d'un document puis publie l'evenement de creation associe.
     *
     * @param documentDTO document a enregistrer
     * @return identifiant technique du document
     * @throws Exception si le chiffrement ou la persistance echoue
     */
    @Transactional
    public Long save(DocumentDTO documentDTO) throws Exception {
        log.info("Sauvegarde des metadonnees pour le document: {}", documentDTO.getTitre());

        LocalDateTime depotDateTime = documentDTO.getDepotDateTime();
        if (depotDateTime == null) {
            depotDateTime = LocalDateTime.now();
        }

        DocumentEntity metadata = documentMapper.toEntity(documentDTO)
                .setDepotDateTime(depotDateTime)
                .setOcrIndexDone(false);

        DocumentEntity saved = repository.save(metadata);
        Long documentId = saved.getId();
        log.info("Metadonnees sauvegardees avec l'ID: {}", documentId);
        documentDTO.setId(documentId);
        return documentId;
    }

    /**
     * Retourne l'ensemble des documents connus.
     *
     * @return liste des documents dechiffres
     */
    @Transactional(readOnly = true)
    public List<DocumentDTO> findAll() {
        return repository.findAll().stream()
                .map(this::safeToDto)
                .filter(Objects::nonNull)
                .toList();
    }

    /**
     * Retourne les documents dont l'index OCR n'est pas encore termine.
     *
     * @return liste des documents en attente d'indexation OCR
     */
    @Transactional(readOnly = true)
    public List<DocumentDTO> findAllPendingOcrIndexing() {
        return repository.findByOcrIndexDoneFalse().stream()
                .map(this::safeToDto)
                .filter(Objects::nonNull)
                .toList();
    }

    /**
     * Marque l'etat d'indexation OCR d'un document.
     *
     * @param id identifiant du document
     * @param done nouvel etat d'indexation
     */
    @Transactional
    public void markOcrIndexDone(Long id, boolean done) {
        repository.findById(id).ifPresent(entity -> entity.setOcrIndexDone(done));
    }

    /**
     * Retourne la ressource de fichier associee a un document.
     *
     * @param id identifiant du document
     * @return paire nom de telechargement / ressource
     * @throws Exception si le dechiffrement ou la resolution du chemin echoue
     */
    @Transactional(readOnly = true)
    public Map.Entry<String, FileSystemResource> getFileResource(Long id) throws Exception {
        DocumentEntity documentEntity = repository.findById(id).orElseThrow();
        String documentFileName = documentMapper.toDto(documentEntity).getNomFichier();
        Path path = getDocumentPath(documentFileName);
        return new AbstractMap.SimpleEntry<>(documentFileName, new FileSystemResource(path));
    }

    /**
     * Lit le contenu OCR d'un document avec le moteur par defaut.
     *
     * @param documentDTO document a lire
     * @return texte OCR ou chaine vide si l'OCR est desactive
     * @throws IOException si la lecture du fichier echoue
     */
    public String getFileText(DocumentDTO documentDTO) throws IOException {
        if (!ocrServiceFactory.isOcrEnabled()) {
            log.debug("OCR is disabled");
            return "";
        }
        return getFileText(documentDTO, ocrServiceFactory.getDefaultOCRService().getType());
    }

    /**
     * Lit le contenu OCR d'un document avec un moteur explicite.
     *
     * @param documentDTO document a lire
     * @param ocrType type de moteur OCR a utiliser
     * @return texte OCR ou chaine vide si l'OCR est desactive
     * @throws IOException si la lecture du fichier echoue
     */
    public String getFileText(DocumentDTO documentDTO, String ocrType) throws IOException {
        if (!ocrServiceFactory.isOcrEnabled()) {
            log.debug("OCR is disabled");
            return "";
        }
        DataType dataType = DataType.valueOf(documentDTO.getCategorie());
        Path documentPath = getDocumentPath(documentDTO.getNomFichier());
        OCRService ocrService = ocrServiceFactory.getOCRService(ocrType);
        try (InputStream stream = Files.newInputStream(documentPath)) {
            return ocrService.getDocumentDatas(documentDTO.getNomFichier(), stream, dataType);
        }
    }

    private DocumentDTO safeToDto(DocumentEntity entity) {
        try {
            return documentMapper.toDto(entity);
        } catch (Exception e) {
            log.error("documentMapper.toDto KO : {}", e.getMessage(), e);
            return null;
        }
    }

    private Path getDocumentPath(String documentFileName) {
        return storageServiceFactory.getDefaultStorageService().getPath(documentFileName);
    }
}
