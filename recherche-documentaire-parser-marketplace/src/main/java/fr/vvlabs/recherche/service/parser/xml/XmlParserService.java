package fr.vvlabs.recherche.service.parser.xml;

import fr.vvlabs.recherche.service.parser.OCRService;
import fr.vvlabs.recherche.service.parser.OCRType;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;

import javax.xml.stream.XMLInputFactory;
import javax.xml.stream.XMLStreamConstants;
import javax.xml.stream.XMLStreamReader;
import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.LocalTime;
import java.util.Base64;
import java.util.LinkedHashSet;
import java.util.Set;
import java.util.zip.DataFormatException;
import java.util.zip.Inflater;

/**
 * Parser XML oriente diagrammes draw.io / diagrams.net, destine a l'ingestion Confluence.
 *
 * <p>Le parser extrait le texte lisible des noeuds {@code mxCell} (attributs
 * {@code value}) qui portent les libelles des formes et des liens. Il gere les
 * deux formats produits par draw.io :</p>
 * <ul>
 *   <li>modeles non compresses ({@code <mxGraphModel>...<mxCell value="..."/>}) ;</li>
 *   <li>diagrammes compresses ({@code <diagram>} contenant une charge utile
 *   base64 + deflate + URL-encoded), qui est decodee puis analysee a son tour.</li>
 * </ul>
 *
 * <p>Le libelle draw.io pouvant contenir du HTML, les balises sont retirees et
 * les entites usuelles decodees pour ne conserver que le texte.</p>
 */
@Service
@ConditionalOnProperty(name = "app.parser.ocr.default", havingValue = OCRType.XML)
@ConditionalOnProperty(name = "app.parser.ocr.enabled", havingValue = "true")
@Slf4j
public class XmlParserService implements OCRService {

    private static final XMLInputFactory XML_INPUT_FACTORY = createSecureFactory();

    @Override
    public String getType() {
        return OCRType.XML;
    }

    @Override
    public String parseRapport(String fileName, InputStream stream) {
        return parseXml(fileName, stream);
    }

    @Override
    public String parseFacture(String fileName, InputStream stream) {
        return parseXml(fileName, stream);
    }

    @Override
    public String parseContrat(String fileName, InputStream stream) {
        return parseXml(fileName, stream);
    }

    private String parseXml(String fileName, InputStream stream) {
        LocalTime startTime = LocalTime.now();
        StringBuilder builder = new StringBuilder();
        try {
            byte[] bytes = stream.readAllBytes();
            extractFromXml(bytes, builder);
            log.info("Millis ecoules pour le parsing XML : {}", Duration.between(startTime, LocalTime.now()).toMillis());
            log.debug("Parsed content : {}", builder);
        } catch (Exception e) {
            log.error("Parsing error for {}: {}", fileName, e.getMessage(), e);
        }
        return builder.toString().strip();
    }

    /**
     * Analyse un flux XML draw.io et ajoute au buffer les libelles rencontres.
     * Les diagrammes compresses sont decodes et analyses recursivement.
     */
    private void extractFromXml(byte[] bytes, StringBuilder builder) throws Exception {
        XMLStreamReader reader = XML_INPUT_FACTORY.createXMLStreamReader(new ByteArrayInputStream(bytes));
        try {
            String currentElement = null;
            StringBuilder diagramText = new StringBuilder();
            boolean inDiagram = false;

            while (reader.hasNext()) {
                int event = reader.next();
                switch (event) {
                    case XMLStreamConstants.START_ELEMENT -> {
                        currentElement = reader.getLocalName();
                        if ("diagram".equals(currentElement)) {
                            inDiagram = true;
                            diagramText.setLength(0);
                        }
                        String value = getAttribute(reader, "value");
                        appendText(value, builder);
                    }
                    case XMLStreamConstants.CHARACTERS, XMLStreamConstants.CDATA -> {
                        if (inDiagram) {
                            diagramText.append(reader.getText());
                        }
                    }
                    case XMLStreamConstants.END_ELEMENT -> {
                        if ("diagram".equals(reader.getLocalName())) {
                            inDiagram = false;
                            decodeCompressedDiagram(diagramText.toString(), builder);
                        }
                        currentElement = null;
                    }
                    default -> {
                        // autres evenements ignores
                    }
                }
            }
        } finally {
            reader.close();
        }
    }

    /**
     * Tente de decoder une charge utile draw.io compressee (base64 -> deflate ->
     * URL decode). En cas d'echec (diagramme deja en clair ou vide), rien n'est ajoute.
     */
    private void decodeCompressedDiagram(String payload, StringBuilder builder) {
        String trimmed = payload == null ? "" : payload.strip();
        if (trimmed.isEmpty()) {
            return;
        }
        try {
            byte[] compressed = Base64.getDecoder().decode(trimmed);
            byte[] inflated = inflate(compressed);
            if (inflated.length == 0) {
                return;
            }
            String urlEncoded = new String(inflated, StandardCharsets.UTF_8);
            String innerXml = URLDecoder.decode(urlEncoded, StandardCharsets.UTF_8);
            extractFromXml(innerXml.getBytes(StandardCharsets.UTF_8), builder);
        } catch (Exception e) {
            log.debug("Diagramme draw.io non compresse ou illisible, ignore : {}", e.getMessage());
        }
    }

    private byte[] inflate(byte[] data) throws DataFormatException {
        Inflater inflater = new Inflater(true);
        try {
            inflater.setInput(data);
            java.io.ByteArrayOutputStream output = new java.io.ByteArrayOutputStream(data.length * 4);
            byte[] buffer = new byte[1024];
            while (!inflater.finished()) {
                int count = inflater.inflate(buffer);
                if (count == 0 && inflater.needsInput()) {
                    break;
                }
                output.write(buffer, 0, count);
            }
            return output.toByteArray();
        } finally {
            inflater.end();
        }
    }

    private String getAttribute(XMLStreamReader reader, String name) {
        for (int i = 0; i < reader.getAttributeCount(); i++) {
            if (name.equals(reader.getAttributeLocalName(i))) {
                return reader.getAttributeValue(i);
            }
        }
        return null;
    }

    /**
     * Nettoie un libelle draw.io (potentiellement du HTML) et l'ajoute au buffer.
     */
    private void appendText(String raw, StringBuilder builder) {
        if (raw == null || raw.isBlank()) {
            return;
        }
        String text = stripHtml(raw).strip();
        if (text.isEmpty()) {
            return;
        }
        if (builder.length() > 0) {
            builder.append(System.lineSeparator());
        }
        builder.append(text);
    }

    private String stripHtml(String value) {
        String noTags = value.replaceAll("<[^>]+>", " ");
        return noTags
                .replace("&nbsp;", " ")
                .replace("&amp;", "&")
                .replace("&lt;", "<")
                .replace("&gt;", ">")
                .replace("&quot;", "\"")
                .replace("&#39;", "'")
                .replaceAll("\\s+", " ");
    }

    private static XMLInputFactory createSecureFactory() {
        XMLInputFactory factory = XMLInputFactory.newFactory();
        factory.setProperty(XMLInputFactory.IS_SUPPORTING_EXTERNAL_ENTITIES, Boolean.FALSE);
        factory.setProperty(XMLInputFactory.SUPPORT_DTD, Boolean.FALSE);
        return factory;
    }
}
