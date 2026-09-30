package com.digitalasset.quickstart.controller;

import com.digitalasset.quickstart.config.TemplateSchemaDebugConfig;
import com.digitalasset.quickstart.service.TemplateSchemaService;
import com.digitalasset.quickstart.service.TemplateSchemaService.SchemaResponse;
import com.digitalasset.quickstart.service.TemplateSchemaService.ChoicesResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Profile;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/**
 * DEVNET-only debug endpoint to inspect template schemas.
 * Guarded by feature flag feature.enable-template-schema-debug and optional X-Debug-Token.
 */
@RestController
@RequestMapping("/api/devnet/templates")
@Profile("devnet")
public class TemplateSchemaController {

    private static final Logger LOG = LoggerFactory.getLogger(TemplateSchemaController.class);

    private final TemplateSchemaService schemaService;
    private final TemplateSchemaDebugConfig debugConfig;

    public TemplateSchemaController(TemplateSchemaService schemaService, TemplateSchemaDebugConfig debugConfig) {
        this.schemaService = schemaService;
        this.debugConfig = debugConfig;
    }

    @GetMapping("/schema")
    public ResponseEntity<?> schema(
            @RequestParam("packageId") String packageId,
            @RequestParam("module") String module,
            @RequestParam("entity") String entity,
            @RequestHeader(value = "X-Debug-Token", required = false) String debugToken
    ) {
        if (!schemaService.isEnabled()) {
            return ResponseEntity.status(403).body(Map.of("ok", false, "error", "disabled"));
        }
        if (!schemaService.isAuthorized(debugToken)) {
            return ResponseEntity.status(403).body(Map.of("ok", false, "error", "unauthorized"));
        }
        try {
            SchemaResponse resp = schemaService.getTemplateSchema(packageId, module, entity);
            return ResponseEntity.ok(Map.of(
                    "ok", true,
                    "schema", resp
            ));
        } catch (Exception e) {
            LOG.error("Schema fetch failed: {}", e.getMessage(), e);
            return ResponseEntity.internalServerError().body(Map.of(
                    "ok", false,
                    "error", e.getMessage()
            ));
        }
    }

    @GetMapping("/choices")
    public ResponseEntity<?> choices(
            @RequestParam("packageId") String packageId,
            @RequestParam("module") String module,
            @RequestParam("entity") String entity,
            @RequestHeader(value = "X-Debug-Token", required = false) String debugToken
    ) {
        if (!schemaService.isEnabled()) {
            return ResponseEntity.status(403).body(Map.of("ok", false, "error", "disabled"));
        }
        if (!schemaService.isAuthorized(debugToken)) {
            return ResponseEntity.status(403).body(Map.of("ok", false, "error", "unauthorized"));
        }
        try {
            ChoicesResponse resp = schemaService.getTemplateChoices(packageId, module, entity);
            return ResponseEntity.ok(Map.of(
                    "ok", true,
                    "choices", resp
            ));
        } catch (Exception e) {
            LOG.error("Choices fetch failed: {}", e.getMessage(), e);
            return ResponseEntity.internalServerError().body(Map.of(
                    "ok", false,
                    "error", e.getMessage()
            ));
        }
    }
}

