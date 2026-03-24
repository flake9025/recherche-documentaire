package fr.vvlabs.recherche.service.storage;

import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.nio.file.Path;

public interface StorageService {

    String getType();

    Path storeFile(MultipartFile file, String titre) throws IOException;
    Path getPath(String nomFichier);
    boolean deleteFile(Path file);
}
