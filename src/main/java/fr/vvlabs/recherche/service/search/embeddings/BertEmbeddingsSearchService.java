package fr.vvlabs.recherche.service.search.embeddings;

import fr.vvlabs.recherche.dto.SearchFragmentDTO;
import fr.vvlabs.recherche.dto.SearchRequestDTO;
import fr.vvlabs.recherche.dto.SearchResultDTO;
import fr.vvlabs.recherche.service.business.index.IndexType;
import fr.vvlabs.recherche.service.business.index.embeddings.BertEmbeddingDocument;
import fr.vvlabs.recherche.service.business.index.embeddings.BertEmbeddingsService;
import fr.vvlabs.recherche.service.business.index.embeddings.BertEmbeddingsStore;
import fr.vvlabs.recherche.service.search.SearchService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
@Slf4j
public class BertEmbeddingsSearchService implements SearchService {

    private static final Pattern TOKEN_SPLIT = Pattern.compile("[^\\p{L}\\p{N}]+");
    private static final DateTimeFormatter RESULT_DATE_FORMATTER =
            DateTimeFormatter.ofPattern("dd/MM/yyyy HH:mm:ss", Locale.FRANCE);

    private final BertEmbeddingsService bertEmbeddingsService;
    private final BertEmbeddingsStore bertEmbeddingsStore;

    @Value("${app.embeddings.search.max-results:25}")
    private int maxResults;

    @Value("${app.embeddings.search.min-score:0.35}")
    private double minScore;

    @Value("${app.embeddings.search.semantic-weight:0.75}")
    private double semanticWeight;

    @Value("${app.embeddings.search.lexical-weight:0.25}")
    private double lexicalWeight;

    @Override
    public String getType() {
        return IndexType.BERT;
    }

    @Override
    public SearchResultDTO search(SearchRequestDTO request) {
        SearchRequestDTO effectiveRequest = request == null ? new SearchRequestDTO() : request;
        String query = effectiveRequest.getQuery() == null ? "" : effectiveRequest.getQuery().trim();
        float[] queryVector = bertEmbeddingsService.generateEmbedding(query);
        Set<String> queryTokens = tokenize(query);

        List<SearchFragmentDTO> fragments = bertEmbeddingsStore.findAll().stream()
                .filter(entity -> matchesFilters(entity, effectiveRequest))
                .map(entity -> toSearchFragment(entity, query, queryTokens, queryVector))
                .filter(fragment -> query.isBlank() || fragment.getScore() >= minScore)
                .sorted(Comparator.comparing(SearchFragmentDTO::getScore).reversed())
                .limit(maxResults)
                .toList();

        SearchResultDTO result = new SearchResultDTO();
        result.setFragments(fragments);
        result.setNbResults(fragments.size());
        return result;
    }

    @Override
    public boolean isSearchStoreEmpty() {
        return bertEmbeddingsStore.count() == 0;
    }

    private SearchFragmentDTO toSearchFragment(
            BertEmbeddingDocument entity,
            String query,
            Set<String> queryTokens,
            float[] queryVector
    ) {
        float semanticScore = queryVector.length == 0 ? 1.0f : cosineSimilarity(queryVector, entity.embedding());
        float lexicalScore = query.isBlank() ? 1.0f : lexicalScore(entity, query, queryTokens);
        float score = query.isBlank()
                ? semanticScore
                : (float) ((semanticWeight * semanticScore) + (lexicalWeight * lexicalScore));

        SearchFragmentDTO fragment = new SearchFragmentDTO();
        fragment.setId(entity.documentId().toString());
        fragment.setName(entity.title());
        fragment.setAuthor(entity.author());
        fragment.setCategory(entity.category());
        fragment.setDate(formatDate(entity.depotDateTime()));
        fragment.setFilename(entity.filename());
        fragment.setFileUrl("/api/documents/" + entity.documentId() + "/file");
        fragment.setFragment(buildFragment(entity.contentText()));
        fragment.setScore(score);
        return fragment;
    }

    private boolean matchesFilters(BertEmbeddingDocument entity, SearchRequestDTO request) {
        return matchesTextFilter(entity.category(), request.getCategory())
                && matchesTextFilter(entity.author(), request.getAuthor())
                && matchesDateRange(entity.depotDateTime(), request.getDateFrom(), request.getDateTo());
    }

    private boolean matchesTextFilter(String value, String filter) {
        if (filter == null || filter.isBlank()) {
            return true;
        }
        if (value == null) {
            return false;
        }
        return value.equalsIgnoreCase(filter.trim());
    }

    private boolean matchesDateRange(LocalDateTime value, LocalDate from, LocalDate to) {
        if (from == null && to == null) {
            return true;
        }
        if (value == null) {
            return false;
        }
        LocalDate date = value.toLocalDate();
        return (from == null || !date.isBefore(from)) && (to == null || !date.isAfter(to));
    }

    private String buildFragment(String content) {
        if (content == null || content.isBlank()) {
            return "";
        }
        String normalized = content.replaceAll("\\s+", " ").trim();
        return normalized.length() <= 280 ? normalized : normalized.substring(0, 280) + "...";
    }

    private float lexicalScore(BertEmbeddingDocument entity, String query, Set<String> queryTokens) {
        if (queryTokens.isEmpty()) {
            return 0.0f;
        }

        String normalizedQuery = normalize(query);
        String title = normalize(entity.title());
        String author = normalize(entity.author());
        String category = normalize(entity.category());
        String content = normalize(entity.contentText());

        double titleCoverage = coverage(queryTokens, tokenize(title));
        double authorCoverage = coverage(queryTokens, tokenize(author));
        double categoryCoverage = coverage(queryTokens, tokenize(category));
        double contentCoverage = coverage(queryTokens, tokenize(content));
        double codeScore = codeScore(query, entity);

        double phraseBoost = 0.0d;
        if (!normalizedQuery.isBlank()) {
            if (title.contains(normalizedQuery)) {
                phraseBoost += 0.35d;
            }
            if (author.contains(normalizedQuery) || category.contains(normalizedQuery)) {
                phraseBoost += 0.20d;
            }
            if (content.contains(normalizedQuery)) {
                phraseBoost += 0.15d;
            }
        }

        double weighted = (titleCoverage * 0.45d)
                + (authorCoverage * 0.20d)
                + (categoryCoverage * 0.10d)
                + (contentCoverage * 0.25d)
                + phraseBoost
                + codeScore;
        return (float) Math.min(1.0d, weighted);
    }

    private double codeScore(String query, BertEmbeddingDocument entity) {
        String queryCode = canonicalCode(query);
        if (queryCode.isBlank()) {
            return 0.0d;
        }

        String titleCode = canonicalCode(entity.title());
        String filenameCode = canonicalCode(entity.filename());
        String contentCode = canonicalCode(entity.contentText());

        if (queryCode.equals(titleCode) || queryCode.equals(filenameCode)) {
            return 0.80d;
        }
        if (queryCode.equals(contentCode)) {
            return 0.50d;
        }

        String queryPrefix = leadingLetters(queryCode);
        String queryDigits = trailingDigits(queryCode);
        if (!queryPrefix.isBlank() && !queryDigits.isBlank()) {
            if (sameFamilyDifferentNumber(queryPrefix, queryDigits, titleCode)
                    || sameFamilyDifferentNumber(queryPrefix, queryDigits, filenameCode)) {
                return -0.20d;
            }
            if (sameFamilyDifferentNumber(queryPrefix, queryDigits, contentCode)) {
                return -0.10d;
            }
        }
        return 0.0d;
    }

    private boolean sameFamilyDifferentNumber(String queryPrefix, String queryDigits, String candidateCode) {
        if (candidateCode == null || candidateCode.isBlank()) {
            return false;
        }
        return queryPrefix.equals(leadingLetters(candidateCode))
                && !queryDigits.isBlank()
                && !queryDigits.equals(trailingDigits(candidateCode));
    }

    private String canonicalCode(String value) {
        if (value == null || value.isBlank()) {
            return "";
        }
        String compact = normalize(value).replaceAll("[^\\p{L}\\p{N}]+", "");
        String letters = leadingLetters(compact);
        String digits = trailingDigits(compact);
        if (letters.isBlank() || digits.isBlank()) {
            return "";
        }
        String normalizedDigits = digits.replaceFirst("^0+(?!$)", "");
        return letters + normalizedDigits;
    }

    private String leadingLetters(String value) {
        return value.replaceFirst("^(\\p{L}+).*$", "$1").matches("\\p{L}+")
                ? value.replaceFirst("^(\\p{L}+).*$", "$1")
                : "";
    }

    private String trailingDigits(String value) {
        return value.replaceFirst("^\\p{L}+([0-9]+).*$", "$1").matches("[0-9]+")
                ? value.replaceFirst("^\\p{L}+([0-9]+).*$", "$1")
                : "";
    }

    private double coverage(Set<String> queryTokens, Set<String> fieldTokens) {
        if (queryTokens.isEmpty() || fieldTokens.isEmpty()) {
            return 0.0d;
        }
        long matched = queryTokens.stream().filter(fieldTokens::contains).count();
        return (double) matched / queryTokens.size();
    }

    private Set<String> tokenize(String value) {
        return TOKEN_SPLIT.splitAsStream(normalize(value))
                .filter(token -> token.length() >= 2)
                .collect(Collectors.toSet());
    }

    private String normalize(String value) {
        return value == null ? "" : value.toLowerCase(Locale.ROOT).trim();
    }

    private String formatDate(LocalDateTime dateTime) {
        return dateTime == null ? null : dateTime.format(RESULT_DATE_FORMATTER);
    }

    private float cosineSimilarity(float[] left, float[] right) {
        if (left.length == 0 || right.length == 0 || left.length != right.length) {
            return 0.0f;
        }
        double dotProduct = 0.0d;
        double leftNorm = 0.0d;
        double rightNorm = 0.0d;
        for (int i = 0; i < left.length; i++) {
            dotProduct += left[i] * right[i];
            leftNorm += left[i] * left[i];
            rightNorm += right[i] * right[i];
        }
        if (leftNorm == 0.0d || rightNorm == 0.0d) {
            return 0.0f;
        }
        return (float) (dotProduct / (Math.sqrt(leftNorm) * Math.sqrt(rightNorm)));
    }
}
