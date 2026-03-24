package fr.vvlabs.recherche.service.parser.ocr;

import fr.vvlabs.recherche.service.event.DocumentCreatedEvent;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

@Component
@RequiredArgsConstructor
@Slf4j
public class OCRIndexTaskListener {

    private final OCRIndexTask ocrIndexTask;

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void onDocumentCreated(DocumentCreatedEvent event) {
        log.debug("Document created event received for id {}", event.documentId());
        ocrIndexTask.processOCRDocuments();
    }
}

