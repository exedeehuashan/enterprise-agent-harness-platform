package com.enterprise.agentharness.artifact;

import com.enterprise.agentharness.run.RunService;
import java.util.List;
import java.util.UUID;
import org.springframework.http.ContentDisposition;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1")
public class ArtifactController {
    private final ArtifactStore artifacts;
    private final RunService runs;
    public ArtifactController(ArtifactStore artifacts, RunService runs) { this.artifacts = artifacts; this.runs = runs; }

    @Transactional(readOnly = true)
    @GetMapping("/agent-runs/{id}/artifacts")
    public List<ArtifactStore.Artifact> list(@PathVariable UUID id) {
        runs.get(id);
        return artifacts.list(id);
    }

    @GetMapping("/artifacts/{id}")
    public ArtifactStore.Artifact get(@PathVariable UUID id) { return artifacts.get(id); }

    /** Always a download: tool output is untrusted, so it is never rendered by the browser. */
    @GetMapping("/artifacts/{id}/content")
    public ResponseEntity<byte[]> content(@PathVariable UUID id) {
        var content = artifacts.content(id);
        var a = content.artifact();
        return ResponseEntity.ok()
                .contentType(MediaType.parseMediaType(a.mediaType()))
                .header("Content-Disposition", ContentDisposition.attachment().filename(a.name()).build().toString())
                .header("Content-Security-Policy", "sandbox; default-src 'none'")
                .header("X-Content-Type-Options", "nosniff")
                .eTag(a.sha256())
                .body(content.bytes());
    }
}
