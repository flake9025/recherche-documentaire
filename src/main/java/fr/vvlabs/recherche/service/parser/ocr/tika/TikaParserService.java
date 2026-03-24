package fr.vvlabs.recherche.service.parser.ocr.tika;

import fr.vvlabs.recherche.service.parser.ocr.OCRService;
import fr.vvlabs.recherche.service.parser.ocr.OCRType;
import fr.vvlabs.recherche.service.parser.ocr.tesseract.TesseractParserService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.rendering.PDFRenderer;
import org.apache.tika.Tika;
import org.springframework.stereotype.Service;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.time.Duration;
import java.time.LocalTime;

@Service
@Slf4j
@RequiredArgsConstructor
public class TikaParserService implements OCRService {

    private static final byte[] PDF_MAGIC = new byte[] { '%', 'P', 'D', 'F' };
    private static final Tika TIKA = new Tika();

    private final TesseractParserService tesseractParserService;

    @org.springframework.beans.factory.annotation.Value("${app.parser.ocr.textDetection.minChars:30}")
    private int minTextChars;

    @org.springframework.beans.factory.annotation.Value("${app.parser.ocr.tesseract.pdfDpi:200}")
    private int pdfDpi;

    @org.springframework.beans.factory.annotation.Value("${app.parser.ocr.tesseract.maxPages:0}")
    private int maxPages;

    @Override
    public String getType() { return OCRType.TIKA; }

    @Override
    public String parseRapport(String fileName, InputStream stream) {
        return parseDocument(fileName, stream);
    }

    @Override
    public String parseFacture(String fileName, InputStream stream) {
        return parseDocument(fileName, stream);
    }

    @Override
    public String parseContrat(String fileName, InputStream stream) {
        return parseDocument(fileName, stream);
    }

    private String parseDocument(String fileName, InputStream stream) {
        LocalTime t1 = LocalTime.now();
        String text = "";
        try {
            byte[] bytes = stream.readAllBytes();
            text = TIKA.parseToString(new ByteArrayInputStream(bytes));
            if (isPdf(bytes) && !hasEnoughText(text)) {
                text = ocrPdf(bytes);
            }
            LocalTime t2 = LocalTime.now();
            Duration d = Duration.between(t1, t2);
            log.info("Millis Ã©coulÃ©s pour l'OCR Tika : {}" , d.toMillis());
            log.debug("Parsed content : {}", text);
        } catch (Exception e) {
            log.error("Parsing error for {}", fileName, e);
        }
        return text;
    }

    private boolean isPdf(byte[] content) {
        if (content.length < PDF_MAGIC.length) {
            return false;
        }
        for (int i = 0; i < PDF_MAGIC.length; i++) {
            if (content[i] != PDF_MAGIC[i]) {
                return false;
            }
        }
        return true;
    }

    private boolean hasEnoughText(String text) {
        return text != null && text.replaceAll("\\s+", "").length() >= minTextChars;
    }

    private String ocrPdf(byte[] content) throws Exception {
        StringBuilder builder = new StringBuilder();
        try (PDDocument document = Loader.loadPDF(content)) {
            PDFRenderer renderer = new PDFRenderer(document);
            int pageCount = document.getNumberOfPages();
            int limit = maxPages > 0 ? Math.min(pageCount, maxPages) : pageCount;
            for (int pageIndex = 0; pageIndex < limit; pageIndex++) {
                String pageText = tesseractParserService.doOcr(renderer.renderImageWithDPI(pageIndex, pdfDpi));
                if (pageText != null && !pageText.trim().isEmpty()) {
                    if (!builder.isEmpty()) {
                        builder.append(System.lineSeparator());
                    }
                    builder.append(pageText.trim());
                }
            }
        }
        return builder.toString();
    }
}

