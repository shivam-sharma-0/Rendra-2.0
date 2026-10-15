package com.code.pal.agent.validation;

import com.code.pal.agent.runtime.RunCancellation;
import com.code.pal.agent.workspace.SandboxPort;
import org.springframework.stereotype.Component;
import tools.jackson.databind.ObjectMapper;
import java.net.URI;
import java.net.http.*;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;
import java.io.IOException;
import java.util.function.Supplier;

/** Calls only the backend-selected candidate endpoint. Responses/tokens never enter model diagnostics. */
@Component
public final class ApiBehaviorProbe {
    private final ObjectMapper mapper;
    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(3))
            .followRedirects(HttpClient.Redirect.NEVER).build();

    public ApiBehaviorProbe(ObjectMapper mapper) {
        this.mapper = mapper;
    }

    public List<ValidationCheck> run(BehaviorCriteria criteria, Supplier<SandboxPort.ServerEndpoint> endpoint,
            Runnable restart) {
        Map<String, String> values = new HashMap<>();
        String id = UUID.randomUUID().toString();
        values.put("fixtureId", id);
        values.put("fixtureEmail", "check-" + id + "@example.invalid");
        values.put("fixturePassword", UUID.randomUUID().toString());
        values.put("invalidToken", UUID.randomUUID().toString());
        List<ValidationCheck> checks = new ArrayList<>();
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(60);
        for (var step : criteria.api()) {
            if (RunCancellation.current().isCancelled())
                throw new CancellationException("Validation cancelled");
            if (System.nanoTime() >= deadline) {
                checks.add(new ValidationCheck("API_" + step.id(), ValidationCheck.Outcome.SKIPPED, true,
                        "Candidate acceptance deadline exceeded"));
                break;
            }
            try {
                if (step.kind() == BehaviorCriteria.ApiKind.PERSISTENCE)
                    restart.run();
                String path = expand(step.path(), values);
                if (!path.matches("/api/[A-Za-z0-9_./-]{1,240}") || path.contains("..") || path.contains("//"))
                    throw new IllegalArgumentException("Unsafe expanded request path");
                var target = endpoint.get();
                var request = HttpRequest.newBuilder(URI.create(target.baseUrl() + path)).timeout(Duration.ofSeconds(8))
                        .header("Content-Type", "application/json");
                if (step.kind() == BehaviorCriteria.ApiKind.HEALTH
                        || step.kind() == BehaviorCriteria.ApiKind.PERSISTENCE)
                    awaitReady(target, criteria.api().getFirst().path());
                target.headers().forEach(request::header);
                if (step.tokenVariable() != null) {
                    String token = values.get(step.tokenVariable());
                    if (token == null || token.length() > 8192 || token.contains("\r") || token.contains("\n"))
                        throw new IllegalStateException("Invalid fixture token");
                    request.header("Authorization", "Bearer " + token);
                }
                String body = expand(step.body() == null ? "" : step.body(), values);
                if (!body.isEmpty())
                    mapper.readTree(body);
                request.method(step.method(), body.isEmpty() ? HttpRequest.BodyPublishers.noBody()
                        : HttpRequest.BodyPublishers.ofString(body));
                var future = http.sendAsync(request.build(), HttpResponse.BodyHandlers.ofInputStream());
                try (var cancelled = RunCancellation.current().onCancel(() -> future.cancel(true))) {
                    var response = future.get(8, TimeUnit.SECONDS);
                    if (response.headers().firstValue("x-codepal-relay-error").isPresent()) {
                        response.body().close();
                        checks.add(new ValidationCheck("API_" + step.id(), ValidationCheck.Outcome.SKIPPED, true,
                                "Candidate API transport is unavailable"));
                        break;
                    }
                    try (var stream = response.body();
                            var readingCancellation = RunCancellation.current().onCancel(() -> {
                                try {
                                    stream.close();
                                } catch (IOException ignored) {
                                }
                            });
                            var reader = Executors.newVirtualThreadPerTaskExecutor()) {
                        var read = reader.submit(() -> stream.readNBytes(65_537));
                        byte[] bytes;
                        try {
                            bytes = read.get(8, TimeUnit.SECONDS);
                        } finally {
                            stream.close();
                            read.cancel(true);
                        }
                        if (bytes.length > 65_536)
                            throw new IllegalStateException("Response limit exceeded");
                        if (response.statusCode() != step.status())
                            throw new IllegalStateException("Unexpected response status");
                        if (step.assertPointer() != null || step.captureVariable() != null) {
                            var json = mapper.readTree(new String(bytes, StandardCharsets.UTF_8));
                            if (step.assertPointer() != null) {
                                var asserted = json.at(step.assertPointer());
                                if (!asserted.isValueNode() || asserted.isNull() || !asserted.asText()
                                        .equals(expand(step.assertValue() == null ? "" : step.assertValue(), values)))
                                    throw new IllegalStateException("Expected response value missing");
                            }
                            if (step.captureVariable() != null) {
                                var value = json.at(step.capturePointer());
                                if (!value.isValueNode() || value.isNull() || value.asString().isBlank()
                                        || value.asString().length() > 8192)
                                    throw new IllegalStateException("Expected fixture value missing");
                                values.put(step.captureVariable(), value.asString());
                            }
                        }
                    }
                }
                checks.add(new ValidationCheck("API_" + step.id(), ValidationCheck.Outcome.PASS, true,
                        "Candidate acceptance passed"));
            } catch (CancellationException cancelled) {
                throw cancelled;
            } catch (Exception failure) {
                if (failure instanceof InterruptedException) {
                    Thread.currentThread().interrupt();
                    throw new CancellationException("Validation interrupted");
                }
                if (RunCancellation.current().isCancelled())
                    throw new CancellationException("Validation cancelled");
                checks.add(new ValidationCheck("API_" + step.id(), ValidationCheck.Outcome.FAIL, true,
                        "Candidate acceptance did not pass"));
                break;
            }
        }
        if (checks.size() < criteria.api().size())
            for (var step : criteria.api().subList(checks.size(), criteria.api().size()))
                checks.add(new ValidationCheck("API_" + step.id(), ValidationCheck.Outcome.SKIPPED, true,
                        "Earlier acceptance failed", ValidationCheck.SkipCause.PREREQUISITE_FAILED));
        return List.copyOf(checks);
    }

    private static String expand(String text, Map<String, String> values) {
        for (var e : values.entrySet())
            text = text.replace("${" + e.getKey() + "}", e.getValue());
        if (text.contains("${"))
            throw new IllegalArgumentException("Unknown fixture variable");
        return text;
    }

    private void awaitReady(SandboxPort.ServerEndpoint endpoint, String path) throws Exception {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
        while (System.nanoTime() < deadline) {
            if (RunCancellation.current().isCancelled())
                throw new CancellationException("Validation cancelled");
            var request = HttpRequest.newBuilder(URI.create(endpoint.baseUrl() + path)).timeout(Duration.ofSeconds(1))
                    .GET();
            endpoint.headers().forEach(request::header);
            var future = http.sendAsync(request.build(), HttpResponse.BodyHandlers.ofInputStream());
            try (var cancelled = RunCancellation.current().onCancel(() -> future.cancel(true))) {
                var response = future.get(2, TimeUnit.SECONDS);
                try (var body = response.body()) {
                    if (response.statusCode() < 500)
                        return;
                }
            } catch (CancellationException stopped) {
                throw stopped;
            } catch (Exception unavailable) {
                future.cancel(true);
            }
            Thread.sleep(100);
        }
        throw new IllegalStateException("Candidate API readiness timed out");
    }
}
