package com.code.pal.project.supabase;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import tools.jackson.databind.ObjectMapper;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Against a running local Supabase stack (Supabase CLI), reached through Windows-visible ports. Opt-in:
 * {@code mvnw test -Dtest=SupabaseLocalIT -Dcodepal.supabase.it=true}. Defaults match the CLI's local
 * stack behind the dev forwarder (API 55321, database 55322) and its well-known local publishable key.
 */
@EnabledIfSystemProperty(named = "codepal.supabase.it", matches = "true")
class SupabaseLocalIT {
    private static final String URL = System.getProperty("codepal.supabase.url", "http://127.0.0.1:55321");
    private static final String DB = System.getProperty("codepal.supabase.db", "jdbc:postgresql://127.0.0.1:55322/postgres");
    private static final String KEY = System.getProperty("codepal.supabase.key", "sb_publishable_ACJWlzQHlZjBrEguHvfOxg_3BJgxAaH");

    private final SelfHostedSupabase instance = new SelfHostedSupabase(URL, DB, "postgres", "postgres");
    private final SupabaseClient client = new SupabaseClient(new ObjectMapper(), "https://api.supabase.com");
    private final String table = "codepal_it_" + UUID.randomUUID().toString().replace("-", "").substring(0, 10);
    private final HttpClient http = HttpClient.newHttpClient();

    @AfterEach
    void dropTable() {
        try { instance.runSql("drop table if exists public." + table + ";"); } catch (RuntimeException ignored) { }
    }

    @Test
    void theAnonKeyIsReallyChecked() {
        client.verifyAnonKey(URL, KEY);
        assertThrows(SupabaseException.class, () -> client.verifyAnonKey(URL, "not-a-key"));
    }

    @Test
    void anAppliedMigrationIsServedByTheRestApiUnderItsPolicies() throws Exception {
        instance.runSql("""
                create table public.%1$s (id bigint generated always as identity primary key, title text not null);
                alter table public.%1$s enable row level security;
                create policy "anyone reads" on public.%1$s for select using (true);
                create policy "anyone adds" on public.%1$s for insert with check (true);
                """.formatted(table));

        // PostgREST reloads its schema cache asynchronously after the migration's NOTIFY.
        HttpResponse<String> inserted = rest("POST", "{\"title\":\"Buy milk\"}");
        for (int waited = 0; inserted.statusCode() == 404 && waited < 5_000; waited += 250) {
            Thread.sleep(250);
            inserted = rest("POST", "{\"title\":\"Buy milk\"}");
        }
        HttpResponse<String> listed = rest("GET", null);

        assertEquals(201, inserted.statusCode(), inserted.body());
        assertEquals(200, listed.statusCode(), listed.body());
        assertTrue(listed.body().contains("Buy milk"), listed.body());
    }

    @Test
    void aFailingMigrationLeavesNothingBehind() throws Exception {
        SupabaseException failure = assertThrows(SupabaseException.class, () -> instance.runSql(
                "create table public." + table + " (id int); select * from table_that_does_not_exist;"));

        assertTrue(failure.getMessage().contains("table_that_does_not_exist"), failure.getMessage());
        assertEquals(404, rest("GET", null).statusCode(), "the table from the failed migration was rolled back");
    }

    private HttpResponse<String> rest(String method, String body) throws Exception {
        HttpRequest.Builder request = HttpRequest.newBuilder(URI.create(URL + "/rest/v1/" + table))
                .header("apikey", KEY).header("Content-Type", "application/json");
        request = body == null ? request.GET() : request.method(method, HttpRequest.BodyPublishers.ofString(body));
        return http.send(request.build(), HttpResponse.BodyHandlers.ofString());
    }
}
