package com.digitalasset.quickstart.controller;

import com.digitalasset.quickstart.ledger.LedgerApi;
import org.springframework.context.annotation.Profile;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

@Profile("local")
@RestController
@RequestMapping("/api/debug")
public class PackageController {
    private final LedgerApi ledgerApi;
    public PackageController(LedgerApi ledgerApi) {
        this.ledgerApi = ledgerApi;
    }

    @PostMapping(value = "/upload-dar", consumes = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<?> uploadDar(@RequestBody Map<String, String> body) {
        try {
            String which = body.getOrDefault("which", "prod").toLowerCase();
            // Prefer latest GV or PROD dar from daml-prod dist
            Path dist = Path.of("/root/cn-quickstart/quickstart/clearportx/daml-prod/.daml/dist");
            java.util.Optional<Path> chosen;
            if ("gv".equals(which)) {
                chosen = Files.list(dist)
                        .filter(p -> p.getFileName().toString().startsWith("clearportx-amm-production-gv-") && p.toString().endsWith(".dar"))
                        .max((a, b) -> Long.compare(a.toFile().lastModified(), b.toFile().lastModified()));
            } else {
                chosen = Files.list(dist)
                        .filter(p -> p.getFileName().toString().startsWith("clearportx-amm-production-") && p.toString().endsWith(".dar") && !p.getFileName().toString().contains("-gv-"))
                        .max((a, b) -> Long.compare(a.toFile().lastModified(), b.toFile().lastModified()));
            }
            if (chosen.isEmpty()) {
                return ResponseEntity.status(404).body(Map.of("success", false, "error", "DAR not found in dist: " + which));
            }
            byte[] bytes = Files.readAllBytes(chosen.get());
            ledgerApi.uploadDar(bytes).join();
            return ResponseEntity.ok(Map.of("success", true, "uploaded", chosen.get().getFileName().toString()));
        } catch (Exception e) {
            return ResponseEntity.status(500).body(Map.of("success", false, "error", e.getMessage()));
        }
    }
}


