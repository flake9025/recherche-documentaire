package fr.vvlabs.recherche.service.parser;

import org.junit.jupiter.api.Test;

import java.io.InputStream;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class OCRServiceFactoryTest {

    @Test
    void getDefaultOCRService_returnsConfiguredService() {
        OCRService service = new StubOCRService("pdfbox");
        OCRServiceFactory factory = new OCRServiceFactory(List.of(service), "pdfbox", true);

        assertThat(factory.getDefaultOCRService()).isSameAs(service);
    }

    @Test
    void getOCRService_throwsWhenUnknown() {
        OCRServiceFactory factory = new OCRServiceFactory(List.of(new StubOCRService("pdfbox")), "pdfbox", true);

        assertThatThrownBy(() -> factory.getOCRService("tika"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Unknown OCR");
    }

    @Test
    void isOcrEnabled_exposesConfiguredFlag() {
        OCRServiceFactory factory = new OCRServiceFactory(List.of(new StubOCRService("pdfbox")), "pdfbox", true);

        assertThat(factory.isOcrEnabled()).isTrue();
    }

    private record StubOCRService(String type) implements OCRService {
        @Override
        public String getType() {
            return type;
        }

        @Override
        public String parseRapport(String fileName, InputStream stream) {
            return "";
        }

        @Override
        public String parseFacture(String fileName, InputStream stream) {
            return "";
        }

        @Override
        public String parseContrat(String fileName, InputStream stream) {
            return "";
        }
    }
}
