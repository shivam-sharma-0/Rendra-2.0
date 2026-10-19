package com.code.pal.agent.contract;

import com.code.pal.project.brief.ApprovedProjectMemory;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.util.LinkedHashMap;
import java.util.Set;
import java.util.stream.Collectors;

/** Carries the full immutable product scope into planning and prevents new contracts dropping approved pages. */
final class ApprovedProductScope {
    private ApprovedProductScope() {}

    static String planningRequirements(ApprovedProjectMemory.Approved approved, ObjectMapper mapper) {
        if (approved.specJson() == null) return approved.requirementsJson();
        var draft = mapper.readTree(approved.specJson());
        var scope = draft.has("requestedScope") ? draft.path("requestedScope") : draft;
        var data = new LinkedHashMap<String, Object>();
        data.put("approvedRequirements", approved.requirementsJson());
        data.put("requestedScope", scope);
        data.put("answersChanged", draft.path("answersChanged").asBoolean(false));
        if (draft.has("answerOverrides")) data.put("answerOverrides", draft.path("answerOverrides"));
        return mapper.writeValueAsString(data);
    }

    static void requirePages(ContractPlanner.Input input, BuildContract contract, ObjectMapper mapper) {
        if (input.requiredRootPath() == null || contract.stack() == BuildContract.Stack.STATIC) return;
        var approved = objectOrNull(input.approvedRequirements(), mapper);
        if (approved == null || approved.path("answersChanged").asBoolean(false)) return;
        var pages = approved.path("requestedScope").path("pages");
        if (!pages.isArray()) return;
        if (pages.size() > 20) throw new IllegalArgumentException("Approved page scope exceeds contract capacity");
        Set<String> planned = contract.routes().stream().map(BuildContract.Route::path).collect(Collectors.toSet());
        if (contract.ownership().containsKey(input.requiredRootPath())) planned.add("/");
        for (var page : pages) {
            String path = page.path("path").asString("");
            if (!path.matches("/[A-Za-z0-9_/:.-]{0,120}") || path.contains("..") || path.contains("//")) {
                throw new IllegalArgumentException("Approved page needs an explicit supported route mapping");
            }
            if (!planned.contains(path)) throw new IllegalArgumentException("Contract omits approved page route: " + path);
        }
    }

    private static JsonNode objectOrNull(String value, ObjectMapper mapper) {
        try {
            var node = mapper.readTree(value);
            return node != null && node.isObject() ? node : null;
        } catch (RuntimeException legacy) {
            return null;
        }
    }
}
