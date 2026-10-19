package com.code.pal.agent.workspace;

import com.code.pal.agent.config.AgentProperties;
import com.code.pal.agent.validation.SupabaseApiAcceptance;
import com.code.pal.agent.validation.ValidationCheck;
import com.code.pal.project.spec.ApprovedSpec;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import tools.jackson.databind.ObjectMapper;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

@EnabledIfSystemProperty(named = "codepal.supabase.it", matches = "true")
class DockerSupabaseScratchIT {
    private DockerSupabaseScratch factory() {
        var properties = new AgentProperties();
        properties.getSupabaseValidation().setEnabled(true);
        properties.getSandbox().setDockerCommand(System.getProperty("codepal.docker.command", "docker"));
        properties.getSandbox().setDockerCommandArgs(System.getProperty("codepal.docker.args", ""));
        return new DockerSupabaseScratch(properties, new ObjectMapper());
    }

    @Test
    void isolatedPasswordAccountsCrudRestartPersistenceAndRowIsolation() throws Exception {
        String sql;
        try (var stream = getClass().getResourceAsStream("/supabase/owned-tasks.sql")) {
            sql = new String(stream.readAllBytes(), StandardCharsets.UTF_8);
        }
        var entities = List.of(new ApprovedSpec.Entity("tasks", List.of(new ApprovedSpec.Field("title", "STRING", true),
                new ApprovedSpec.Field("completed", "BOOLEAN", true)), ApprovedSpec.Storage.SUPABASE, "USER"));
        try (var scratch = factory().open(UUID.randomUUID(), UUID.randomUUID(), List.of(new SupabaseScratch.Migration("owned-tasks.sql", sql)))) {
            assertTrue(scratch.buildVariables().get("VITE_SUPABASE_URL").endsWith(".invalid"));
            var checks = new SupabaseApiAcceptance(new ObjectMapper(), new AgentProperties()).check(scratch, entities, true);
            assertFalse(checks.isEmpty());
            assertTrue(checks.stream().allMatch(check -> check.outcome() == ValidationCheck.Outcome.PASS), checks.toString());
            assertTrue(checks.stream().anyMatch(check -> check.id().equals("SUPABASE_ISOLATION")), checks.toString());
        }
    }

    @Test
    void generatedSqlCannotResetItsRoleToCreateASuperuser() {
        var failure = assertThrows(SupabaseScratchException.class, () -> factory().open(UUID.randomUUID(), UUID.randomUUID(),
                List.of(new SupabaseScratch.Migration("forbidden.sql", "RESET ROLE; CREATE ROLE forbidden SUPERUSER;"))));
        assertTrue(failure.schemaFailure(), failure.getMessage());
    }

    @Test
    void migrationInputCannotExecutePsqlShellMetaCommands() {
        var failure = assertThrows(SupabaseScratchException.class, () -> factory().open(UUID.randomUUID(), UUID.randomUUID(),
                List.of(new SupabaseScratch.Migration("meta.sql", "\\! true\nSELECT 1;"))));
        assertTrue(failure.schemaFailure(), failure.getMessage());
    }

    @Test
    void missingRowLevelSecurityCannotPassAnAccountDataBuild() throws Exception {
        String sql;
        try (var stream = getClass().getResourceAsStream("/supabase/owned-tasks.sql")) {
            sql = new String(stream.readAllBytes(), StandardCharsets.UTF_8);
        }
        sql += "\nALTER TABLE public.tasks DISABLE ROW LEVEL SECURITY;\n";
        var entities = List.of(new ApprovedSpec.Entity("tasks", List.of(new ApprovedSpec.Field("title", "STRING", true)),
                ApprovedSpec.Storage.SUPABASE, "USER"));
        try (var scratch = factory().open(UUID.randomUUID(), UUID.randomUUID(), List.of(new SupabaseScratch.Migration("unsafe.sql", sql)))) {
            var checks = new SupabaseApiAcceptance(new ObjectMapper(), new AgentProperties()).check(scratch, entities, true);
            assertTrue(checks.stream().anyMatch(check -> List.of("SUPABASE_ANONYMOUS_ISOLATION", "SUPABASE_USER_ISOLATION").contains(check.id())
                    && check.outcome() == ValidationCheck.Outcome.FAIL), checks.toString());
        }
    }
}
