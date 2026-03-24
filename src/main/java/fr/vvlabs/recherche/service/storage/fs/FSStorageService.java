package fr.vvlabs.recherche.service.storage.fs;

import fr.vvlabs.recherche.service.storage.StorageService;
import fr.vvlabs.recherche.service.storage.StorageType;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.UUID;

@Service
@Slf4j
public class FSStorageService implements StorageService {

    @Value("${app.storage.path:./storage/documents}")
    private String storagePath;

    @Override
    public String getType() {
        return StorageType.FILE_SYSTEM;
    }

    @Override
    public Path storeFile(MultipartFile file, String titre) throws IOException {
        // Création du répertoire de stockage s'il n'existe pas
        Path storageDir = Paths.get(storagePath);
        Files.createDirectories(storageDir);

        // Génération d'un nom de fichier unique
        String originalFilename = file.getOriginalFilename();
        String extension = getFileExtension(originalFilename);
        String timestamp = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd_HHmmss"));
        String uniqueId = UUID.randomUUID().toString().substring(0, 8);
        String filename = String.format("%s_%s_%s%s",
                sanitizeFilename(titre),
                timestamp,
                uniqueId,
                extension
        );

        Path destinationFile = storageDir.resolve(filename);

        log.info("Storing file: {} -> {}", originalFilename, destinationFile);

        // Copie du fichier avec remplacement si existe déjà
        Files.copy(file.getInputStream(), destinationFile, StandardCopyOption.REPLACE_EXISTING);

        log.info("File stored successfully: {}", destinationFile);

        return destinationFile;
    }

    @Override
    public Path getPath(String nomFichier) {
        return Paths.get(storagePath, nomFichier);
    }

    @Override
    public boolean deleteFile(Path filePath) {
        try {
            return Files.deleteIfExists(filePath);
        } catch (IOException e) {
            log.error("Error deleting file: {}", filePath, e);
            return false;
        }
    }

    private String getFileExtension(String filename) {
        if (filename == null || filename.isEmpty()) {
            return "";
        }
        int lastDotIndex = filename.lastIndexOf('.');
        return lastDotIndex > 0 ? filename.substring(lastDotIndex) : "";
    }

    private String sanitizeFilename(String filename) {
        // Remplace les caractères non autorisés par des underscores
        return filename.replaceAll("[^a-zA-Z0-9.-]", "_");
    }
}
