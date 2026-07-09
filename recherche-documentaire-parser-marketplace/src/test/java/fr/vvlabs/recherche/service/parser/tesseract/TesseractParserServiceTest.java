package fr.vvlabs.recherche.service.parser.tesseract;

import fr.vvlabs.recherche.service.parser.OCRType;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;

class TesseractParserServiceTest {

    private final TesseractParserService service = new TesseractParserService();

    @Test
    void getType_returnsTesseractType() {
        assertThat(service.getType()).isEqualTo(OCRType.TESSERACT);
    }

    @Test
    void doOcr_returnsEmptyString_whenImageIsNull() throws Exception {
        assertThat(service.doOcr(null)).isEmpty();
    }

    @Test
    void parseRapport_returnsEmptyString_whenStreamIsNotAnImage() {
        String result = service.parseRapport(
                "not-an-image.txt",
                new ByteArrayInputStream("not-an-image".getBytes(StandardCharsets.UTF_8))
        );

        assertThat(result).isEmpty();
    }
}

