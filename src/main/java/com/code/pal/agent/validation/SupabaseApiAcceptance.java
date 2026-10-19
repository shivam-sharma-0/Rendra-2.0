package com.code.pal.agent.validation;

import com.code.pal.agent.runtime.RunCancellation;
import com.code.pal.agent.config.AgentProperties;
import com.code.pal.agent.llm.LlmCancelledException;
import com.code.pal.agent.workspace.SupabaseScratch;
import com.code.pal.project.spec.ApprovedSpec.Entity;
import com.code.pal.project.spec.ApprovedSpec.Field;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.Locale;
import java.util.concurrent.CancellationException;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

/** Backend-owned outcome assertions on a fresh scratch target; no SQL, tokens or response bodies in diagnostics. */
@Component
public final class SupabaseApiAcceptance {
    private record Reply(int status, JsonNode body) {
        boolean success() { return status >= 200 && status < 300; }
    }
    private record User(String id, String token) {}
    private record Row(Entity entity, String id, String field, Object original, Object updated) {}
    private final ObjectMapper mapper;
    private final AgentProperties properties;
    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(2))
            .followRedirects(HttpClient.Redirect.NEVER).build();

    public SupabaseApiAcceptance(ObjectMapper mapper, AgentProperties properties) {
        this.mapper = mapper;
        this.properties = properties;
    }

    public List<ValidationCheck> check(SupabaseScratch.Session session, List<Entity> entities, boolean accounts) {
        var checks = new ArrayList<ValidationCheck>();
        var client = new Client(session);
        try {
            User owner = accounts ? user(client) : new User(null, session.publicKey());
            User other = accounts ? user(client) : null;
            if (accounts) {
                require(!owner.id().equals(other.id()), "SUPABASE_AUTH", "Independent accounts must have distinct identities");
                checks.add(pass("SUPABASE_AUTH", "Two independent password accounts signed up and signed in"));
            }
            if (entities.isEmpty() && !accounts) {
                return List.of(new ValidationCheck("SUPABASE_DATA", ValidationCheck.Outcome.SKIPPED, true,
                        "Approved data entities are unavailable; cloud CRUD cannot be certified"));
            }
            JsonNode schema = client.request("GET", "/rest/v1/", owner.token(), null).body();
            var rows = new ArrayList<Row>();
            for (Entity entity : entities) {
                rows.add(create(client, entity, schema, owner, accounts));
            }
            session.restartRest();
            for (Row row : rows) {
                String path = table(row.entity()) + "?id=eq." + row.id();
                require(stored(client.request("GET", path, owner.token(), null), row.field(), row.original()),
                        "SUPABASE_PERSISTENCE", "Created data must survive a REST process restart");
                if (accounts && "USER".equals(row.entity().ownership())) {
                    isolation(client, row, schema, owner, other);
                }
                Reply update = client.request("PATCH", path, owner.token(), Map.of(row.field(), row.updated()));
                require(update.success() && stored(client.request("GET", path, owner.token(), null), row.field(), row.updated()),
                        "SUPABASE_UPDATE", "An update must change the stored field");
                Reply delete = client.request("DELETE", path, owner.token(), null);
                require(delete.success() && empty(client.request("GET", path, owner.token(), null)),
                        "SUPABASE_DELETE", "Deleted data must no longer be returned");
            }
            if (!rows.isEmpty()) checks.add(pass("SUPABASE_CRUD", "Approved entities passed create/read/update/delete and restart persistence"));
            if (rows.stream().anyMatch(row -> accounts && "USER".equals(row.entity().ownership()))) {
                checks.add(pass("SUPABASE_ISOLATION", "Anonymous/invalid sessions and another account cannot read, overwrite, delete or impersonate private owners"));
            }
        } catch (AssertionFailure failure) {
            checks.add(new ValidationCheck(failure.id, ValidationCheck.Outcome.FAIL, true, failure.getMessage()));
        } catch (CancellationException | LlmCancelledException cancelled) {
            throw new LlmCancelledException();
        } catch (InterruptedException cancelled) {
            Thread.currentThread().interrupt();
            throw new LlmCancelledException();
        } catch (Exception unavailable) {
            if (RunCancellation.current().isCancelled()) throw new LlmCancelledException();
            checks.add(new ValidationCheck("SUPABASE_BEHAVIOUR", ValidationCheck.Outcome.SKIPPED, true,
                    "Isolated Supabase behavior transport was unavailable or returned an unsupported response"));
        }
        return List.copyOf(checks);
    }

    private Row create(Client client, Entity entity, JsonNode schema, User owner, boolean accounts) throws Exception {
        require(entity.id().matches("[A-Za-z_][A-Za-z0-9_]{0,63}"), "SUPABASE_SCHEMA", "Approved entity has no supported table identity");
        var payload = payload(entity, schema);
        if (accounts && "USER".equals(entity.ownership())) payload.put("user_id", owner.id());
        String mutable = null;
        Object initial = null;
        Object updated = null;
        for (Field field : entity.fields()) {
            if (!payload.containsKey(field.name()) || field.name().equals("user_id")) continue;
            Object replacement = value(field, definition(schema, entity, field), true);
            if (!payload.get(field.name()).equals(replacement)) {
                mutable = field.name();
                initial = payload.get(field.name());
                updated = replacement;
                break;
            }
        }
        require(mutable != null, "SUPABASE_SCHEMA", "Entity needs a supported mutable field for independent CRUD evidence");
        Reply created = client.request("POST", table(entity), owner.token(), payload);
        require(created.success() && created.body().isArray() && created.body().size() == 1,
                "SUPABASE_CREATE", "Creating an approved entity must return the stored row");
        return new Row(entity, rowId(created), mutable, initial, updated);
    }

    private void isolation(Client client, Row row, JsonNode schema, User owner, User other) throws Exception {
        String path = table(row.entity()) + "?id=eq." + row.id();
        var otherPayload = payload(row.entity(), schema);
        otherPayload.put("user_id", other.id());
        Reply otherCreated = client.request("POST", table(row.entity()), other.token(), otherPayload);
        require(otherCreated.success() && otherCreated.body().isArray() && otherCreated.body().size() == 1,
                "SUPABASE_SECOND_ACCOUNT", "The second account must also be able to create its own data");
        String otherPath = table(row.entity()) + "?id=eq." + rowId(otherCreated);
        require(stored(client.request("GET", otherPath, other.token(), null), row.field(), otherPayload.get(row.field())),
                "SUPABASE_SECOND_ACCOUNT", "The second account must be able to read its own stored data");
        require(hidden(client.request("GET", otherPath, owner.token(), null)), "SUPABASE_USER_ISOLATION",
                "Account isolation must hold in both directions");
        require(hidden(client.request("GET", path, null, null)), "SUPABASE_ANONYMOUS_ISOLATION",
                "Anonymous clients must not read private account data");
        require(hidden(client.request("GET", path, other.token(), null)), "SUPABASE_USER_ISOLATION",
                "Another account must not read private account data");
        require(hidden(client.request("GET", path, "invalid-validation-token", null)), "SUPABASE_INVALID_TOKEN",
                "Invalid sessions must not read private account data");
        for (String token : List.of(other.token(), client.session.publicKey(), "invalid-validation-token")) {
            require(hidden(client.request("PATCH", path, token, Map.of(row.field(), row.updated()))),
                    "SUPABASE_WRITE_ISOLATION", "Unauthorized updates must be denied or affect no rows");
            require(hidden(client.request("DELETE", path, token, null)),
                    "SUPABASE_WRITE_ISOLATION", "Unauthorized deletes must be denied or affect no rows");
        }
        require(stored(client.request("GET", path, owner.token(), null), row.field(), row.original()),
                "SUPABASE_WRITE_ISOLATION", "Unauthorized clients must not update or delete the owner's row");
        var forged = new LinkedHashMap<>(otherPayload);
        forged.put("user_id", owner.id());
        require(client.request("POST", table(row.entity()), other.token(), forged).status() == 403,
                "SUPABASE_INSERT_ISOLATION", "An account must not create rows as another owner");
        require(client.request("DELETE", otherPath, other.token(), null).success()
                && empty(client.request("GET", otherPath, other.token(), null)), "SUPABASE_SECOND_ACCOUNT",
                "The second account must be able to delete its own data");
    }

    private static Map<String, Object> payload(Entity entity, JsonNode schema) {
        var result = new LinkedHashMap<String, Object>();
        for (Field field : entity.fields()) {
            if (!List.of("id", "user_id", "created_at", "updated_at").contains(field.name())) {
                result.put(field.name(), value(field, definition(schema, entity, field), false));
            }
        }
        return result;
    }

    private static JsonNode definition(JsonNode schema, Entity entity, Field field) {
        return schema.path("definitions").path(entity.id()).path("properties").path(field.name());
    }

    private static String rowId(Reply created) {
        String id = created.body().get(0).path("id").asString("");
        require(id.matches("[A-Za-z0-9_-]{1,100}"), "SUPABASE_SCHEMA", "Stored entities need a readable id");
        return id;
    }

    private User user(Client client) throws Exception {
        String email = "check-" + UUID.randomUUID() + "@example.invalid";
        String password = UUID.randomUUID().toString();
        var credentials = Map.of("email", email, "password", password);
        Reply signup = client.request("POST", "/auth/v1/signup", client.session.publicKey(), credentials);
        require(signup.success(), "SUPABASE_SIGNUP", "An isolated password account must be creatable");
        Reply login = client.request("POST", "/auth/v1/token?grant_type=password", client.session.publicKey(), credentials);
        require(login.success() && login.body().path("access_token").isString()
                && login.body().path("user").path("id").isString(), "SUPABASE_LOGIN", "A new account must be able to sign in");
        Reply rejected = client.request("POST", "/auth/v1/token?grant_type=password", client.session.publicKey(),
                Map.of("email", email, "password", UUID.randomUUID().toString()));
        require(List.of(400, 401, 403).contains(rejected.status()), "SUPABASE_LOGIN_REJECT", "An incorrect password must be rejected");
        Reply identity = client.request("GET", "/auth/v1/user", login.body().path("access_token").asString(), null);
        require(identity.success() && identity.body().path("id").asString("").equals(login.body().path("user").path("id").asString()),
                "SUPABASE_SESSION", "The returned session must authenticate as the signed-in account");
        return new User(login.body().path("user").path("id").asString(), login.body().path("access_token").asString());
    }

    private static Object value(Field field, JsonNode definition, boolean update) {
        if (definition.path("enum").isArray() && !definition.path("enum").isEmpty()) {
            return definition.path("enum").get(update && definition.path("enum").size() > 1 ? 1 : 0).asString();
        }
        return switch (field.kind().toUpperCase(Locale.ROOT)) {
        case "BOOLEAN" -> update;
        case "NUMBER" -> update ? 2 : 1;
        case "DATE" -> LocalDate.now(ZoneOffset.UTC).plusDays(update ? 2 : 1).toString();
        default -> field.name().toLowerCase(Locale.ROOT).contains("email") ? "check-" + UUID.randomUUID() + "@example.invalid"
                : field.name().toLowerCase(Locale.ROOT).contains("url") ? "https://example.invalid/" + UUID.randomUUID()
                : "fixture-" + UUID.randomUUID();
        };
    }

    private final class Client {
        private final SupabaseScratch.Session session;
        private final long deadline = System.nanoTime()
                + TimeUnit.MILLISECONDS.toNanos(Math.max(1, properties.getSupabaseValidation().getBehaviorTimeoutMs()));

        Client(SupabaseScratch.Session session) { this.session = session; }

        private Reply request(String method, String path, String token, Object payload) throws Exception {
            if (RunCancellation.current().isCancelled()) throw new LlmCancelledException();
            long remaining = remainingMillis();
            var request = HttpRequest.newBuilder(URI.create(session.endpoint().baseUrl() + path)).timeout(Duration.ofMillis(remaining))
                    .header("Content-Type", "application/json").header("apikey", session.publicKey()).header("Prefer", "return=representation");
            session.endpoint().headers().forEach(request::header);
            if (token != null) request.header("Authorization", "Bearer " + token);
            request.method(method, payload == null ? HttpRequest.BodyPublishers.noBody()
                    : HttpRequest.BodyPublishers.ofString(mapper.writeValueAsString(payload)));
            var future = http.sendAsync(request.build(), HttpResponse.BodyHandlers.ofInputStream());
            try (var cancelled = RunCancellation.current().onCancel(() -> future.cancel(true))) {
                var response = future.get(remaining, TimeUnit.MILLISECONDS);
                try (var stream = response.body(); var reader = Executors.newVirtualThreadPerTaskExecutor();
                        var stopRead = RunCancellation.current().onCancel(() -> {
                            try { stream.close(); } catch (IOException ignored) { }
                        })) {
                    var read = reader.submit(() -> stream.readNBytes(65_537));
                    byte[] bytes;
                    try { bytes = read.get(remainingMillis(), TimeUnit.MILLISECONDS); }
                    finally { stream.close(); read.cancel(true); }
                    if (bytes.length > 65_536) throw new IOException("Response exceeds validation capacity");
                    return new Reply(response.statusCode(), bytes.length == 0 ? mapper.nullNode()
                            : mapper.readTree(new String(bytes, StandardCharsets.UTF_8)));
                }
            } finally { future.cancel(true); }
        }

        private long remainingMillis() throws IOException {
            if (System.nanoTime() >= deadline) throw new IOException("Supabase acceptance deadline exceeded");
            return Math.min(5_000, Math.max(1, TimeUnit.NANOSECONDS.toMillis(deadline - System.nanoTime())));
        }
    }

    private static String table(Entity entity) { return "/rest/v1/" + entity.id(); }
    private static boolean stored(Reply reply, String field, Object value) {
        if (!reply.success() || !reply.body().isArray() || reply.body().size() != 1) return false;
        JsonNode actual = reply.body().get(0).path(field);
        if (value instanceof Number number) return actual.isNumber() && actual.asDouble() == number.doubleValue();
        if (value instanceof Boolean flag) return actual.isBoolean() && actual.asBoolean() == flag;
        return actual.isString() && actual.asString().equals(String.valueOf(value));
    }
    private static boolean empty(Reply reply) { return reply.success() && reply.body().isArray() && reply.body().isEmpty(); }
    private static boolean hidden(Reply reply) { return reply.status() == 401 || reply.status() == 403 || empty(reply); }
    private static ValidationCheck pass(String id, String reason) { return new ValidationCheck(id, ValidationCheck.Outcome.PASS, true, reason); }
    private static void require(boolean condition, String id, String reason) {
        if (!condition) throw new AssertionFailure(id, reason);
    }
    private static final class AssertionFailure extends RuntimeException {
        private final String id;
        AssertionFailure(String id, String reason) { super(reason); this.id = id; }
    }
}
