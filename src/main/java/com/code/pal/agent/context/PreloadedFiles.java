package com.code.pal.agent.context;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/** Prepared conversation data; no read receipt is recorded until the caller actually inserts this text. */
public record PreloadedFiles(String text, Map<String, String> versions, boolean partial) {
    public PreloadedFiles {
        versions = Collections.unmodifiableMap(new LinkedHashMap<>(versions));
    }
}
