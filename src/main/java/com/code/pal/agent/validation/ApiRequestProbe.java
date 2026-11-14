package com.code.pal.agent.validation;

import com.code.pal.agent.runtime.RunCancellation;
import com.code.pal.agent.workspace.SandboxPort;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;

import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CancellationException;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;

final class ApiRequestProbe {
    static final String PATH = "/api/[A-Za-z0-9_./-]{1,240}(\\?[A-Za-z0-9_.~%=&-]{1,200})?";
    static final Set<String> METHODS = Set.of("GET", "POST", "PUT", "PATCH", "DELETE");
    private static final Set<String> SECRET = Set.of("token", "accesstoken", "refreshtoken", "password", "passwordhash",
            "authorization", "secret", "apikey");
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final HttpClient HTTP = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(3))
            .followRedirects(HttpClient.Redirect.NEVER).build();

    private record Reply(int status, String body) {
    }

    private ApiRequestProbe() {
    }

    static MernBehaviorCheck.ApiView send(Supplier<SandboxPort.ServerEndpoint> endpoint, String method, String path,
            String body, boolean signedIn) {
        try {
            awaitReady(endpoint);
            String token = null;
            if (signedIn) {
                var credentials = JSON.writeValueAsString(Map.of("email", "check-" + UUID.randomUUID() + "@example.invalid",
                        "password", UUID.randomUUID().toString()));
                var signup = call(endpoint.get(), "POST", "/api/auth/signup", credentials, null);
                if (signup.status() != 201)
                    return new MernBehaviorCheck.ApiView(false, null, null,
                            "Signing in a fixture account failed: POST /api/auth/signup returned " + signup.status());
                token = JSON.readTree(signup.body()).path("token").asString("");
                if (token.isBlank() || token.length() > 8192)
                    return new MernBehaviorCheck.ApiView(false, null, null, "Signing in a fixture account returned no token");
            }
            var reply = call(endpoint.get(), method, path, body, token);
            return new MernBehaviorCheck.ApiView(true, reply.status(), redact(reply.body()), null);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new CancellationException("API check interrupted");
        } catch (CancellationException cancelled) {
            throw cancelled;
        } catch (Exception failure) {
            return new MernBehaviorCheck.ApiView(false, null, null, "The request failed: " + failure.getClass().getSimpleName());
        }
    }

    private static Reply call(SandboxPort.ServerEndpoint endpoint, String method, String path, String body, String token)
            throws Exception {
        if (endpoint == null)
            throw new IllegalStateException("Server unavailable");
        var request = HttpRequest.newBuilder(URI.create(endpoint.baseUrl() + path)).timeout(Duration.ofSeconds(8))
                .header("Content-Type", "application/json")
                .method(method, body == null || body.isBlank() ? HttpRequest.BodyPublishers.noBody()
                        : HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8));
        endpoint.headers().forEach(request::header);
        if (token != null)
            request.header("Authorization", "Bearer " + token);
        var response = HTTP.send(request.build(), HttpResponse.BodyHandlers.ofInputStream());
        try (InputStream stream = response.body()) {
            byte[] bytes = stream.readNBytes(65_536);
            return new Reply(response.statusCode(), new String(bytes, StandardCharsets.UTF_8));
        }
    }

    private static void awaitReady(Supplier<SandboxPort.ServerEndpoint> endpoint) throws Exception {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(25);
        while (System.nanoTime() < deadline) {
            if (RunCancellation.current().isCancelled())
                throw new CancellationException("API check cancelled");
            try {
                if (call(endpoint.get(), "GET", "/api/health", null, null).status() < 500)
                    return;
            } catch (InterruptedException interrupted) {
                throw interrupted;
            } catch (Exception notReady) {
                Thread.sleep(250);
            }
        }
        throw new IllegalStateException("Server readiness timed out");
    }

    static String redact(String body) {
        if (body == null || body.isBlank())
            return "";
        try {
            JsonNode tree = JSON.readTree(body);
            scrub(tree);
            String text = JSON.writeValueAsString(tree);
            return text.length() <= 4000 ? text : text.substring(0, 4000) + "…";
        } catch (RuntimeException notJson) {
            return body.length() <= 2000 ? body : body.substring(0, 2000) + "…";
        }
    }

    private static void scrub(JsonNode node) {
        if (node instanceof ObjectNode object) {
            object.propertyNames().stream().filter(name -> SECRET.contains(name.toLowerCase(Locale.ROOT))).toList()
                    .forEach(name -> object.put(name, "[hidden]"));
            object.values().forEach(ApiRequestProbe::scrub);
        } else if (node.isArray())
            node.forEach(ApiRequestProbe::scrub);
    }
}
