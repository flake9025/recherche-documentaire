package fr.vvlabs.recherche.service.parser;

import fr.vvlabs.recherche.config.DataType;
import fr.vvlabs.recherche.dto.DocumentDTO;
import fr.vvlabs.recherche.service.document.DocumentService;
import fr.vvlabs.recherche.service.index.IndexServiceFactory;
import fr.vvlabs.recherche.service.storage.StorageServiceFactory;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Async;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.LocalTime;
import java.util.List;
import java.util.concurrent.Future;

@Service
@RequiredArgsConstructor
@Slf4j
public class OCRIndexTask {

    private final DocumentService documentService;
    private final IndexServiceFactory indexServiceFactory;
    private final StorageServiceFactory storageServiceFactory;
    private final OCRServiceFactory ocrServiceFactory;

    @Value("${app.task.ocr.enabled}")
    private boolean ocrTaskEnabled;

    @Value("${app.indexer.use-database}")
    private boolean useDatabaseIndexer;

    @Scheduled(fixedDelayString = "${app.task.ocr.delay:300000}", initialDelay = 30000)
    @Async("ocrExecutor")
    public Future<Void> processOCRDocuments() {
        if(!ocrTaskEnabled) {
            //log.debug("OCR task is disabled");
            return null;
        }

        try {
            log.info("OCRTask started");
            LocalTime t1 = LocalTime.now();

            List<DocumentDTO> documentList = useDatabaseIndexer
                    ? documentService.findAllPendingOcrIndexing()
                    : documentService.findAll();
            documentList.forEach(documentDTO -> {
                try {
                    DataType dataType = DataType.valueOf(documentDTO.getCategorie());
                    Path documentPath = storageServiceFactory.getDefaultStorageService().getPath(documentDTO.getNomFichier());
                    String ocrText;
                    try (InputStream stream = Files.newInputStream(documentPath)) {
                        ocrText = ocrServiceFactory.getDefaultOCRService().getDocumentDatas(documentDTO.getNomFichier(), stream, dataType);
                    }
                    log.info("OCR OK: {}", documentDTO.getTitre());
                    indexServiceFactory.getDefaultIndexService().addDocumentToDocumentIndex(documentDTO, ocrText);
                    log.info("Index OK: {}", documentDTO.getTitre());
                    if (useDatabaseIndexer) {
                        documentService.markOcrIndexDone(documentDTO.getId(), true);
                    }
                } catch (IOException e) {
                    throw new RuntimeException(e);
                }
            });
            LocalTime t2 = LocalTime.now();
            Duration d = Duration.between(t1, t2);
            log.info("Millis Ã©coulÃ©s pour l'OCRTask : {}" , d.toMillis());
        } catch (Exception e) {
            log.error("processOCRDocuments KO : {}", e.getMessage(), e);
        }
        return null;
    }
}


