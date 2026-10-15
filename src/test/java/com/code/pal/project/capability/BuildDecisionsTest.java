package com.code.pal.project.capability;

import com.code.pal.agent.config.AgentProperties;
import com.code.pal.agent.workspace.LocalProject;
import com.code.pal.project.domain.*;
import com.code.pal.project.exception.WorkflowStageException;
import com.code.pal.project.repository.RequirementSessionRepository;
import com.code.pal.project.supabase.SupabaseConnectionRepository;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class BuildDecisionsTest {
    final UUID id = UUID.randomUUID();
    final RequirementSessionRepository sessions = mock(RequirementSessionRepository.class);
    final SupabaseConnectionRepository connections = mock(SupabaseConnectionRepository.class);
    final AgentProperties properties = new AgentProperties();
    final BuildDecisions decisions = new BuildDecisions(sessions, connections, properties, new ObjectMapper());

    BuildDecisionsTest() { properties.getSandbox().setRequired(true); }

    @Test void browserRemainsBrowserEvenWithAConnection() {
        connect();
        approve(BuildDecisions.Storage.BROWSER, BuildDecisions.Auth.NONE);
        var state = decisions.state(id);
        assertFalse(state.blocked());
        assertEquals(BuildDecisions.Storage.BROWSER, state.choice().storage());
        assertTrue(state.prompt().contains("storage=BROWSER"));
        assertFalse(state.prompt().contains("Supabase is connected: use it"));
        decisions.requireStarter(id, "vite-react");
        assertThrows(WorkflowStageException.class, () -> decisions.requireStarter(id, "mern"));
    }

    @Test void accountsCannotBeImplementedByBrowserOrMemory() {
        for (var storage : List.of("NONE", "BROWSER")) {
            assertThrows(WorkflowStageException.class, () -> decisions.approve(answers(storage, "ACCOUNTS"), decisions.available(id)));
        }
        assertEquals(BuildDecisions.Auth.ACCOUNTS,
                decisions.approve(answers("MERN", "ACCOUNTS"), decisions.available(id)).auth());
    }

    @Test void unsupportedAndFreeformChoicesNeverActivateAProvider() {
        assertThrows(WorkflowStageException.class, () -> decisions.approve(answers("saved_database", "NONE"), decisions.available(id)));
        var custom = answers("SUPABASE", "NONE");
        custom.getFirst().setOtherText("Cloud, please");
        assertThrows(WorkflowStageException.class, () -> decisions.approve(custom, decisions.available(id)));
        assertThrows(WorkflowStageException.class, () -> decisions.approve(answers("SUPABASE", "NONE"), decisions.available(id)));
    }

    @Test void disconnectedOrChangedConnectionInvalidatesApproval() {
        var connection = connect();
        approve(BuildDecisions.Storage.SUPABASE, BuildDecisions.Auth.ACCOUNTS);
        assertFalse(decisions.state(id).blocked());
        when(connection.getUpdatedAt()).thenReturn(Instant.parse("2026-10-11T12:00:00Z"));
        assertTrue(decisions.state(id).blocked());
        when(connections.capability(id)).thenReturn(Optional.empty());
        assertTrue(decisions.state(id).blocked());
    }

    @Test void configurationDoesNotClaimDatabaseReadinessAndDisablementIsEnforced() {
        approve(BuildDecisions.Storage.MERN, BuildDecisions.Auth.NONE);
        assertTrue(decisions.state(id).prompt().contains("still require validation"));
        decisions.requireStarter(id, "mern");
        assertThrows(WorkflowStageException.class, () -> decisions.requireStarter(id, "vite-react"));
        properties.getSandbox().setMernEnabled(false);
        assertTrue(decisions.state(id).blocked());
        assertThrows(WorkflowStageException.class, () -> decisions.requireStarter(id, "mern"));
    }

    @Test void legacyAndMalformedApprovalsRemainBlockedRatherThanGuessed() {
        var session = RequirementSession.builder().status(RequirementStatus.APPROVED).build();
        when(sessions.findByProjectId(id)).thenReturn(Optional.of(session));
        assertTrue(decisions.state(id).blocked());
        session.setBuildDecisionJson("{broken");
        assertTrue(decisions.state(id).blocked());
        session.setBuildDecisionJson("{\"version\":2,\"storage\":\"BROWSER\",\"auth\":\"NONE\"}");
        assertTrue(decisions.state(id).blocked());
    }

    @Test void finalGateRejectsWrongStackAndUnapprovedSdk() {
        LocalProject project = LocalProject.create();
        when(sessions.findByProjectId(project.projectId)).thenAnswer(call -> sessions.findByProjectId(id));
        approve(BuildDecisions.Storage.MERN, BuildDecisions.Auth.ACCOUNTS);
        project.write("package.json", "{}");
        assertNotNull(decisions.validate(project.projectId, project.files, "."));
        project.write("client/package.json", "{}");
        project.write("server/package.json", "{}");
        assertNull(decisions.validate(project.projectId, project.files, "."));
        project.write("client/package.json", "{\"dependencies\":{\"@supabase/supabase-js\":\"2.0.0\"}}");
        assertTrue(decisions.validate(project.projectId, project.files, ".").contains("Supabase SDK"));
    }

    @Test void ordinaryLocalHostConfigurationIsNotAProductionCapability() {
        properties.getSandbox().setRequired(false);
        assertFalse(decisions.available(id).mernConfigured());
        properties.getSandbox().setAllowHostExecution(true);
        assertTrue(decisions.available(id).mernConfigured());
    }

    private SupabaseConnectionRepository.Capability connect() {
        var connection = mock(SupabaseConnectionRepository.Capability.class);
        when(connection.getProjectRef()).thenReturn("test-reference");
        when(connection.getMode()).thenReturn(com.code.pal.project.supabase.SupabaseConnection.Mode.CLOUD);
        when(connection.getUpdatedAt()).thenReturn(Instant.parse("2026-10-11T00:00:00Z"));
        when(connections.capability(id)).thenReturn(Optional.of(connection));
        return connection;
    }
    private void approve(BuildDecisions.Storage storage, BuildDecisions.Auth auth) {
        var choice = decisions.approve(answers(storage.name(), auth.name()), decisions.available(id));
        when(sessions.findByProjectId(id)).thenReturn(Optional.of(RequirementSession.builder()
                .status(RequirementStatus.APPROVED).buildDecisionJson(decisions.encode(choice)).build()));
    }
    static List<RequirementQuestion> answers(String storage, String auth) {
        return List.of(RequirementQuestion.builder().questionKey(BuildDecisions.STORAGE_KEY).selectedOption(storage).build(),
                RequirementQuestion.builder().questionKey(BuildDecisions.AUTH_KEY).selectedOption(auth).build());
    }
}
