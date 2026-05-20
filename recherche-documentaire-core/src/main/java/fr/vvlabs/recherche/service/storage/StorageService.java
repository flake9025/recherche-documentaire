package fr.vvlabs.recherche.service.storage;

import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;

public interface StorageService {

    String getType();

    Path storeFile(MultipartFile file, String titre) throws IOException;
    Path getPath(String nomFichier);
    boolean deleteFile(Path file);

    /**
     * Deplace ou renomme un fichier dans le store.
     * L'implementation par defaut realise un {@link java.nio.file.Files#move} local.
     *
     * @param source chemin actuel du fichier
     * @param target chemin cible
     * @return chemin effectif apres deplacement
     * @throws IOException si le deplacement echoue
     */
    default Path moveFile(Path source, Path target) throws IOException {
        return Files.move(source, target, StandardCopyOption.REPLACE_EXISTING);
    }
}
