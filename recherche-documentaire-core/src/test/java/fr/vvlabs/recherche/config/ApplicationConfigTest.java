package fr.vvlabs.recherche.config;

import fr.vvlabs.recherche.service.index.IndexService;
import fr.vvlabs.recherche.service.index.IndexServiceFactory;
import org.apache.lucene.store.ByteBuffersDirectory;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.io.IOException;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class ApplicationConfigTest {
    @Mock
    private LuceneConfig luceneConfig;
    @Mock
    private IndexServiceFactory factory;
    @Mock
    private IndexService<ByteBuffersDirectory> indexService;

    private ApplicationConfig config;

    @BeforeEach
    void setUp() {
        config = new ApplicationConfig(luceneConfig, factory);
        when(factory.getDefaultIndexService()).thenReturn(indexService);
    }

    @Test
    void unreadableIndexStopsStartupInsteadOfBeingIgnored() throws Exception {
        var failure = new IOException("Snapshot incompatible");
        when(indexService.loadDocumentIndexFromDatabase()).thenThrow(failure);

        assertThatThrownBy(config::reloadIndexAtStartup).isSameAs(failure);
        verifyNoInteractions(luceneConfig);
    }

    @Test
    void validLuceneSnapshotIsInstalled() throws Exception {
        try (var directory = new ByteBuffersDirectory()) {
            when(indexService.loadDocumentIndexFromDatabase()).thenReturn(directory);

            config.reloadIndexAtStartup();

            verify(luceneConfig).setDocumentsIndex(directory);
        }
    }
}
