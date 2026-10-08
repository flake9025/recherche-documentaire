package fr.vvlabs.recherche.web;

import fr.vvlabs.recherche.service.search.SearchStoreInitializer;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/admin/index")
@RequiredArgsConstructor
public class IndexAdminController {
    private final SearchStoreInitializer initializer;

    @PostMapping("/rebuild")
    public RebuildResult rebuild() throws Exception {
        return new RebuildResult(initializer.rebuildAll());
    }

    public record RebuildResult(long durationMs) { }
}
