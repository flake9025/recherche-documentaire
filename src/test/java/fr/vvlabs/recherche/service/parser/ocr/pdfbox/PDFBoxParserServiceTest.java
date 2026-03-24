package fr.vvlabs.recherche.service.parser.ocr.pdfbox;

import fr.vvlabs.recherche.service.parser.ocr.OCRType;
import fr.vvlabs.recherche.service.parser.ocr.tesseract.TesseractParserService;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.font.PDType1Font;
import org.apache.pdfbox.pdmodel.font.Standard14Fonts;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;

import static org.assertj.core.api.Assertions.assertThat;

class PDFBoxParserServiceTest {

    private final TesseractParserService tesseractParserService = Mockito.mock(TesseractParserService.class);

    @Test
    void getType_returnsPdfboxType() {
        PDFBoxParserService service = new PDFBoxParserService(tesseractParserService);

        assertThat(service.getType()).isEqualTo(OCRType.PDFBOX);
    }

    @Test
    void parseRapport_extractsTextFromPdf() throws Exception {
        String expected = "Rapport ABC";
        byte[] pdfBytes = createPdfBytes(expected);
        PDFBoxParserService service = new PDFBoxParserService(tesseractParserService);

        String result = service.parseRapport("rapport.pdf", new ByteArrayInputStream(pdfBytes));

        assertThat(result).contains(expected);
    }

    @Test
    void parseFacture_extractsTextFromPdf() throws Exception {
        String expected = "Facture client";
        byte[] pdfBytes = createPdfBytes(expected);
        PDFBoxParserService service = new PDFBoxParserService(tesseractParserService);

        String result = service.parseFacture("facture.pdf", new ByteArrayInputStream(pdfBytes));

        assertThat(result).contains(expected);
    }

    @Test
    void parseContrat_extractsTextFromPdf() throws Exception {
        String expected = "Contrat fournisseur";
        byte[] pdfBytes = createPdfBytes(expected);
        PDFBoxParserService service = new PDFBoxParserService(tesseractParserService);

        String result = service.parseContrat("contrat.pdf", new ByteArrayInputStream(pdfBytes));

        assertThat(result).contains(expected);
    }

    @Test
    void parse_returnsEmptyString_whenStreamFails() {
        PDFBoxParserService service = new PDFBoxParserService(tesseractParserService);
        InputStream failingStream = new InputStream() {
            @Override
            public int read() throws IOException {
                throw new IOException("boom");
            }
        };

        String result = service.parseRapport("broken.pdf", failingStream);

        assertThat(result).isEmpty();
    }

    private static byte[] createPdfBytes(String text) throws IOException {
        try (PDDocument document = new PDDocument();
             ByteArrayOutputStream outputStream = new ByteArrayOutputStream()) {
            PDPage page = new PDPage();
            document.addPage(page);
            try (PDPageContentStream contentStream = new PDPageContentStream(document, page)) {
                contentStream.beginText();
                contentStream.setFont(new PDType1Font(Standard14Fonts.FontName.HELVETICA), 12);
                contentStream.newLineAtOffset(72, 720);
                contentStream.showText(text);
                contentStream.endText();
            }
            document.save(outputStream);
            return outputStream.toByteArray();
        }
    }
}

