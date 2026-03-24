package fr.vvlabs.recherche.service.search.lucene;

import fr.vvlabs.recherche.config.IndexConstants;
import fr.vvlabs.recherche.config.LuceneConfig;
import fr.vvlabs.recherche.dto.SearchFragmentDTO;
import fr.vvlabs.recherche.dto.SearchRequestDTO;
import fr.vvlabs.recherche.dto.SearchResultDTO;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.lucene.document.Document;
import org.apache.lucene.index.DirectoryReader;
import org.apache.lucene.index.IndexReader;
import org.apache.lucene.index.StoredFields;
import org.apache.lucene.queryparser.classic.MultiFieldQueryParser;
import org.apache.lucene.queryparser.classic.QueryParser;
import org.apache.lucene.search.*;
import org.apache.lucene.search.uhighlight.UnifiedHighlighter;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.time.Duration;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;

@Service
@RequiredArgsConstructor
@Slf4j
public class LuceneSearchService {

    private final LuceneConfig luceneConfig;
    private static final DateTimeFormatter INDEX_DATE_FORMATTER = DateTimeFormatter.ofPattern("dd/MM/yyyy HH:mm:ss");

    public SearchResultDTO search(SearchRequestDTO request) throws Exception {
        String queryText = request == null ? null : request.getQuery();
        String category = request == null ? null : request.getCategory();
        String author = request == null ? null : request.getAuthor();
        LocalDate dateFrom = request == null ? null : request.getDateFrom();
        LocalDate dateTo = request == null ? null : request.getDateTo();

        log.info("Searching '{}' in index", queryText);

        LocalTime t1 = LocalTime.now();

        String[] fields = { IndexConstants.INDEX_KEY_NAME, IndexConstants.INDEX_KEY_CONTENT };

        MultiFieldQueryParser parser = new MultiFieldQueryParser(fields, luceneConfig.getDocumentsAnalyzer());
        parser.setDefaultOperator(QueryParser.Operator.OR);

        Query baseQuery;
        if (hasText(queryText)) {
            String escapedText = QueryParser.escape(queryText.trim());
            baseQuery = parser.parse(escapedText);
        } else {
            baseQuery = new MatchAllDocsQuery();
        }

        BooleanQuery.Builder booleanQuery = new BooleanQuery.Builder();
        booleanQuery.add(baseQuery, BooleanClause.Occur.MUST);
        addFilter(booleanQuery, IndexConstants.INDEX_KEY_CATEGORIE, category);
        addFilter(booleanQuery, IndexConstants.INDEX_KEY_AUTEUR, author);

        Query query = booleanQuery.build();

        SearchResultDTO searchResultDTO = new SearchResultDTO();

        try (IndexReader reader = DirectoryReader.open(luceneConfig.getDocumentsIndex())) {
            IndexSearcher searcher = new IndexSearcher(reader);
            TopDocs results = searcher.search(query, 2000);

            UnifiedHighlighter highlighter = new UnifiedHighlighter(searcher, luceneConfig.getDocumentsAnalyzer());
            highlighter.setMaxLength(50_000);
            highlighter.setHighlightPhrasesStrictly(true);
            String[] fragments = new String[results.scoreDocs.length];
            if (!(baseQuery instanceof MatchAllDocsQuery)) {
                fragments = highlighter.highlight(IndexConstants.INDEX_KEY_CONTENT, baseQuery, results);
            }

            LocalTime t2 = LocalTime.now();
            Duration d = Duration.between(t1, t2);
            log.info("Millis Ã©coulÃ©s pour la recherche : {}" , d.toMillis());

            StoredFields storedFields = searcher.storedFields();

            for (int i = 0; i < results.scoreDocs.length; i++) {
                ScoreDoc hit = results.scoreDocs[i];
                Document doc = storedFields.document(hit.doc);
                String fragment = fragments[i] != null ? fragments[i] : doc.get(IndexConstants.INDEX_KEY_CONTENT);
                if (!matchesDateRange(doc.get(IndexConstants.INDEX_KEY_DATE_DEPOT), dateFrom, dateTo)) {
                    continue;
                }

                log.debug("Document ID: {}", doc.get(IndexConstants.INDEX_KEY_ID));
                log.debug("Document NAME: {}", doc.get(IndexConstants.INDEX_KEY_NAME));
                log.debug("Document Highlight: {}", fragment);

                searchResultDTO.getFragments().add(buildSearchFragmentDTO(doc, fragment, hit));
            }
        } catch (IOException e) {
            log.error(e.getMessage(), e);
        }

        searchResultDTO.setNbResults(searchResultDTO.getFragments().size());
        return searchResultDTO;
    }

    private static SearchFragmentDTO buildSearchFragmentDTO(Document doc, String fragment, ScoreDoc hit) {
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
        return fragmentDTO;
    }

    private void addFilter(BooleanQuery.Builder builder, String field, String value) throws Exception {
        if (!hasText(value)) {
            return;
        }

        QueryParser parser = new QueryParser(field, luceneConfig.getDocumentsAnalyzer());
        parser.setDefaultOperator(QueryParser.Operator.AND);
        Query filterQuery = parser.parse(QueryParser.escape(value.trim()));
        builder.add(filterQuery, BooleanClause.Occur.MUST);
    }

    private boolean hasText(String value) {
        return value != null && !value.trim().isEmpty();
    }

    private boolean matchesDateRange(String indexedDate, LocalDate dateFrom, LocalDate dateTo) {
        if (dateFrom == null && dateTo == null) {
            return true;
        }
        LocalDate documentDate = parseIndexedDate(indexedDate);
        if (documentDate == null) {
            return false;
        }
        if (documentDate == null) {
            return false;
        }
        return (dateFrom == null || !documentDate.isBefore(dateFrom)) &&
                (dateTo == null || !documentDate.isAfter(dateTo));
    }

    private LocalDate parseIndexedDate(String value) {
        if (!hasText(value)) {
            return null;
        }
        try {
            LocalDateTime dateTime = LocalDateTime.parse(value.trim(), INDEX_DATE_FORMATTER);
            return dateTime.toLocalDate();
        } catch (DateTimeParseException e) {
            return null;
        }
    }
}

