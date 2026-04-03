package fr.vvlabs.recherche.service.storage;

import org.junit.jupiter.api.Test;
import org.springframework.web.multipart.MultipartFile;

import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class StorageServiceFactoryTest {

    @Test
    void getDefaultStorageService_returnsConfiguredService() {
        StorageService service = new StubStorageService("fs");
        StorageServiceFactory factory = new StorageServiceFactory(List.of(service), "fs");

        assertThat(factory.getDefaultStorageService()).isSameAs(service);
    }

    @Test
    void getStorageService_throwsWhenUnknown() {
        StorageServiceFactory factory = new StorageServiceFactory(List.of(new StubStorageService("fs")), "fs");

        assertThatThrownBy(() -> factory.getStorageService("s3"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Unknown Storage");
    }

    private record StubStorageService(String type) implements StorageService {
        @Override
        public String getType() {
            return type;
        }

        @Override
        public Path storeFile(MultipartFile file, String titre) {
            return Path.of("stub");
        }

        @Override
        public Path getPath(String nomFichier) {
            return Path.of(nomFichier);
        }

        @Override
        public boolean deleteFile(Path file) {
            return true;
        }
    }
}
