package fr.vvlabs.recherche.service.search.lucene;

import fr.vvlabs.recherche.config.IndexConstants;
import fr.vvlabs.recherche.config.LuceneConfig;
import fr.vvlabs.recherche.dto.SearchFragmentDTO;
import fr.vvlabs.recherche.dto.SearchRequestDTO;
import fr.vvlabs.recherche.dto.SearchResultDTO;
import fr.vvlabs.recherche.service.index.IndexType;
import fr.vvlabs.recherche.service.search.SearchService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.apache.lucene.document.Document;
import org.apache.lucene.index.DirectoryReader;
import org.apache.lucene.index.IndexReader;
import org.apache.lucene.index.StoredFields;
import org.apache.lucene.queryparser.classic.MultiFieldQueryParser;
import org.apache.lucene.queryparser.classic.QueryParser;
import org.apache.lucene.search.BooleanClause;
import org.apache.lucene.search.BooleanQuery;
import org.apache.lucene.search.IndexSearcher;
import org.apache.lucene.search.MatchAllDocsQuery;
import org.apache.lucene.search.Query;
import org.apache.lucene.search.ScoreDoc;
import org.apache.lucene.search.TopDocs;
import org.apache.lucene.search.uhighlight.UnifiedHighlighter;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.time.Duration;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;

@Service
@ConditionalOnProperty(name = "app.search.default", havingValue = IndexType.LUCENE)
@RequiredArgsConstructor
@Slf4j
public class LuceneSearchService implements SearchService {

    private final LuceneConfig luceneConfig;
    private static final DateTimeFormatter INDEX_DATE_FORMATTER = DateTimeFormatter.ofPattern("dd/MM/yyyy HH:mm:ss");

    @Override
    public String getType() {
        return IndexType.LUCENE;
    }

    @Override
    public SearchResultDTO search(SearchRequestDTO request) throws Exception {
        String queryText = request == null ? null : request.getQuery();
        String category = request == null ? null : request.getCategory();
        String author = request == null ? null : request.getAuthor();
        LocalDate dateFrom = request == null ? null : request.getDateFrom();
        LocalDate dateTo = request == null ? null : request.getDateTo();

        log.info("Searching '{}' in index", queryText);

        LocalTime startTime = LocalTime.now();
        String[] fields = {IndexConstants.INDEX_KEY_NAME, IndexConstants.INDEX_KEY_CONTENT};

        MultiFieldQueryParser parser = new MultiFieldQueryParser(fields, luceneConfig.getDocumentsAnalyzer());
        parser.setDefaultOperator(QueryParser.Operator.OR);

        Query baseQuery;
        if (StringUtils.isNotBlank(queryText)) {
            String escapedText = QueryParser.escape(queryText.trim());
            baseQuery = parser.parse(escapedText);
        } else {
            baseQuery = new MatchAllDocsQuery();
        }

        BooleanQuery.Builder booleanQuery = new BooleanQuery.Builder();
        booleanQuery.add(baseQuery, BooleanClause.Occur.MUST);
        addFilter(booleanQuery, IndexConstants.INDEX_KEY_CATEGORIE, category);
        addFilter(booleanQuery, IndexConstants.INDEX_KEY_AUTEUR, author);

        SearchResultDTO searchResultDTO = new SearchResultDTO();

        try (IndexReader reader = DirectoryReader.open(luceneConfig.getDocumentsIndex())) {
            IndexSearcher searcher = new IndexSearcher(reader);
            TopDocs results = searcher.search(booleanQuery.build(), 2000);

            UnifiedHighlighter highlighter = new UnifiedHighlighter(searcher, luceneConfig.getDocumentsAnalyzer());
            highlighter.setMaxLength(50_000);
            highlighter.setHighlightPhrasesStrictly(true);
            String[] fragments = new String[results.scoreDocs.length];
            if (!(baseQuery instanceof MatchAllDocsQuery)) {
                fragments = highlighter.highlight(IndexConstants.INDEX_KEY_CONTENT, baseQuery, results);
            }

            log.info("Millis ecoules pour la recherche : {}", Duration.between(startTime, LocalTime.now()).toMillis());

            StoredFields storedFields = searcher.storedFields();
            for (int i = 0; i < results.scoreDocs.length; i++) {
                ScoreDoc hit = results.scoreDocs[i];
                Document doc = storedFields.document(hit.doc);
                String fragment = fragments[i] != null ? fragments[i] : doc.get(IndexConstants.INDEX_KEY_CONTENT);

                LocalDate documentDate = null;
                String indexedDate = doc.get(IndexConstants.INDEX_KEY_DATE_DEPOT);
                if (StringUtils.isNotBlank(indexedDate)) {
                    try {
                        documentDate = LocalDateTime.parse(indexedDate.trim(), INDEX_DATE_FORMATTER).toLocalDate();
                    } catch (DateTimeParseException ignored) {
                        documentDate = null;
                    }
                }
                if ((dateFrom != null || dateTo != null)
                        && (documentDate == null
                        || (dateFrom != null && documentDate.isBefore(dateFrom))
                        || (dateTo != null && documentDate.isAfter(dateTo)))) {
                    continue;
                }

                log.debug("Document ID: {}", doc.get(IndexConstants.INDEX_KEY_ID));
                log.debug("Document NAME: {}", doc.get(IndexConstants.INDEX_KEY_NAME));
                log.debug("Document Highlight: {}", fragment);

                SearchFragmentDTO fragmentDTO = new SearchFragmentDTO();
                fragmentDTO.setId(doc.get(IndexConstants.INDEX_KEY_ID));
                fragmentDTO.setName(doc.get(IndexConstants.INDEX_KEY_NAME));
                fragmentDTO.setAuthor(doc.get(IndexConstants.INDEX_KEY_AUTEUR));
                fragmentDTO.setCategory(doc.get(IndexConstants.INDEX_KEY_CATEGORIE));
                fragmentDTO.setDate(doc.get(IndexConstants.INDEX_KEY_DATE_DEPOT));
                fragmentDTO.setFilename(doc.get(IndexConstants.INDEX_KEY_FICHIER));
                fragmentDTO.setFileUrl("/api/documents/" + doc.get(IndexConstants.INDEX_KEY_ID) + "/file");
                fragmentDTO.setFragment(fragment);
                fragmentDTO.setScore(hit.score);
                searchResultDTO.getFragments().add(fragmentDTO);
            }
        } catch (IOException e) {
            log.error(e.getMessage(), e);
        }

        searchResultDTO.setNbResults(searchResultDTO.getFragments().size());
        return searchResultDTO;
    }

    @Override
    public boolean isSearchStoreEmpty() throws Exception {
        return luceneConfig.isIndexEmpty();
    }

    private void addFilter(BooleanQuery.Builder builder, String field, String value) throws Exception {
        if (StringUtils.isBlank(value)) {
            return;
        }

        QueryParser parser = new QueryParser(field, luceneConfig.getDocumentsAnalyzer());
        parser.setDefaultOperator(QueryParser.Operator.AND);
        Query filterQuery = parser.parse(QueryParser.escape(value.trim()));
        builder.add(filterQuery, BooleanClause.Occur.MUST);
    }

}
