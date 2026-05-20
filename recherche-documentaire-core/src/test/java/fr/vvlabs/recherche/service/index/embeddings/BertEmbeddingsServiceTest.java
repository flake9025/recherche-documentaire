package fr.vvlabs.recherche.service.index.embeddings;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class BertEmbeddingsServiceTest {

    @Test
    void buildIndexText_includesSameTextualFieldsAsLucene() throws Exception {
        BertEmbeddingsService service = new BertEmbeddingsService("sentence-transformers/all-MiniLM-L6-v2");

        String result = service.buildIndexText(
                "Rapport Qualite",
                "Marie-France FROMAGE",
                "note",
                "rapport.pdf",
                "Contenu du document"
        );

        assertThat(result).isEqualTo(
                "Rapport Qualite\n\nMarie-France FROMAGE\n\nnote\n\nrapport.pdf\n\nContenu du document"
        );
    }
}
