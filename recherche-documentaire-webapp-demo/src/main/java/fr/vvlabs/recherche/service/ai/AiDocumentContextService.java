package fr.vvlabs.recherche.service.ai;

import fr.vvlabs.recherche.dto.DocumentDTO;
import fr.vvlabs.recherche.dto.SearchFragmentDTO;
import fr.vvlabs.recherche.service.document.DocumentAccessService;
import fr.vvlabs.recherche.service.document.DocumentService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.lucene.analysis.fr.FrenchAnalyzer;
import org.apache.lucene.analysis.tokenattributes.CharTermAttribute;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
@Slf4j
public class AiDocumentContextService {
    private static final String GAP = "\n[Passage non transmis]\n";
    private final DocumentService documents;
    private final DocumentAccessService access;

    public List<DocumentText> readSources(List<SearchFragmentDTO> fragments, int limit) {
        Set<Long> ids = fragments.stream().map(fragment -> Long.valueOf(fragment.getId()))
                .distinct().limit(limit).collect(Collectors.toCollection(LinkedHashSet::new));
        ids.forEach(access::requireRead);
        var metadata = documents.findByIds(ids).stream()
                .collect(Collectors.toMap(DocumentDTO::getId, Function.identity()));
        if (!metadata.keySet().containsAll(ids)) {
            throw new ResponseStatusException(HttpStatus.BAD_GATEWAY, "Metadonnees sources indisponibles");
        }
        List<DocumentText> sources = new ArrayList<>();
        for (Long id : ids) {
            access.requireRead(id);
            String text;
            try {
                text = documents.getFileText(metadata.get(id));
            } catch (IOException exception) {
                log.warn("AI source extraction failed error={}", exception.getClass().getSimpleName());
                throw new ResponseStatusException(HttpStatus.BAD_GATEWAY,
                        "Lecture du contenu source impossible ; synthese interrompue", exception);
            }
            if (text == null || text.isBlank()) {
                throw new ResponseStatusException(HttpStatus.BAD_GATEWAY,
                        "Texte source inexploitable ; verifier l'extraction OCR");
            }
            sources.add(new DocumentText(id, metadata.get(id).getTitre(), text.replace("\r\n", "\n").strip()));
        }
        return List.copyOf(sources);
    }

    public Selection select(String text, String query, int budget) throws IOException {
        if (budget <= 0) {
            return new Selection("", true);
        }
        if (text.length() <= budget) {
            return new Selection(text, false);
        }
        int windowSize = Math.min(budget, Math.min(1000, Math.max(120, budget / 3)));
        int overlap = windowSize / 5;
        List<Window> windows = new ArrayList<>();
        try (FrenchAnalyzer analyzer = new FrenchAnalyzer()) {
            Set<String> terms = tokens(analyzer, query == null ? "" : query);
            for (int start = 0; start < text.length();) {
                int end = Math.min(start + windowSize, text.length());
                if (end < text.length()) {
                    int boundary = end;
                    while (boundary > start + windowSize / 2 && !Character.isWhitespace(text.charAt(boundary))) {
                        boundary--;
                    }
                    if (boundary > start + windowSize / 2) {
                        end = boundary;
                    } else if (Character.isLowSurrogate(text.charAt(end))) {
                        end--;
                    }
                }
                Set<String> passageTerms = tokens(analyzer, text.substring(start, end));
                long matches = terms.stream().filter(passageTerms::contains).count();
                double score = matches + (start == 0 || end == text.length() ? 0.1 : 0);
                windows.add(new Window(start, end, score));
                if (end == text.length()) {
                    break;
                }
                int next = Math.max(start + 1, end - overlap);
                while (next < end && !Character.isWhitespace(text.charAt(next - 1))) {
                    next++;
                }
                start = next;
            }
        }
        windows.sort(Comparator.comparingDouble(Window::score).reversed().thenComparingInt(Window::start));
        List<Window> selected = new ArrayList<>();
        for (Window window : windows) {
            List<Window> candidate = new ArrayList<>(selected);
            candidate.add(window);
            candidate = merge(candidate);
            if (render(text, candidate).length() <= budget) {
                selected = candidate;
            }
        }
        return new Selection(render(text, selected), true);
    }

    private Set<String> tokens(FrenchAnalyzer analyzer, String text) throws IOException {
        Set<String> terms = new HashSet<>();
        try (var stream = analyzer.tokenStream("context", text)) {
            var term = stream.addAttribute(CharTermAttribute.class);
            stream.reset();
            while (stream.incrementToken()) {
                terms.add(term.toString());
            }
            stream.end();
        }
        return terms;
    }

    private List<Window> merge(List<Window> windows) {
        List<Window> merged = new ArrayList<>();
        windows.sort(Comparator.comparingInt(Window::start));
        for (Window window : windows) {
            if (!merged.isEmpty() && window.start() <= merged.getLast().end()) {
                Window previous = merged.removeLast();
                merged.add(new Window(previous.start(), Math.max(previous.end(), window.end()), 0));
            } else {
                merged.add(window);
            }
        }
        return merged;
    }

    private String render(String text, List<Window> windows) {
        return windows.stream().map(window -> text.substring(window.start(), window.end())).collect(Collectors.joining(GAP));
    }

    public record DocumentText(Long id, String title, String text) { }
    public record Selection(String text, boolean partial) { }
    private record Window(int start, int end, double score) { }
}
