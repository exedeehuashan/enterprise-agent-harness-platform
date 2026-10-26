package com.enterprise.agentharness.ai;

import org.springframework.web.bind.annotation.*;

/** The configured AI provider, model and today's token use, for clients. Feature endpoints live in their modules. */
@RestController
@RequestMapping("/api/v1")
public class AiController {
    private final AiService ai;
    public AiController(AiService ai) { this.ai = ai; }

    @GetMapping("/ai/status") public AiService.Status status() { return ai.status(); }
}
