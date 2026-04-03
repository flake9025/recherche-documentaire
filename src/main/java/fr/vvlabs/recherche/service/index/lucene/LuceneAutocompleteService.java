package fr.vvlabs.recherche.service.index.lucene;

import fr.vvlabs.recherche.config.LuceneConfig;
import fr.vvlabs.recherche.service.document.DocumentService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.lucene.search.suggest.InputIterator;
import org.apache.lucene.util.BytesRef;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.text.Normalizer;
import java.util.Collections;
import java.util.Comparator;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
@Slf4j
public class LuceneAutocompleteService {

    private final LuceneConfig luceneConfig;
    private final DocumentService documentService;
    private final Map<String, AuthorAggregate> authorAggregates = new ConcurrentHashMap<>();

    public void addAuthor(String author, long weight) throws IOException {
        if (author == null || author.trim().isEmpty()) {
            return;
        }

        synchronized (authorAggregates) {
            mergeAuthor(author, weight);
            rebuildSuggesterFromAggregates();
        }
    }

    public void buildAuthorIndex() throws IOException {
        log.info("Building author autocomplete index");

        Map<String, Long> authorCounts = documentService.findAll().stream()
                .map(doc -> doc.getAuteur())
                .filter(author -> author != null && !author.trim().isEmpty())
                .collect(Collectors.groupingBy(author -> author, Collectors.counting()));

        synchronized (authorAggregates) {
            authorAggregates.clear();
            authorCounts.forEach(this::mergeAuthor);
            rebuildSuggesterFromAggregates();
        }

        log.info("Author autocomplete index built successfully");
    }

    public List<AuthorSuggestion> suggest(String prefix, int maxResults) throws IOException {
        if (prefix == null || prefix.trim().isEmpty()) {
            return Collections.emptyList();
        }

        try {
            var lookupResults = luceneConfig.getAuthorsSuggester().lookup(prefix.trim(), false, maxResults);
            Map<String, AuthorSuggestion> groupedResults = new LinkedHashMap<>();

            for (var result : lookupResults) {
                String author = result.key.toString();
                String key = canonicalizeAuthor(author);
                AuthorSuggestion existing = groupedResults.get(key);
                if (existing == null) {
                    AuthorSuggestion suggestion = new AuthorSuggestion();
                    suggestion.setAuthor(author);
                    suggestion.setWeight(result.value);
                    suggestion.setHighlight(result.highlightKey != null ? result.highlightKey.toString() : author);
                    groupedResults.put(key, suggestion);
                    continue;
                }

                existing.setWeight(existing.getWeight() + result.value);
                if (isBetterDisplay(author, existing.getAuthor())) {
                    existing.setAuthor(author);
                    existing.setHighlight(result.highlightKey != null ? result.highlightKey.toString() : author);
                }
            }

            List<AuthorSuggestion> results = groupedResults.values().stream()
                    .sorted(Comparator.comparingLong(AuthorSuggestion::getWeight).reversed()
                            .thenComparing(AuthorSuggestion::getAuthor, String.CASE_INSENSITIVE_ORDER))
                    .limit(maxResults)
                    .toList();

            log.debug("Found {} suggestions for prefix '{}'", results.size(), prefix);
            return results;
        } catch (Exception e) {
            log.error("Error during author suggestion lookup for prefix '{}': {}", prefix, e.getMessage(), e);
            return Collections.emptyList();
        }
    }

    public boolean isSuggestIndexEmpty() throws IOException {
        return luceneConfig.getAuthorsSuggester().getCount() == 0;
    }

    public long getAuthorCount() throws IOException {
        return luceneConfig.getAuthorsSuggester().getCount();
    }

    public void refresh() throws IOException {
        log.info("Refreshing author autocomplete index");
        luceneConfig.getAuthorsSuggester().refresh();
    }

    void mergeAuthor(String author, long weight) {
        String displayAuthor = author == null ? "" : author.trim();
        String canonicalAuthor = canonicalizeAuthor(displayAuthor);
        if (canonicalAuthor.isEmpty()) {
            return;
        }

        authorAggregates.compute(canonicalAuthor, (key, existing) -> {
            if (existing == null) {
                return new AuthorAggregate(displayAuthor, weight);
            }
            existing.weight += weight;
            if (isBetterDisplay(displayAuthor, existing.displayAuthor)) {
                existing.displayAuthor = displayAuthor;
            }
            return existing;
        });
    }

    String canonicalizeAuthor(String author) {
        if (author == null) {
            return "";
        }
        return Normalizer.normalize(author, Normalizer.Form.NFD)
                .replaceAll("\\p{M}+", "")
                .toLowerCase(Locale.ROOT)
                .replaceAll("[^\\p{L}\\p{N}]+", " ")
                .trim()
                .replaceAll("\\s+", " ");
    }

    private void rebuildSuggesterFromAggregates() throws IOException {
        List<SuggestionInput> suggestions = authorAggregates.values().stream()
                .map(entry -> new SuggestionInput(entry.displayAuthor, entry.weight))
                .sorted(Comparator.comparingLong((SuggestionInput entry) -> entry.weight).reversed()
                        .thenComparing(entry -> entry.text, String.CASE_INSENSITIVE_ORDER))
                .toList();

        luceneConfig.getAuthorsSuggester().build(new SuggestionInputIterator(suggestions));
        luceneConfig.getAuthorsSuggester().commit();
    }

    private boolean isBetterDisplay(String candidate, String current) {
        if (current == null || current.isBlank()) {
            return true;
        }
        if (candidate == null || candidate.isBlank()) {
            return false;
        }
        int candidateScore = displayScore(candidate);
        int currentScore = displayScore(current);
        if (candidateScore != currentScore) {
            return candidateScore > currentScore;
        }
        return candidate.length() < current.length();
    }

    private int displayScore(String value) {
        String trimmed = value.trim();
        if (trimmed.equals(trimmed.toUpperCase(Locale.ROOT))) {
            return 0;
        }
        if (trimmed.equals(trimmed.toLowerCase(Locale.ROOT))) {
            return 1;
        }
        return 2;
    }

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

    private static class SuggestionInput {
        final String text;
        final long weight;

        SuggestionInput(String text, long weight) {
            this.text = text;
            this.weight = weight;
        }
    }

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

    private static class AuthorAggregate {
        private String displayAuthor;
        private long weight;

        private AuthorAggregate(String displayAuthor, long weight) {
            this.displayAuthor = displayAuthor;
            this.weight = weight;
        }
    }
}
