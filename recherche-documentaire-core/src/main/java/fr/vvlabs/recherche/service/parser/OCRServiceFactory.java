package fr.vvlabs.recherche.service.parser;

import lombok.Getter;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;

@Component
@Slf4j
public class OCRServiceFactory {

    private final Map<String, OCRService> services;
    private final String defaultOcr;
    @Getter
    private final boolean ocrEnabled;

    public OCRServiceFactory(
            List<OCRService> ocrServices,
            @Value("${app.parser.ocr.default:pdfbox}") String defaultOcr,
            @Value("${app.parser.ocr.enabled:false}") boolean ocrEnabled
    ) {
        this.services = ocrServices.stream()
                .peek(service -> log.info("OCR detected: {}", service.getType()))
                .collect(Collectors.toMap(
                        OCRService::getType,
                        Function.identity()
                ));
        this.defaultOcr = defaultOcr;
        this.ocrEnabled = ocrEnabled;
        validateDefaultService();
    }

    public OCRService getDefaultOCRService() {
        return getOCRService(defaultOcr);
    }

    public OCRService getOCRService(String ocrType) {
        OCRService service = services.get(ocrType);
        if (service == null) {
            throw new IllegalStateException(buildUnknownServiceMessage("OCR service", ocrType));
        }
        return service;
    }

    private void validateDefaultService() {
        if (!ocrEnabled) {
            return;
        }
        if (!services.containsKey(defaultOcr)) {
            throw new IllegalStateException(buildUnknownServiceMessage("default OCR service", defaultOcr));
        }
    }

    private String buildUnknownServiceMessage(String label, String requestedType) {
        Set<String> availableTypes = services.keySet();
        return "Unknown " + label + ": " + requestedType + ". Available types: " + availableTypes
                + ". Check app.parser.ocr.default/app.parser.ocr.enabled and bean conditional configuration.";
    }
}

