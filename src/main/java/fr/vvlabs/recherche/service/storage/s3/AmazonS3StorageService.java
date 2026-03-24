package fr.vvlabs.recherche.service.storage.s3;

import fr.vvlabs.recherche.service.storage.StorageService;
import fr.vvlabs.recherche.service.storage.StorageType;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.nio.file.Path;

public class AmazonS3StorageService implements StorageService {

    @Override
    public String getType() { return StorageType.AMAZON_S3; }

    @Override
    public Path storeFile(MultipartFile file, String titre) throws IOException {
        //@TODO
        return null;
    }

    @Override
    public Path getPath(String nomFichier) {
        //@TODO
        return null;
    }

    @Override
    public boolean deleteFile(Path file) {
        //@TODO
        return false;
    }
}
