package fr.vvlabs.recherche.service.search.lucene;

import fr.vvlabs.recherche.config.IndexConstants;
import fr.vvlabs.recherche.config.LuceneConfig;
import fr.vvlabs.recherche.dto.SearchFragmentDTO;
import fr.vvlabs.recherche.dto.SearchRequestDTO;
import fr.vvlabs.recherche.dto.SearchResultDTO;
import fr.vvlabs.recherche.service.index.IndexType;
import fr.vvlabs.recherche.service.index.embeddings.BertEmbeddingsService;
import fr.vvlabs.recherche.service.search.SearchService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.lucene.document.Document;
import org.apache.lucene.index.DirectoryReader;
import org.apache.lucene.index.IndexReader;
import org.apache.lucene.index.StoredFields;
import org.apache.lucene.queryparser.classic.QueryParser;
import org.apache.lucene.search.BooleanClause;
import org.apache.lucene.search.BooleanQuery;
import org.apache.lucene.search.IndexSearcher;
import org.apache.lucene.search.KnnFloatVectorQuery;
import org.apache.lucene.search.MatchAllDocsQuery;
import org.apache.lucene.search.Query;
import org.apache.lucene.search.ScoreDoc;
import org.apache.lucene.search.TopDocs;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;

import static fr.vvlabs.recherche.service.index.lucene.LuceneVectorIndexService.VECTOR_FIELD;

@Service
@RequiredArgsConstructor
@Slf4j
public class LuceneVectorSearchService implements SearchService {

    private static final DateTimeFormatter INDEX_DATE_FORMATTER = DateTimeFormatter.ofPattern("dd/MM/yyyy HH:mm:ss");

    private final LuceneConfig luceneConfig;
    private final BertEmbeddingsService bertEmbeddingsService;

    @Value("${app.search.vector.max-results:25}")
    private int maxResults;

    @Value("${app.search.vector.candidate-multiplier:4}")
    private int candidateMultiplier;

    @Override
    public String getType() {
        return IndexType.LUCENE_VECTOR;
    }

    @Override
    public SearchResultDTO search(SearchRequestDTO request) throws Exception {
        SearchRequestDTO effectiveRequest = request == null ? new SearchRequestDTO() : request;
        String queryText = effectiveRequest.getQuery() == null ? "" : effectiveRequest.getQuery().trim();
        float[] queryVector = bertEmbeddingsService.generateEmbedding(queryText);
        Query filterQuery = buildFilterQuery(effectiveRequest.getCategory(), effectiveRequest.getAuthor());

        SearchResultDTO result = new SearchResultDTO();
        try (IndexReader reader = DirectoryReader.open(luceneConfig.getDocumentsIndex())) {
            IndexSearcher searcher = new IndexSearcher(reader);
            int k = Math.max(maxResults, 1);
            int candidateCount = Math.max(k, k * Math.max(candidateMultiplier, 1));
            TopDocs topDocs = searcher.search(
                    new KnnFloatVectorQuery(VECTOR_FIELD, queryVector, candidateCount, filterQuery),
                    candidateCount
            );

            StoredFields storedFields = searcher.storedFields();
            for (ScoreDoc hit : topDocs.scoreDocs) {
                Document doc = storedFields.document(hit.doc);
                if (!matchesDateRange(doc.get(IndexConstants.INDEX_KEY_DATE_DEPOT), effectiveRequest.getDateFrom(), effectiveRequest.getDateTo())) {
                    continue;
                }

                SearchFragmentDTO fragment = new SearchFragmentDTO();
                fragment.setId(doc.get(IndexConstants.INDEX_KEY_ID));
                fragment.setName(doc.get(IndexConstants.INDEX_KEY_NAME));
                fragment.setAuthor(doc.get(IndexConstants.INDEX_KEY_AUTEUR));
                fragment.setCategory(doc.get(IndexConstants.INDEX_KEY_CATEGORIE));
                fragment.setDate(doc.get(IndexConstants.INDEX_KEY_DATE_DEPOT));
                fragment.setFilename(doc.get(IndexConstants.INDEX_KEY_FICHIER));
                fragment.setFileUrl("/api/documents/" + doc.get(IndexConstants.INDEX_KEY_ID) + "/file");
                fragment.setFragment(buildFragment(doc.get(IndexConstants.INDEX_KEY_CONTENT)));
                fragment.setScore(hit.score);
                result.getFragments().add(fragment);

                if (result.getFragments().size() >= maxResults) {
                    break;
                }
            }
        } catch (IOException e) {
            log.error("Lucene vector search failed: {}", e.getMessage(), e);
        }

        result.setNbResults(result.getFragments().size());
        return result;
    }

    @Override
    public boolean isSearchStoreEmpty() throws Exception {
        return luceneConfig.isIndexEmpty();
    }

    private Query buildFilterQuery(String category, String author) throws Exception {
        BooleanQuery.Builder builder = new BooleanQuery.Builder();
        boolean hasFilter = false;

        if (hasText(category)) {
            builder.add(parseFilter(IndexConstants.INDEX_KEY_CATEGORIE, category), BooleanClause.Occur.MUST);
            hasFilter = true;
        }
        if (hasText(author)) {
            builder.add(parseFilter(IndexConstants.INDEX_KEY_AUTEUR, author), BooleanClause.Occur.MUST);
            hasFilter = true;
        }

        return hasFilter ? builder.build() : new MatchAllDocsQuery();
    }

    private Query parseFilter(String field, String value) throws Exception {
        QueryParser parser = new QueryParser(field, luceneConfig.getDocumentsAnalyzer());
        parser.setDefaultOperator(QueryParser.Operator.AND);
        return parser.parse(QueryParser.escape(value.trim()));
    }

    private boolean matchesDateRange(String indexedDate, LocalDate dateFrom, LocalDate dateTo) {
        if (dateFrom == null && dateTo == null) {
            return true;
        }
        if (!hasText(indexedDate)) {
            return false;
        }

        try {
            LocalDate documentDate = LocalDateTime.parse(indexedDate.trim(), INDEX_DATE_FORMATTER).toLocalDate();
            if (dateFrom != null && documentDate.isBefore(dateFrom)) {
                return false;
            }
            return dateTo == null || !documentDate.isAfter(dateTo);
        } catch (DateTimeParseException ignored) {
            return false;
        }
    }

    private String buildFragment(String content) {
        if (!hasText(content)) {
            return "";
        }
        String normalized = content.replaceAll("\\s+", " ").trim();
        return normalized.length() <= 280 ? normalized : normalized.substring(0, 280) + "...";
    }

    private boolean hasText(String value) {
        return value != null && !value.trim().isEmpty();
    }
}
