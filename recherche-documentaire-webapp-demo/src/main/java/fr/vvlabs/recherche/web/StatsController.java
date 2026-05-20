package fr.vvlabs.recherche.web;

import fr.vvlabs.recherche.dto.AppStatsDTO;
import fr.vvlabs.recherche.service.stats.AppStatsService;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/stats")
@Tag(name = "Statistiques", description = "API de statistiques de l'application")
@RequiredArgsConstructor
public class StatsController {

    private final AppStatsService appStatsService;

    @GetMapping
    public AppStatsDTO getStats() {
        return appStatsService.getStats();
    }
}
