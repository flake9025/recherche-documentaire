package fr.vvlabs.recherche.service.business.index.lucene;

import fr.vvlabs.recherche.config.LuceneConfig;
import fr.vvlabs.recherche.service.business.document.DocumentService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.lucene.search.suggest.InputIterator;
import org.apache.lucene.util.BytesRef;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.util.*;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
@Slf4j
public class LuceneAutocompleteService {

    private final LuceneConfig luceneConfig;
    private final DocumentService documentService;

    /**
     * Ajoute ou met Ã  jour un auteur dans l'index de suggestions
     * 
     * @param author Le nom de l'auteur
     * @param weight Le poids (nombre de documents de cet auteur)
     */
    public void addAuthor(String author, long weight) throws IOException {
        if (author == null || author.trim().isEmpty()) {
            return;
        }
        
        String normalizedAuthor = author.trim();
        
        // Convertir l'auteur en BytesRef
        BytesRef payload = new BytesRef(normalizedAuthor);
        
        // Ajouter Ã  l'index de suggestions
        luceneConfig.getAuthorsSuggester().add(new BytesRef(normalizedAuthor), null, weight, payload);
    }

    /**
     * Construit l'index d'autocomplÃ©tion Ã  partir d'une map d'auteurs
     * 
     */
    public void buildAuthorIndex() throws IOException {
        log.info("Building author autocomplete index");

        // Compter le nombre de documents par auteur
        Map<String, Long> authorCounts = documentService.findAll().stream()
                .map(doc -> doc.getAuteur())
                .filter(author -> author != null && !author.trim().isEmpty())
                .collect(Collectors.groupingBy(
                        author -> author,
                        Collectors.counting()
                ));

        // Convertir la map en liste de suggestions
        List<SuggestionInput> suggestions = authorCounts.entrySet().stream()
            .filter(entry -> entry.getKey() != null && !entry.getKey().trim().isEmpty())
            .map(entry -> new SuggestionInput(
                entry.getKey().trim(),
                entry.getValue()
            ))
            .collect(Collectors.toList());
        
        // Construire l'index en une seule fois
        luceneConfig.getAuthorsSuggester().build(new SuggestionInputIterator(suggestions));
        
        // Commit les changements
        luceneConfig.getAuthorsSuggester().commit();
        
        log.info("Author autocomplete index built successfully");
    }

    /**
     * Recherche des suggestions d'auteurs
     * 
     * @param prefix Le texte saisi par l'utilisateur
     * @param maxResults Nombre maximum de suggestions Ã  retourner
     * @return Liste des suggestions
     */
    public List<AuthorSuggestion> suggest(String prefix, int maxResults) throws IOException {
        if (prefix == null || prefix.trim().isEmpty()) {
            return Collections.emptyList();
        }

        List<AuthorSuggestion> results = new ArrayList<>();
        
        try {
            // Rechercher les suggestions
            // allTermsRequired = false : cherche dans tous les mots (permet "France" pour "Marie-France MAGGI")
            // highlight = true : met en Ã©vidence les correspondances
            var lookupResults = luceneConfig.getAuthorsSuggester().lookup(prefix.trim(), false, maxResults);
            
            for (var result : lookupResults) {
                AuthorSuggestion suggestion = new AuthorSuggestion();
                suggestion.setAuthor(result.key.toString());
                suggestion.setWeight(result.value);
                suggestion.setHighlight(result.highlightKey != null ? result.highlightKey.toString() : result.key.toString());
                results.add(suggestion);
            }
            
            log.debug("Found {} suggestions for prefix '{}'", results.size(), prefix);
        } catch (Exception e) {
            log.error("Error during author suggestion lookup for prefix '{}': {}", prefix, e.getMessage(), e);
        }
        
        return results;
    }

    /**
     * VÃ©rifie si l'index de suggestions est vide
     */
    public boolean isSuggestIndexEmpty() throws IOException {
        return luceneConfig.getAuthorsSuggester().getCount() == 0;
    }

    /**
     * Obtient le nombre d'auteurs dans l'index
     */
    public long getAuthorCount() throws IOException {
        return luceneConfig.getAuthorsSuggester().getCount();
    }

    /**
     * RafraÃ®chit le suggester (utile aprÃ¨s des modifications)
     */
    public void refresh() throws IOException {
        log.info("Refreshing author autocomplete index");
        luceneConfig.getAuthorsSuggester().refresh();
    }

    /**
     * Classe interne pour les rÃ©sultats de suggestion
     */
    public static class AuthorSuggestion {
        private String author;
        private long weight;
        private String highlight;

        public String getAuthor() {
            return author;
        }

        public void setAuthor(String author) {
            this.author = author;
        }

        public long getWeight() {
            return weight;
        }

        public void setWeight(long weight) {
            this.weight = weight;
        }

        public String getHighlight() {
            return highlight;
        }

        public void setHighlight(String highlight) {
            this.highlight = highlight;
        }
    }

    /**
     * Classe interne pour construire l'index
     */
    private static class SuggestionInput {
        final String text;
        final long weight;

        SuggestionInput(String text, long weight) {
            this.text = text;
            this.weight = weight;
        }
    }

    /**
     * ItÃ©rateur pour construire l'index de suggestions
     */
    private static class SuggestionInputIterator implements InputIterator {
        private final Iterator<SuggestionInput> iterator;
        private SuggestionInput current;

        SuggestionInputIterator(List<SuggestionInput> suggestions) {
            this.iterator = suggestions.iterator();
        }

        @Override
        public BytesRef next() {
            if (iterator.hasNext()) {
                current = iterator.next();
                return new BytesRef(current.text);
            }
            return null;
        }

        @Override
        public long weight() {
            return current != null ? current.weight : 0;
        }

        @Override
        public BytesRef payload() {
            return current != null ? new BytesRef(current.text) : null;
        }

        @Override
        public boolean hasPayloads() {
            return true;
        }

        @Override
        public Set<BytesRef> contexts() {
            return null;
        }

        @Override
        public boolean hasContexts() {
            return false;
        }
    }
}
