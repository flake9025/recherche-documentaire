package fr.vvlabs.recherche.service.parser.markdown;

import fr.vvlabs.recherche.service.parser.OCRType;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;

class MarkdownParserServiceTest {

    private final MarkdownParserService service = new MarkdownParserService();

    @Test
    void getType_returnsMarkdownType() {
        assertThat(service.getType()).isEqualTo(OCRType.MARKDOWN);
    }

    @Test
    void parseRapport_extractsPlainTextAndStripsSyntax() {
        String markdown = """
                # Titre principal

                Un paragraphe avec du **gras** et de l'*italique*.

                - element un
                - element deux

                [Confluence](https://example.org)
                """;

        String result = service.parseRapport(
                "page.md",
                new ByteArrayInputStream(markdown.getBytes(StandardCharsets.UTF_8))
        );

        assertThat(result)
                .contains("Titre principal")
                .contains("Un paragraphe avec du gras et de l'italique.")
                .contains("element un")
                .contains("element deux")
                .contains("Confluence")
                .doesNotContain("**")
                .doesNotContain("https://example.org");
    }

    @Test
    void parseRapport_keepsCodeBlockContent() {
        String markdown = """
                Exemple :

                ```java
                int x = 42;
                ```
                """;

        String result = service.parseRapport(
                "code.md",
                new ByteArrayInputStream(markdown.getBytes(StandardCharsets.UTF_8))
        );

        assertThat(result).contains("int x = 42;");
    }

    @Test
    void parseRapport_returnsEmptyStringOnEmptyInput() {
        String result = service.parseRapport(
                "empty.md",
                new ByteArrayInputStream(new byte[0])
        );

        assertThat(result).isEmpty();
    }
}
