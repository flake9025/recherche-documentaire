package fr.vvlabs.recherche.web;

import fr.vvlabs.recherche.service.ai.AiGateway;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/api/ai")
@RequiredArgsConstructor
public class AiController {
    private final AiGateway gateway;

    @GetMapping("/models")
    public List<AiGateway.ModelView> models() {
        return gateway.models();
    }
}
