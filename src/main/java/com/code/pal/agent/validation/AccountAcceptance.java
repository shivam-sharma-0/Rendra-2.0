package com.code.pal.agent.validation;

import lombok.extern.slf4j.Slf4j;
import tools.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.*;

/** Platform MERN accounts have a coordinator-owned auth API; no external/custom auth is inferred. */
@Slf4j
public final class AccountAcceptance {
    private static final Set<String> DATA = Set.of("CREATE", "READ", "UPDATE", "DELETE", "NOT_FOUND", "PERSISTENCE");
    private AccountAcceptance() {}

    public static String storageKey(UUID projectId) { return "rendra-session:" + projectId; }

    @SuppressWarnings("unchecked")
    public static boolean normalize(Map<String, Object> contract, ObjectMapper mapper) {
        if (!"MERN".equals(contract.get("stack")) || !"ACCOUNTS".equals(contract.get("auth"))
                || !(contract.get("acceptance") instanceof Map<?, ?> raw) || !Boolean.TRUE.equals(raw.get("authRequired"))
                || !(raw.get("api") instanceof List<?> original) || original.isEmpty()) return false;
        var api = new ArrayList<Map<String, Object>>();
        original.forEach(value -> api.add(new LinkedHashMap<>((Map<String, Object>) value)));
        if (!"HEALTH".equals(api.getFirst().get("kind"))) return false;
        List<Map<String, Object>> standard;
        try (var in = AccountAcceptance.class.getResourceAsStream("/contract/auth-acceptance.json")) {
            if (in == null) throw new IllegalStateException("Missing account acceptance resource");
            standard = mapper.readValue(new String(in.readAllBytes(), StandardCharsets.UTF_8), List.class);
        } catch (IOException failure) { throw new IllegalStateException("Cannot read account acceptance resource", failure); }
        var captures = new HashSet<Object>(Set.of("fixtureId", "fixtureEmail", "fixturePassword", "invalidToken"));
        api.forEach(step -> { if (step.get("captureVariable") != null) captures.add(step.get("captureVariable")); });
        boolean changed = false;
        int setup = 1;
        for (int index : List.of(0, 1, 2)) {
            var step = standard.get(index);
            var existing = api.stream().filter(old -> step.get("kind").equals(old.get("kind"))
                    && step.get("method").equals(old.get("method")) && step.get("path").equals(old.get("path"))).findFirst();
            if (existing.isPresent()) setup = Math.max(setup, api.indexOf(existing.get()) + 1);
            else {
                if (step.get("captureVariable") != null && captures.contains(step.get("captureVariable")))
                    throw new IllegalArgumentException("Platform auth token capture conflicts with an existing variable");
                requireUniqueId(api, step);
                api.add(setup++, new LinkedHashMap<>(step));
                changed = true;
                log.info("acceptance_auth_setup_inserted id={} kind={}", step.get("id"), step.get("kind"));
            }
        }
        String token = api.stream().filter(step -> "AUTH_LOGIN".equals(step.get("kind")))
                .map(step -> step.get("captureVariable")).filter(String.class::isInstance).map(String.class::cast)
                .findFirst().orElse(null);
        if (token == null || !token.matches("[A-Za-z][A-Za-z0-9_]{0,30}")) return false;
        for (var step : api) {
            if (DATA.contains(step.get("kind")) && (step.get("tokenVariable") == null || !captures.contains(step.get("tokenVariable")))) {
                if (!token.equals(step.get("tokenVariable"))) {
                    if (!(step.get("id") instanceof String id) || !id.matches("[A-Za-z0-9_-]{1,64}"))
                        throw new IllegalArgumentException("Invalid acceptance ID for auth binding");
                    step.put("tokenVariable", token);
                    changed = true;
                    log.info("acceptance_auth_token_bound id={} capture={}", step.get("id"), token);
                }
            }
        }
        // Denials run immediately before the first protected read, after any record identity capture.
        int read = -1;
        for (int index = 0; index < api.size(); index++)
            if (DATA.contains(api.get(index).get("kind")) && "GET".equals(api.get(index).get("method"))) { read = index; break; }
        if (read >= 0) {
            String target = (String) api.get(read).get("path");
            for (int index : List.of(3, 4)) {
                var step = new LinkedHashMap<>(standard.get(index));
                boolean exists = api.stream().anyMatch(old -> step.get("kind").equals(old.get("kind"))
                        && "GET".equals(old.get("method")));
                if (!exists) {
                    requireUniqueId(api, step);
                    step.put("path", target);
                    api.add(read++, step);
                    changed = true;
                    log.info("acceptance_auth_setup_inserted id={} kind={}", step.get("id"), step.get("kind"));
                }
            }
        }
        if (changed) ((Map<String, Object>) raw).put("api", api);
        return changed;
    }

    private static void requireUniqueId(List<Map<String, Object>> api, Map<String, Object> step) {
        if (api.stream().anyMatch(old -> step.get("id").equals(old.get("id"))))
            throw new IllegalArgumentException("Platform auth acceptance ID conflicts with an existing check");
    }

    public static Map<String, String> browserSession(BehaviorCriteria criteria, UUID projectId) {
        if (criteria == null || !criteria.authRequired()) return Map.of();
        // Explicit UI sign-in/signup tests retain their declared selectors and their own setup.
        if (criteria.browser().stream().anyMatch(action -> action.kind() == BehaviorCriteria.BrowserKind.FILL
                && action.selector() != null && action.selector().matches("(?i).*(?:type|name)\\s*=\\s*['\"]?password.*"))) return Map.of();
        String signup = criteria.api().stream().filter(step -> step.kind() == BehaviorCriteria.ApiKind.AUTH_SIGNUP)
                .map(BehaviorCriteria.ApiStep::path).findFirst().orElse(null);
        String login = criteria.api().stream().filter(step -> step.kind() == BehaviorCriteria.ApiKind.AUTH_LOGIN)
                .map(BehaviorCriteria.ApiStep::path).findFirst().orElse(null);
        if (!"/api/auth/signup".equals(signup) || !"/api/auth/login".equals(login)) return Map.of();
        return Map.of("signup", signup, "login", login, "storageKey", storageKey(projectId));
    }
}
