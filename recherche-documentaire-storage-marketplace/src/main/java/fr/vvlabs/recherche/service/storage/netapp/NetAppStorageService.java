package fr.vvlabs.recherche.service.storage.netapp;

import fr.vvlabs.recherche.service.storage.StorageService;
import fr.vvlabs.recherche.service.storage.StorageType;
import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.UUID;

/**
 * Storage base sur un partage NetApp monte localement (NFS/SMB/CIFS).
 *
 * <p>Le service manipule uniquement des {@link Path} locaux, ce qui le rend
 * compatible avec le reste du POC. Le montage reseau doit donc etre realise
 * par l'OS ou l'orchestrateur avant le demarrage de l'application.</p>
 */
@Service
@ConditionalOnProperty(name = "app.storage.netapp.enabled", havingValue = "true")
@Slf4j
public class NetAppStorageService implements StorageService {

    private static final DateTimeFormatter TIMESTAMP_FORMATTER = DateTimeFormatter.ofPattern("yyyyMMdd_HHmmss");

    private final Path storagePath;
    private final boolean requireExistingPath;
    private final boolean autoCreateDirectories;

    public NetAppStorageService(
            @Value("${app.storage.netapp.path:./storage/netapp}") String storagePath,
            @Value("${app.storage.netapp.require-existing-path:true}") boolean requireExistingPath,
            @Value("${app.storage.netapp.auto-create-directories:true}") boolean autoCreateDirectories
    ) {
        this.storagePath = Path.of(storagePath);
        this.requireExistingPath = requireExistingPath;
        this.autoCreateDirectories = autoCreateDirectories;
    }

    @PostConstruct
    void init() throws IOException {
        validateAndPrepareStoragePath();
        log.info("NetApp storage initialise : path={}", storagePath.toAbsolutePath());
    }

    @Override
    public String getType() {
        return StorageType.NETAPP;
    }

    @Override
    public Path storeFile(MultipartFile file, String titre) throws IOException {
        validateAndPrepareStoragePath();

        String filename = generateFilename(file.getOriginalFilename(), titre);
        Path destinationFile = storagePath.resolve(filename);

        log.info("Storing file on NetApp: {} -> {}", file.getOriginalFilename(), destinationFile);
        try (var input = file.getInputStream()) {
            Files.copy(input, destinationFile, StandardCopyOption.REPLACE_EXISTING);
        }
        return destinationFile;
    }

    @Override
    public Path getPath(String nomFichier) {
        return storagePath.resolve(nomFichier);
    }

    @Override
    public boolean deleteFile(Path filePath) {
        try {
            return Files.deleteIfExists(filePath);
        } catch (IOException e) {
            log.error("Error deleting NetApp file: {}", filePath, e);
            return false;
        }
    }

    private void validateAndPrepareStoragePath() throws IOException {
        if (requireExistingPath && Files.notExists(storagePath)) {
            throw new IllegalStateException("NetApp path does not exist or is not mounted: " + storagePath.toAbsolutePath());
        }
        if (autoCreateDirectories) {
            Files.createDirectories(storagePath);
        }
        if (Files.notExists(storagePath) || !Files.isDirectory(storagePath)) {
            throw new IllegalStateException("NetApp path is not a directory: " + storagePath.toAbsolutePath());
        }
        if (!Files.isWritable(storagePath)) {
            throw new IllegalStateException("NetApp path is not writable: " + storagePath.toAbsolutePath());
        }
    }

    private String generateFilename(String originalFilename, String titre) {
        String extension = "";
        if (originalFilename != null && !originalFilename.isEmpty()) {
            int lastDotIndex = originalFilename.lastIndexOf('.');
            extension = lastDotIndex > 0 ? originalFilename.substring(lastDotIndex) : "";
        }

        String safeTitle = titre == null || titre.isBlank()
                ? "document"
                : titre.replaceAll("[^a-zA-Z0-9.-]", "_");

        return String.format(
                "%s_%s_%s%s",
                safeTitle,
                LocalDateTime.now().format(TIMESTAMP_FORMATTER),
                UUID.randomUUID().toString().substring(0, 8),
                extension
        );
    }
}

