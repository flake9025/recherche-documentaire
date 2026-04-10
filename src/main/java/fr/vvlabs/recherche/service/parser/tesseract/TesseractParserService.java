package fr.vvlabs.recherche.service.parser.tesseract;

import fr.vvlabs.recherche.service.parser.OCRService;
import fr.vvlabs.recherche.service.parser.OCRType;
import lombok.extern.slf4j.Slf4j;
import net.sourceforge.tess4j.Tesseract;
import net.sourceforge.tess4j.TesseractException;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.time.Duration;
import java.time.LocalTime;

@Service
@ConditionalOnProperty(name = "app.parser.ocr.enabled", havingValue = "true")
@Slf4j
public class TesseractParserService implements OCRService {

    @Value("${app.parser.ocr.tesseract.dataPath:}")
    private String dataPath;

    @Value("${app.parser.ocr.tesseract.language:eng}")
    private String language;

    @Value("${app.parser.ocr.tesseract.psm:-1}")
    private int pageSegMode;

    @Value("${app.parser.ocr.tesseract.oem:-1}")
    private int ocrEngineMode;

    private final ThreadLocal<Tesseract> tesseractHolder = new ThreadLocal<>();

    @Override
    public String getType() { return OCRType.TESSERACT; }

    @Override
    public String parseRapport(String fileName, InputStream stream) {
        return parseImage(fileName, stream);
    }

    @Override
    public String parseFacture(String fileName, InputStream stream) {
        return parseImage(fileName, stream);
    }

    @Override
    public String parseContrat(String fileName, InputStream stream) {
        return parseImage(fileName, stream);
    }

    private String parseImage(String fileName, InputStream stream) {
        String text = "";
        LocalTime t1 = LocalTime.now();
        try {
            byte[] bytes = stream.readAllBytes();
            BufferedImage image = ImageIO.read(new ByteArrayInputStream(bytes));
            if (image == null) {
                log.error("Unsupported image format for {}", fileName);
                return "";
            }
            text = doOcr(image);
        } catch (TesseractException | java.io.IOException e) {
            log.error("Parsing error for {}", fileName, e);
        }
        LocalTime t2 = LocalTime.now();
        Duration d = Duration.between(t1, t2);
        log.info("Millis Ã©coulÃ©s pour l'OCR Image : {}" , d.toMillis());
        log.debug("Parsed content : {}", text);
        return text;
    }

    public String doOcr(BufferedImage image) throws TesseractException {
        if (image == null) {
            return "";
        }
        LocalTime t1 = LocalTime.now();
        Tesseract tesseract = getTesseract();
        String text = tesseract.doOCR(image);
        LocalTime t2 = LocalTime.now();
        Duration d = Duration.between(t1, t2);
        log.info("Millis Ã©coulÃ©s pour l'OCR Image : {}" , d.toMillis());
        log.debug("Parsed content : {}", text);
        return text;
    }

    private Tesseract getTesseract() {
        Tesseract tesseract = tesseractHolder.get();
        if (tesseract != null) {
            return tesseract;
        }
        tesseract = new Tesseract();
        if (hasText(dataPath)) {
            tesseract.setDatapath(dataPath);
        }
        if (hasText(language)) {
            tesseract.setLanguage(language);
        }
        if (pageSegMode >= 0) {
            tesseract.setPageSegMode(pageSegMode);
        }
        if (ocrEngineMode >= 0) {
            tesseract.setOcrEngineMode(ocrEngineMode);
        }
        tesseractHolder.set(tesseract);
        return tesseract;
    }

    private boolean hasText(String value) {
        return value != null && !value.trim().isEmpty();
    }
}

