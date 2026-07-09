package fr.vvlabs.recherche.service.storage.netapp;

import fr.vvlabs.recherche.service.storage.StorageType;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.mock.web.MockMultipartFile;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class NetAppStorageServiceTest {

    @TempDir
    Path tempDir;

    @Test
    void init_throwsWhenMountPathIsRequiredButMissing() {
        NetAppStorageService service = new NetAppStorageService(
                tempDir.resolve("missing-mount").toString(),
                true,
                true
        );

        assertThatThrownBy(service::init)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("not exist or is not mounted");
    }

    @Test
    void storeFile_getPath_moveFileAndDeleteFile_workOnMountedShare() throws IOException {
        Path mountPath = Files.createDirectories(tempDir.resolve("mounted-share"));
        NetAppStorageService service = new NetAppStorageService(mountPath.toString(), true, true);
        service.init();

        MockMultipartFile multipartFile = new MockMultipartFile(
                "file",
                "contrat.pdf",
                "application/pdf",
                "contenu-pdf".getBytes()
        );

        Path stored = service.storeFile(multipartFile, "Contrat Client");
        Path expectedResolvedPath = service.getPath(stored.getFileName().toString());

        assertThat(service.getType()).isEqualTo(StorageType.NETAPP);
        assertThat(stored).exists();
        assertThat(stored.getParent()).isEqualTo(mountPath);
        assertThat(stored.getFileName().toString())
                .startsWith("Contrat_Client_")
                .endsWith(".pdf");
        assertThat(expectedResolvedPath).isEqualTo(stored);

        Path moved = mountPath.resolve("archive").resolve(stored.getFileName().toString());
        Files.createDirectories(moved.getParent());
        Path movedResult = service.moveFile(stored, moved);

        assertThat(movedResult).isEqualTo(moved);
        assertThat(moved).exists();
        assertThat(stored).doesNotExist();
        assertThat(service.deleteFile(moved)).isTrue();
        assertThat(moved).doesNotExist();
    }

    @Test
    void init_createsDirectoryWhenPathIsNotRequired() throws IOException {
        Path targetPath = tempDir.resolve("netapp-subdir");
        NetAppStorageService service = new NetAppStorageService(targetPath.toString(), false, true);

        service.init();

        assertThat(targetPath).exists().isDirectory();
    }
}


