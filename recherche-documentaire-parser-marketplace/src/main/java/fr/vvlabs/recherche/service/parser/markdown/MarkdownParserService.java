package fr.vvlabs.recherche.service.parser.markdown;

import fr.vvlabs.recherche.service.parser.OCRService;
import fr.vvlabs.recherche.service.parser.OCRType;
import lombok.extern.slf4j.Slf4j;
import org.commonmark.node.AbstractVisitor;
import org.commonmark.node.Code;
import org.commonmark.node.FencedCodeBlock;
import org.commonmark.node.HardLineBreak;
import org.commonmark.node.Heading;
import org.commonmark.node.IndentedCodeBlock;
import org.commonmark.node.ListItem;
import org.commonmark.node.Node;
import org.commonmark.node.Paragraph;
import org.commonmark.node.SoftLineBreak;
import org.commonmark.node.Text;
import org.commonmark.parser.Parser;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.LocalTime;

/**
 * Parser Markdown destine a l'ingestion Confluence.
 *
 * <p>Le contenu Markdown est analyse en AST via commonmark, puis le texte lisible
 * (titres, paragraphes, listes, blocs de code) est extrait en supprimant la
 * syntaxe de mise en forme afin de fournir un texte propre a l'indexation.</p>
 */
@Service
@ConditionalOnProperty(name = "app.parser.ocr.default", havingValue = OCRType.MARKDOWN)
@ConditionalOnProperty(name = "app.parser.ocr.enabled", havingValue = "true")
@Slf4j
public class MarkdownParserService implements OCRService {

    private static final Parser PARSER = Parser.builder().build();

    @Override
    public String getType() {
        return OCRType.MARKDOWN;
    }

    @Override
    public String parseRapport(String fileName, InputStream stream) {
        return parseMarkdown(fileName, stream);
    }

    @Override
    public String parseFacture(String fileName, InputStream stream) {
        return parseMarkdown(fileName, stream);
    }

    @Override
    public String parseContrat(String fileName, InputStream stream) {
        return parseMarkdown(fileName, stream);
    }

    private String parseMarkdown(String fileName, InputStream stream) {
        LocalTime startTime = LocalTime.now();
        String text = "";
        try {
            String markdown = new String(stream.readAllBytes(), StandardCharsets.UTF_8);
            Node document = PARSER.parse(markdown);
            TextExtractingVisitor visitor = new TextExtractingVisitor();
            document.accept(visitor);
            text = visitor.getText();
            log.info("Millis ecoules pour le parsing Markdown : {}", Duration.between(startTime, LocalTime.now()).toMillis());
            log.debug("Parsed content : {}", text);
        } catch (Exception e) {
            log.error("Parsing error for {}: {}", fileName, e.getMessage(), e);
        }
        return text;
    }

    /**
     * Visiteur qui reconstruit un texte lisible depuis l'AST Markdown en
     * separant les blocs par des sauts de ligne et en ignorant la syntaxe.
     */
    private static final class TextExtractingVisitor extends AbstractVisitor {

        private final StringBuilder builder = new StringBuilder();

        String getText() {
            return builder.toString().strip();
        }

        @Override
        public void visit(Text text) {
            builder.append(text.getLiteral());
        }

        @Override
        public void visit(Code code) {
            builder.append(code.getLiteral());
        }

        @Override
        public void visit(FencedCodeBlock fencedCodeBlock) {
            appendBlock(fencedCodeBlock.getLiteral());
        }

        @Override
        public void visit(IndentedCodeBlock indentedCodeBlock) {
            appendBlock(indentedCodeBlock.getLiteral());
        }

        @Override
        public void visit(SoftLineBreak softLineBreak) {
            builder.append(' ');
        }

        @Override
        public void visit(HardLineBreak hardLineBreak) {
            builder.append(System.lineSeparator());
        }

        @Override
        public void visit(Heading heading) {
            visitChildren(heading);
            newBlock();
        }

        @Override
        public void visit(Paragraph paragraph) {
            visitChildren(paragraph);
            newBlock();
        }

        @Override
        public void visit(ListItem listItem) {
            visitChildren(listItem);
            newBlock();
        }

        private void appendBlock(String literal) {
            if (literal == null || literal.isBlank()) {
                return;
            }
            builder.append(literal.strip());
            newBlock();
        }

        private void newBlock() {
            if (builder.length() > 0 && builder.charAt(builder.length() - 1) != '\n') {
                builder.append(System.lineSeparator());
            }
        }
    }
}
