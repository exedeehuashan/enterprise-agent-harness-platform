package com.enterprise.agentharness.code;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.List;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

/** Viewers list the repositories the code agent may work on; approvers register them under /api/v1/admin. */
@RestController
@RequestMapping("/api/v1")
public class CodeRepositoryController {
    private final CodeRepositories repositories;
    public CodeRepositoryController(CodeRepositories repositories) { this.repositories = repositories; }

    @GetMapping("/code-repositories") public List<CodeRepositories.Repository> list() { return repositories.list(); }
    @PutMapping("/admin/code-repositories/{key}") public CodeRepositories.Repository put(@PathVariable String key, @RequestBody JsonNode body) { return repositories.put(key, body); }
    @DeleteMapping("/admin/code-repositories/{key}") public ResponseEntity<Void> delete(@PathVariable String key) { repositories.delete(key); return ResponseEntity.noContent().build(); }
}
