package com.code.pal.agent.config;

import tools.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.Map;

/** Exact model limits are deployment data; unknown routes remain unsupported. */
final class AccountingBounds {
    private AccountingBounds() {}

    static Map<String, AgentProperties.AccountingBound> load() {
        try (var input = AccountingBounds.class.getResourceAsStream("/policies/accounting-bounds.json")) {
            if (input == null) throw new IllegalStateException("Accounting bounds policy is missing");
            var mapper = new ObjectMapper();
            var type = mapper.getTypeFactory().constructMapType(LinkedHashMap.class,
                    String.class, AgentProperties.AccountingBound.class);
            return mapper.readValue(input, type);
        } catch (IOException invalid) {
            throw new IllegalStateException("Accounting bounds policy cannot be read", invalid);
        }
    }
}
