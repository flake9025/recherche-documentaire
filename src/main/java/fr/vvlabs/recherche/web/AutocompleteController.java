package fr.vvlabs.recherche.web;

import fr.vvlabs.recherche.service.business.index.lucene.LuceneAutocompleteService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.*;

import java.io.IOException;
import java.util.List;
import java.util.Map;

@RestController
@RequiredArgsConstructor
@RequestMapping("/api/autocomplete")
@Tag(name = "AutocomplÃ©tion", description = "API d'autocomplÃ©tion")
@Slf4j
public class AutocompleteController {

    private final LuceneAutocompleteService luceneAutocompleteService;

    @GetMapping("/authors")
    @Operation(summary = "Suggestions d'auteurs")
    public List<LuceneAutocompleteService.AuthorSuggestion> suggestAuthors(
            @Parameter(description = "Texte de recherche (minimum 2 caractÃ¨res)")
            @RequestParam String query,
            @Parameter(description = "Nombre maximum de rÃ©sultats (par dÃ©faut: 10)")
            @RequestParam(defaultValue = "10") int limit
    ) throws IOException {
        
        // Si l'index est vide, le construire Ã  partir des documents existants
        if (luceneAutocompleteService.isSuggestIndexEmpty()) {
            log.info("Suggest index is empty, building from existing documents");
            buildAuthorSuggestIndex();
        }
        return luceneAutocompleteService.suggest(query, limit);
    }

    @PostMapping("/authors/rebuild")
    @Operation(summary = "Reconstruire l'index d'autocomplÃ©tion des auteurs")
    public Map<String, Object> rebuildAuthorIndex() throws IOException {
        log.info("Rebuilding author autocomplete index");
        
        long startTime = System.currentTimeMillis();
        buildAuthorSuggestIndex();
        long duration = System.currentTimeMillis() - startTime;
        
        long authorCount = luceneAutocompleteService.getAuthorCount();
        
        return Map.of(
            "success", true,
            "message", "Index d'autocomplÃ©tion reconstruit",
            "authorCount", authorCount,
            "durationMs", duration
        );
    }

    /**
     * Construit l'index de suggestions Ã  partir des documents existants
     */
    private void buildAuthorSuggestIndex() throws IOException {
        luceneAutocompleteService.buildAuthorIndex();
        log.info("Author suggest index built with {} authors", luceneAutocompleteService.getAuthorCount());
    }
}
