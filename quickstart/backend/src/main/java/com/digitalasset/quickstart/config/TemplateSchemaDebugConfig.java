package com.digitalasset.quickstart.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Configuration;

@Configuration
public class TemplateSchemaDebugConfig {

    @Value("${feature.enable-template-schema-debug:false}")
    private boolean enableTemplateSchemaDebug;

    @Value("${feature.template-schema-debug-token:}")
    private String debugToken;

    public boolean isEnabled() {
        return enableTemplateSchemaDebug;
    }

    public String getDebugToken() {
        return debugToken;
    }
}

