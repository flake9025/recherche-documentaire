package fr.vvlabs.recherche.service.parser.pdfbox;

import fr.vvlabs.recherche.service.parser.OCRService;
import fr.vvlabs.recherche.service.parser.OCRType;
import fr.vvlabs.recherche.service.parser.tesseract.TesseractParserService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.rendering.PDFRenderer;
import org.apache.pdfbox.text.PDFTextStripper;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;

import java.io.InputStream;
import java.time.Duration;
import java.time.LocalTime;

@Service
@ConditionalOnProperty(name = "app.parser.ocr.default", havingValue = OCRType.PDFBOX)
@ConditionalOnProperty(name = "app.parser.ocr.enabled", havingValue = "true")
@Slf4j
@RequiredArgsConstructor
public class PDFBoxParserService implements OCRService {

    @Value("${app.parser.ocr.enabled}")
    private boolean ocrEnabled;

    private static final ThreadLocal<PDFTextStripper> PDF_TEXT_STRIPPER =
            ThreadLocal.withInitial(PDFTextStripper::new);

    @Value("${app.parser.ocr.textDetection.minChars:30}")
    private int minTextChars;

    @Value("${app.parser.ocr.tesseract.pdfDpi:200}")
    private int pdfDpi;

    @Value("${app.parser.ocr.tesseract.maxPages:0}")
    private int maxPages;

    private final TesseractParserService tesseractParserService;

    @Override
    public String getType() {
        return OCRType.PDFBOX;
    }

    @Override
    public String parseRapport(String fileName, InputStream stream) {
        return parsePDF(fileName, stream);
    }

    @Override
    public String parseFacture(String fileName, InputStream stream) {
        return parsePDF(fileName, stream);
    }

    @Override
    public String parseContrat(String fileName, InputStream stream) {
        return parsePDF(fileName, stream);
    }

    private String parsePDF(String fileName, InputStream stream) {
        LocalTime startTime = LocalTime.now();
        String text = "";
        try {
            byte[] pdfBytes = stream.readAllBytes();
            try (PDDocument document = Loader.loadPDF(pdfBytes)) {
                PDFTextStripper stripper = PDF_TEXT_STRIPPER.get();
                stripper.setSortByPosition(true);
                stripper.setStartPage(1);
                stripper.setEndPage(document.getNumberOfPages());
                text = stripper.getText(document);

                if (text == null || text.replaceAll("\\s+", "").length() < minTextChars) {
                    StringBuilder builder = new StringBuilder();
                    PDFRenderer renderer = new PDFRenderer(document);
                    int pageCount = document.getNumberOfPages();
                    int limit = maxPages > 0 ? Math.min(pageCount, maxPages) : pageCount;
                    for (int pageIndex = 0; pageIndex < limit; pageIndex++) {
                        String pageText = tesseractParserService.doOcr(renderer.renderImageWithDPI(pageIndex, pdfDpi));
                        if (pageText != null && !pageText.trim().isEmpty()) {
                            if (builder.length() > 0) {
                                builder.append(System.lineSeparator());
                            }
                            builder.append(pageText.trim());
                        }
                    }
                    text = builder.toString();
                }
            }
            log.info("Millis ecoules pour l'OCR Pdfbox : {}", Duration.between(startTime, LocalTime.now()).toMillis());
            log.debug("Parsed content : {}", text);
        } catch (Exception e) {
            log.error("Parsing error for {}: {}", fileName, e.getMessage(), e);
        }
        return text;
    }
}
