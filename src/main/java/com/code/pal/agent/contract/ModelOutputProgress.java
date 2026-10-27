package com.code.pal.agent.contract;

import com.code.pal.agent.config.AgentProperties;
import com.code.pal.agent.llm.*;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.time.Duration;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.regex.Pattern;

/** Per-run quality guard at the physical checked-call seam; never refunds a completed transmission. */
public final class ModelOutputProgress {
    public record Repeated(AgentProperties.ModelSelection route, int wastedCalls) {}
    private static final Pattern TOKENS = Pattern.compile("\\s+|\"(?:\\\\.|[^\"\\\\])*\"|'(?:\\\\.|[^'\\\\])*'|`(?:\\\\.|[^`\\\\])*`|[\\p{L}\\p{N}_$]+|.", Pattern.DOTALL);
    private static final Set<String> VOLATILE = Set.of("snapshot", "interfaces", "diagnosticsRevision", "expectedVersion", "version", "content", "line", "column");
    private static final class State {
        String previous;
        AgentProperties.ModelSelection rejected, alternate;
    }
    private final AgentProperties properties;
    private final ObjectMapper mapper;
    private final Consumer<Repeated> repeated;
    private final Map<String, State> operations = new ConcurrentHashMap<>();
    private final AtomicInteger wasted = new AtomicInteger();

    public ModelOutputProgress(AgentProperties properties, ObjectMapper mapper, Consumer<Repeated> repeated) {
        this.properties = properties; this.mapper = mapper; this.repeated = repeated;
    }

    public int wastedCalls() { return wasted.get(); }

    public LlmClient.LlmResult call(LlmClient.LlmRequest request, Function<LlmClient.LlmRequest, LlmClient.LlmResult> physical) {
        var purpose = LlmCallContext.current().purpose();
        boolean planning = purpose == CallPurpose.PLANNER || purpose == CallPurpose.SCHEMA_CORRECTION;
        // Planning progress is measured after normalization/validation, by rule + location, in the planner.
        if (planning) return physical.apply(request);
        var data = input(request);
        boolean patch = data != null && data.has("diagnosticsRevision") && data.has("files");
        if (!planning && purpose != CallPurpose.UNIT && purpose != CallPurpose.UNIT_CORRECTION && !patch)
            return physical.apply(request);
        String key = (planning ? "plan:" : "code:") + hash(data == null ? "" : mapper.writeValueAsString(canonical(data, true, "")));
        var state = operations.computeIfAbsent(key, ignored -> new State());
        synchronized (state) {
            boolean correcting = purpose == CallPurpose.SCHEMA_CORRECTION || purpose == CallPurpose.UNIT_CORRECTION;
            boolean repair = patch || hasRepair(data);
            var preferred = new AgentProperties.ModelSelection(request.provider(), request.model());
            var route = preferred.equals(state.rejected) ? state.alternate : preferred;
            if (route == null) throw stopped();
            long started = System.nanoTime();
            var response = route.equals(preferred) ? physical.apply(request) : alternate(request, route, physical, started);
            String fingerprint = fingerprint(response);
            boolean unchanged = fingerprint != null && (correcting || repair)
                    && (fingerprint.equals(state.previous) || repair && noSourceChange(data, response));
            if (!unchanged) { state.previous = fingerprint; return response; }
            repeated.accept(new Repeated(route, wasted.incrementAndGet()));
            if (state.alternate != null) throw stopped();
            state.rejected = route;
            var configured = planning ? properties.freePlanningRoutes().stream()
                    .limit(Math.clamp(properties.getPlanning().getMaxRouteAttempts(), 1, 3)).toList() : properties.freeStructuredRoutes();
            if (planning && !properties.getPlanning().isCorrectionFallback()) configured = List.of();
            var candidates = configured.stream().filter(candidate -> !candidate.equals(route))
                    .filter(properties::available).toList();
            if (candidates.isEmpty()) throw stopped();
            // Still one alternate answer: an unreachable route (expired key, outage) only passes the turn to the next.
            LlmClient.LlmResult next = null;
            for (var candidate : candidates)
                try {
                    next = alternate(request, candidate, physical, started);
                    state.alternate = candidate;
                    break;
                } catch (AlternateUnavailable unavailable) {
                    properties.markModelUnavailable(candidate);
                    if (candidate.equals(candidates.getLast())) throw unavailable;
                }
            String nextFingerprint = fingerprint(next);
            if (nextFingerprint != null && (nextFingerprint.equals(fingerprint) || nextFingerprint.equals(state.previous)
                    || repair && noSourceChange(data, next))) {
                repeated.accept(new Repeated(state.alternate, wasted.incrementAndGet()));
                throw stopped();
            }
            state.previous = nextFingerprint;
            return next;
        }
    }

    private LlmClient.LlmResult alternate(LlmClient.LlmRequest request, AgentProperties.ModelSelection route,
            Function<LlmClient.LlmRequest, LlmClient.LlmResult> physical, long started) {
        Duration remaining = request.budget() == null ? null : request.budget().minusNanos(System.nanoTime() - started);
        if (remaining != null && (remaining.isZero() || remaining.isNegative())) throw stopped();
        Integer cap = request.maxTokens();
        var verified = properties.getAccountingBounds().get(route.provider() + ":" + route.model());
        if (cap != null && verified != null && verified.getOutputTokens() > 0) cap = Math.min(cap, verified.getOutputTokens());
        var routed = new LlmClient.LlmRequest(route.provider(), route.model(), request.messages(), request.tools(), request.temperature(),
                remaining, request.toolChoice(), cap, properties.getPlanning().getSchemaProviders().contains(route.provider()) ? request.jsonSchema() : null);
        // A distinct physical call needs its own ledger identity, even inside an existing logical route attempt.
        try (var identity = LlmLogicalCall.bind(UUID.randomUUID())) { return physical.apply(routed); }
        catch (LlmHttpException | RetryableLlmException | LlmStreamException unavailable) {
            throw new AlternateUnavailable(wasted.get());
        }
    }

    private static final class AlternateUnavailable extends LlmException {
        AlternateUnavailable(int wasted) { super("No-progress alternate route unavailable; wastedCalls=" + wasted); }
    }

    private LlmException stopped() { return new LlmException("Model made no progress for the same failure; one alternate exhausted or unavailable; wastedCalls=" + wasted.get()); }

    private JsonNode input(LlmClient.LlmRequest request) {
        try { return mapper.readTree(request.messages().stream().filter(message -> "user".equals(message.role()))
                .map(LlmClient.LlmMessage::content).findFirst().orElse("{}")); }
        catch (RuntimeException malformed) { return null; }
    }

    private String fingerprint(LlmClient.LlmResult response) {
        if (response == null) return null;
        String text = response.hasToolCalls() && response.toolCalls().size() == 1
                ? response.toolCalls().getFirst().arguments() : response.content();
        if (text == null) return null;
        try { return hash(mapper.writeValueAsString(canonical(mapper.readTree(ModelJson.unfenced(text)), false, ""))); }
        catch (RuntimeException malformed) { return hash(tokens(text)); }
    }

    private Object canonical(JsonNode node, boolean input, String field) {
        if (node.isObject()) {
            var out = new TreeMap<String, Object>();
            node.properties().forEach(entry -> {
                if (!(input ? VOLATILE : Set.of("expectedVersion", "diagnosticsRevision")).contains(entry.getKey()))
                    out.put(entry.getKey(), canonical(entry.getValue(), input, entry.getKey()));
            });
            return out;
        }
        if (node.isArray()) { var out = new ArrayList<Object>(); node.forEach(value -> out.add(canonical(value, input, field))); return out; }
        if (node.isString()) {
            if (input && field.equals("repairFeedback")) {
                try { return canonical(mapper.readTree(node.asString()), true, ""); }
                catch (RuntimeException legacyText) { return node.asString(); }
            }
            return Set.of("content", "search", "replace").contains(field) ? tokens(node.asString()) : node.asString();
        }
        return node;
    }

    private static boolean hasRepair(JsonNode node) {
        if (node == null) return false;
        if (node.isObject() && node.has("repairFeedback") && !node.path("repairFeedback").asString("").isBlank()) return true;
        if (node.isObject() || node.isArray()) for (var child : node) if (hasRepair(child)) return true;
        return false;
    }

    private boolean noSourceChange(JsonNode input, LlmClient.LlmResult response) {
        try {
            var output = mapper.readTree(ModelJson.unfenced(response.content()));
            var source = new HashMap<String, String>(); collectSources(input, source);
            var files = new ArrayList<JsonNode>(); collectFiles(output, files);
            if (files.isEmpty()) return false;
            for (var file : files) {
                String before = source.get(file.path("path").asString());
                if (before == null) return false;
                String after;
                if (file.has("content")) { if (file.path("content").isNull()) return false; after = file.path("content").asString(); }
                else {
                    after = before;
                    for (var edit : file.path("edits")) {
                        String search = edit.path("search").asString();
                        if (search.isEmpty() || after.indexOf(search) < 0 || after.indexOf(search) != after.lastIndexOf(search)) return false;
                        after = after.replace(search, edit.path("replace").asString());
                    }
                }
                if (!tokens(before).equals(tokens(after))) return false;
            }
            return true;
        } catch (RuntimeException malformed) { return false; }
    }

    private static void collectSources(JsonNode node, Map<String, String> files) {
        if (node == null) return;
        for (String group : List.of("snapshot", "files"))
            if (node.path(group).isObject()) node.path(group).properties().forEach(entry -> {
                if (entry.getValue().path("content").isString()) files.put(entry.getKey(), entry.getValue().path("content").asString());
            });
        if (node.isArray()) node.forEach(child -> collectSources(child, files));
    }

    private static void collectFiles(JsonNode node, List<JsonNode> files) {
        if (node.has("files") && node.path("files").isArray()) node.path("files").forEach(files::add);
        if (node.has("patches") && node.path("patches").isArray()) node.path("patches").forEach(files::add);
        if (node.has("units")) node.path("units").forEach(unit -> collectFiles(unit, files));
    }

    private static String tokens(String value) {
        var out = new StringBuilder(); var matcher = TOKENS.matcher(value);
        while (matcher.find()) if (!matcher.group().isBlank()) out.append(matcher.group()).append('\u001f');
        return out.toString();
    }
    private static String hash(String value) { return ContractStore.hash(value); }
}
