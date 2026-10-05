package com.code.pal.agent.validation;

import com.code.pal.agent.change.*;
import com.code.pal.agent.config.AgentProperties;
import com.code.pal.agent.context.ProjectContextTrace;
import com.code.pal.agent.plan.AgentPlanState;
import com.code.pal.agent.repository.AgentRunRepository;
import com.code.pal.agent.tool.AgentContext;
import com.code.pal.agent.tool.AgentToolException;
import com.code.pal.agent.workspace.LocalProject;
import com.code.pal.project.domain.ProjectType;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;

import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class ProjectValidationServiceTest {
    @Test
    void reportsIntroducedDiagnosticThenAcceptsBoundedRepair() throws Exception {
        Fixture fixture = fixture(3);
        String initial = fixture.version();
        fixture.changeSets.patch(fixture.context, "src/App.tsx", initial, "OK", "BROKEN");

        ValidationReport failed = fixture.validation.run(fixture.context, ValidationOperation.TYPE_CHECK, null);

        assertFalse(failed.changeValidationPassed());
        assertEquals("src/App.tsx", failed.introducedDiagnostics().getFirst().file());
        assertEquals(1, failed.introducedDiagnostics().getFirst().line());
        assertTrue(failed.preExistingDiagnostics().isEmpty());

        fixture.changeSets.patch(fixture.context, "src/App.tsx", fixture.version(),
                "BROKEN", "OK");
        ValidationReport repaired = fixture.validation.validateAll(fixture.context);

        assertTrue(repaired.changeValidationPassed());
        assertEquals(1, repaired.repairAttempts());
        assertFalse(fixture.changeSets.hasUnvalidatedChanges(fixture.context));
        assertEquals("export const value = 'OK';\n", fixture.project().read("src/App.tsx"),
                "validation and repair must remain isolated from the live workspace");
    }

    @Test
    void refusesARepairBeyondConfiguredLimit() throws Exception {
        Fixture fixture = fixture(2);
        fixture.changeSets.patch(fixture.context, "src/App.tsx", fixture.version(), "OK", "BROKEN-0");
        assertFalse(fixture.validation.run(fixture.context, ValidationOperation.TYPE_CHECK, null).changeValidationPassed());

        for (int attempt = 1; attempt <= 2; attempt++) {
            String previous = "BROKEN-" + (attempt - 1);
            String next = "BROKEN-" + attempt;
            fixture.changeSets.patch(fixture.context, "src/App.tsx", fixture.version(), previous, next);
            assertFalse(fixture.validation.run(fixture.context, ValidationOperation.TYPE_CHECK, null).changeValidationPassed());
        }

        assertTrue(fixture.changeSets.validationProgress(fixture.context).repairLimitExhausted());
        AgentToolException error = assertThrows(AgentToolException.class, () -> fixture.changeSets.patch(
                fixture.context, "src/App.tsx", fixture.version(), "BROKEN-2", "OK"));
        assertEquals("REPAIR_LIMIT_EXCEEDED", error.code());
    }

    @Test
    void distinguishesUnrelatedPreExistingDiagnostics() throws Exception {
        Fixture fixture = fixture(3, "BROKEN-existing");
        fixture.changeSets.create(fixture.context, "src/theme.ts", "export const dark = true;\n");

        ValidationReport report = fixture.validation.run(fixture.context, ValidationOperation.TYPE_CHECK, null);

        assertTrue(report.changeValidationPassed());
        assertTrue(report.introducedDiagnostics().isEmpty());
        assertFalse(report.preExistingDiagnostics().isEmpty());
    }

    private Fixture fixture(int maxRepairs) throws Exception {
        return fixture(maxRepairs, "OK");
    }

    private Fixture fixture(int maxRepairs, String initialValue) {
        AgentProperties properties = new AgentProperties();
        properties.setMaxRepairAttempts(maxRepairs);
        properties.getSandbox().setCommandTimeoutMs(15_000);
        LocalProject project = LocalProject.create(properties)
                .write("src/App.tsx", "export const value = '" + initialValue + "';\n")
                .write("package.json", """
                        {"packageManager":"npm@10.9.0","scripts":{"typecheck":"node validate.cjs"}}
                        """)
                .write("package-lock.json", "{\"lockfileVersion\":3}")
                .write("validate.cjs", """
                        const fs = require('fs');
                        const value = fs.readFileSync('src/App.tsx', 'utf8');
                        if (value.includes('BROKEN')) {
                          console.error('src/App.tsx(1,1): error TS2307: Cannot find module theme');
                          process.exit(1);
                        }
                        """);

        FileChangeRepository repository = mock(FileChangeRepository.class);
        when(repository.save(any(FileChange.class))).thenAnswer(call -> call.getArgument(0));
        ObjectMapper mapper = new ObjectMapper();
        ChangeSetService changeSets = new ChangeSetService(repository, mock(AgentRunRepository.class), mapper,
                properties, project.files, project.sandbox);
        UUID runId = UUID.randomUUID();
        String stage = changeSets.start(runId, project.projectId);
        AgentPlanState plan = new AgentPlanState();
        plan.revise(List.of("Modify App", "Type-check", "Repair if necessary"), "test");
        AgentContext context = new AgentContext(project.projectId, 7L, ProjectType.BUILD, stage, null,
                new ProjectContextTrace(), runId, plan, project.files);
        ProjectValidationService validation = new ProjectValidationService(changeSets,
                new ProjectCommandResolver(mapper), properties, project.files, project.sandbox);
        return new Fixture(project, context, changeSets, validation);
    }

    private record Fixture(LocalProject project, AgentContext context, ChangeSetService changeSets,
                           ProjectValidationService validation) {
        String version() {
            return project.files.version(project.projectId, context.root(), "src/App.tsx");
        }
    }
}
