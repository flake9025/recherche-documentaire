package fr.vvlabs.recherche.service.parser.tika;

import fr.vvlabs.recherche.service.parser.OCRType;
import fr.vvlabs.recherche.service.parser.tesseract.TesseractParserService;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.verifyNoInteractions;

class TikaParserServiceTest {

    private final TesseractParserService tesseractParserService = Mockito.mock(TesseractParserService.class);

    @Test
    void getType_returnsTikaType() {
        TikaParserService service = new TikaParserService(tesseractParserService);

        assertThat(service.getType()).isEqualTo(OCRType.TIKA);
    }

    @Test
    void parseRapport_extractsTextFromPlainTextDocument() {
        TikaParserService service = new TikaParserService(tesseractParserService);
        String expected = "Texte Tika brut";

        String result = service.parseRapport(
                "rapport.txt",
                new ByteArrayInputStream(expected.getBytes(StandardCharsets.UTF_8))
        );

        assertThat(result).contains(expected);
        verifyNoInteractions(tesseractParserService);
    }
}

