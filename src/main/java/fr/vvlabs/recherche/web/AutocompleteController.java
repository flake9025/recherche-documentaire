package fr.vvlabs.recherche.web;

import fr.vvlabs.recherche.dto.AutocompleteRebuildResultDTO;
import fr.vvlabs.recherche.service.index.lucene.LuceneAutocompleteService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.io.IOException;
import java.util.List;

/**
 * Expose les endpoints d'autocompletion des auteurs.
 */
@RestController
@RequiredArgsConstructor
@RequestMapping("/api/autocomplete")
@Tag(name = "Autocompletion", description = "API d'autocompletion")
@Slf4j
public class AutocompleteController {

    private final LuceneAutocompleteService luceneAutocompleteService;

    /**
     * Retourne des suggestions d'auteurs.
     *
     * @param query texte saisi par l'utilisateur
     * @param limit nombre maximum de suggestions
     * @return liste de suggestions triees
     * @throws IOException si l'acces a l'index echoue
     */
    @GetMapping("/authors")
    @Operation(summary = "Suggestions d'auteurs")
    public List<LuceneAutocompleteService.AuthorSuggestion> suggestAuthors(
            @Parameter(description = "Texte de recherche (minimum 2 caracteres)")
            @RequestParam String query,
            @Parameter(description = "Nombre maximum de resultats (par defaut: 10)")
            @RequestParam(defaultValue = "10") int limit
    ) throws IOException {
        if (luceneAutocompleteService.isSuggestIndexEmpty()) {
            log.info("Suggest index is empty, building from existing documents");
            buildAuthorSuggestIndex();
        }
        return luceneAutocompleteService.suggest(query, limit);
    }

    /**
     * Reconstruit l'index d'autocompletion des auteurs.
     *
     * @return resultat de reconstruction
     * @throws IOException si la reconstruction echoue
     */
    @PostMapping("/authors/rebuild")
    @Operation(summary = "Reconstruire l'index d'autocompletion des auteurs")
    public AutocompleteRebuildResultDTO rebuildAuthorIndex() throws IOException {
        log.info("Rebuilding author autocomplete index");

        long startTime = System.currentTimeMillis();
        buildAuthorSuggestIndex();
        long duration = System.currentTimeMillis() - startTime;

        long authorCount = luceneAutocompleteService.getAuthorCount();

        return new AutocompleteRebuildResultDTO(
                true,
                "Index d'autocompletion reconstruit",
                authorCount,
                duration
        );
    }

    private void buildAuthorSuggestIndex() throws IOException {
        luceneAutocompleteService.buildAuthorIndex();
        log.info("Author suggest index built with {} authors", luceneAutocompleteService.getAuthorCount());
    }
}
