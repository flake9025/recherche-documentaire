package fr.vvlabs.recherche.service.business.document;

import fr.vvlabs.recherche.config.DataType;
import fr.vvlabs.recherche.dto.DocumentDTO;
import fr.vvlabs.recherche.model.DocumentEntity;
import fr.vvlabs.recherche.repository.DocumentRepository;
import fr.vvlabs.recherche.service.cipher.CipherService;
import fr.vvlabs.recherche.service.event.DocumentCreatedEvent;
import fr.vvlabs.recherche.service.parser.ocr.OCRService;
import fr.vvlabs.recherche.service.parser.ocr.OCRServiceFactory;
import fr.vvlabs.recherche.service.storage.StorageService;
import fr.vvlabs.recherche.service.storage.StorageServiceFactory;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Captor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.core.io.FileSystemResource;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class DocumentServiceTest {

    @Mock
    private DocumentRepository repository;

    @Mock
    private CipherService cipherService;

    @Mock
    private StorageServiceFactory storageServiceFactory;

    @Mock
    private OCRServiceFactory ocrServiceFactory;

    @Mock
    private ApplicationEventPublisher eventPublisher;

    @Mock
    private StorageService storageService;

    @Mock
    private OCRService ocrService;

    @Captor
    private ArgumentCaptor<DocumentEntity> documentEntityCaptor;

    private DocumentService service;

    @BeforeEach
    void setUp() {
        service = new DocumentService(repository, cipherService, storageServiceFactory, ocrServiceFactory, eventPublisher);
    }

    @Test
    void save_encryptsMetadataAndReturnsId() throws Exception {
        DocumentDTO dto = new DocumentDTO()
                .setTitre("Titre")
                .setAuteur("Auteur")
                .setCategorie("RAPPORT")
                .setNomFichier("doc.pdf")
                .setTailleFichier(123L);

        when(cipherService.encrypt(anyString())).thenAnswer(invocation -> "enc-" + invocation.getArgument(0));
        when(repository.save(any(DocumentEntity.class))).thenAnswer(invocation -> {
            DocumentEntity entity = invocation.getArgument(0);
            entity.setId(10L);
            return entity;
        });

        Long id = service.save(dto);

        assertThat(id).isEqualTo(10L);
        assertThat(dto.getId()).isEqualTo(10L);

        verify(repository).save(documentEntityCaptor.capture());
        DocumentEntity saved = documentEntityCaptor.getValue();
        assertThat(saved.getTitreDocument()).isEqualTo("enc-Titre");
        assertThat(saved.getAuteurDepot()).isEqualTo("enc-Auteur");
        assertThat(saved.getCategoriesEns()).isEqualTo("enc-RAPPORT");
        assertThat(saved.getNomFichier()).isEqualTo("enc-doc.pdf");
        assertThat(saved.getTailleFichier()).isEqualTo(123L);
        assertThat(saved.getDepotDateTime()).isNotNull();
        assertThat(saved.isOcrIndexDone()).isFalse();
        verify(eventPublisher).publishEvent(any(DocumentCreatedEvent.class));
    }

    @Test
    void findAll_decryptsEntities() throws Exception {
        DocumentEntity entity = new DocumentEntity()
                .setId(5L)
                .setTitreDocument("enc-title")
                .setAuteurDepot("enc-author")
                .setCategoriesEns("enc-cat")
                .setNomFichier("enc-file")
                .setTailleFichier(50L)
                .setDepotDateTime(LocalDateTime.of(2026, 2, 1, 9, 0));

        when(repository.findAll()).thenReturn(List.of(entity));
        when(cipherService.decrypt("enc-title")).thenReturn("Titre");
        when(cipherService.decrypt("enc-author")).thenReturn("Auteur");
        when(cipherService.decrypt("enc-cat")).thenReturn("RAPPORT");
        when(cipherService.decrypt("enc-file")).thenReturn("doc.pdf");

        List<DocumentDTO> results = service.findAll();

        assertThat(results).hasSize(1);
        DocumentDTO dto = results.get(0);
        assertThat(dto.getId()).isEqualTo(5L);
        assertThat(dto.getTitre()).isEqualTo("Titre");
        assertThat(dto.getAuteur()).isEqualTo("Auteur");
        assertThat(dto.getCategorie()).isEqualTo("RAPPORT");
        assertThat(dto.getNomFichier()).isEqualTo("doc.pdf");
        assertThat(dto.getTailleFichier()).isEqualTo(50L);
        assertThat(dto.getDepotDateTime()).isEqualTo(entity.getDepotDateTime());
    }

    @Test
    void getFileResource_resolvesPathAndName() throws Exception {
        DocumentEntity entity = new DocumentEntity()
                .setId(7L)
                .setNomFichier("enc-doc.pdf");

        Path dir = Files.createTempDirectory("docs");
        Path file = dir.resolve("doc.pdf");
        Files.writeString(file, "data");

        when(repository.findById(7L)).thenReturn(Optional.of(entity));
        when(cipherService.decrypt("enc-doc.pdf")).thenReturn("doc.pdf");
        when(storageServiceFactory.getDefaultStorageService()).thenReturn(storageService);
        when(storageService.getPath("doc.pdf")).thenReturn(file);

        Map.Entry<String, FileSystemResource> entry = service.getFileResource(7L);

        assertThat(entry.getKey()).isEqualTo("doc.pdf");
        assertThat(entry.getValue().getFile().toPath()).isEqualTo(file);
    }

    @Test
    void getFileText_whenOcrDisabled_returnsEmpty() throws Exception {
        DocumentDTO dto = new DocumentDTO()
                .setCategorie("RAPPORT")
                .setNomFichier("doc.pdf");

        when(ocrServiceFactory.isOcrEnabled()).thenReturn(false);

        String result = service.getFileText(dto);

        assertThat(result).isEmpty();
        verify(ocrServiceFactory, never()).getOCRService(anyString());
        verify(storageServiceFactory, never()).getDefaultStorageService();
    }

    @Test
    void getFileText_whenEnabled_usesOcrService() throws Exception {
        DocumentDTO dto = new DocumentDTO()
                .setCategorie("RAPPORT")
                .setNomFichier("doc.pdf");

        Path dir = Files.createTempDirectory("docs");
        Path file = dir.resolve("doc.pdf");
        Files.writeString(file, "data");

        when(ocrServiceFactory.isOcrEnabled()).thenReturn(true);
        when(ocrServiceFactory.getOCRService(anyString())).thenReturn(ocrService);
        when(storageServiceFactory.getDefaultStorageService()).thenReturn(storageService);
        when(storageService.getPath("doc.pdf")).thenReturn(file);
        when(ocrService.getDocumentDatas(eq("doc.pdf"), any(), eq(DataType.RAPPORT))).thenReturn("TEXT");

        String result = service.getFileText(dto);

        assertThat(result).isEqualTo("TEXT");
        verify(ocrService).getDocumentDatas(eq("doc.pdf"), any(), eq(DataType.RAPPORT));
    }
}

