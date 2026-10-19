package com.code.pal.project.capability;

import tools.jackson.databind.JsonNode;

/** Untrusted model recommendation. Provider availability and user approval remain server-owned. */
public record CapabilityProposal(Persistence persistence, boolean accounts) {
    public enum Persistence { NONE, BROWSER, SERVER }

    public static CapabilityProposal from(JsonNode node) {
        if (node == null || !node.isObject() || !node.path("accounts").isBoolean()) {
            return null;
        }
        try {
            return new CapabilityProposal(Persistence.valueOf(node.path("persistence").asString("")),
                    node.path("accounts").asBoolean());
        } catch (IllegalArgumentException invalid) {
            return null;
        }
    }
}
