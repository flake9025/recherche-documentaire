package fr.vvlabs.recherche.service.parser;

import lombok.Getter;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

@Component
@Slf4j
public class OCRServiceFactory {

    private final Map<String, OCRService> services;
    private final String defaultOcr;

    @Value("${app.parser.ocr.enabled}")
    @Getter
    private boolean ocrEnabled;

    public OCRServiceFactory(
            List<OCRService> ocrServices,
            @Value("${app.parser.ocr.default:pdfbox}") String defaultOcr
    ) {
        this.services = ocrServices.stream()
                .peek(service -> log.info("OCR detected: {}", service.getType()))
                .collect(Collectors.toMap(
                        OCRService::getType,
                        Function.identity()
                ));
        this.defaultOcr = defaultOcr;
    }

    public OCRService getDefaultOCRService() {
        return getOCRService(defaultOcr);
    }

    public OCRService getOCRService(String ocrType) {
        OCRService service = services.get(ocrType);
        if (service == null) {
            throw new IllegalStateException("Unknown OCR: " + ocrType);
        }
        return service;
    }
}

