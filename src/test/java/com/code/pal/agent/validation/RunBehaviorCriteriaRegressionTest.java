package com.code.pal.agent.validation;

import com.code.pal.agent.contract.ContractFixtures;
import com.code.pal.agent.tool.AgentContext;
import com.code.pal.project.brief.ApprovedProjectMemory;
import com.code.pal.project.domain.ProjectType;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import tools.jackson.databind.ObjectMapper;

import java.sql.ResultSet;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class RunBehaviorCriteriaRegressionTest {
    private final ObjectMapper mapper = new ObjectMapper();
    private final JdbcTemplate jdbc = mock(JdbcTemplate.class);
    private final ApprovedProjectMemory memory = mock(ApprovedProjectMemory.class);
    private final UUID project = UUID.randomUUID(), projection = UUID.randomUUID();
    private final AgentContext edit = new AgentContext(project, 7L, ProjectType.BUILD, "stage", null, null,
            UUID.randomUUID(), new com.code.pal.agent.plan.AgentPlanState(), null);

    /** The last completed build stored {@code saved}; nothing was retired since. */
    private RunBehaviorCriteria saved(BehaviorCriteria saved) throws Exception {
        when(memory.current(project)).thenReturn(new ApprovedProjectMemory.Approved(projection, 2, "{}", "brief", null));
        String json = mapper.writeValueAsString(saved);
        when(jdbc.query(contains("from agent_behavior_criteria c join agent_runs"), any(RowMapper.class), any(), any()))
                .thenAnswer(call -> {
                    ResultSet row = mock(ResultSet.class);
                    when(row.getString(1)).thenReturn(json);
                    when(row.getObject(2, OffsetDateTime.class)).thenReturn(OffsetDateTime.now());
                    when(row.getObject(3, UUID.class)).thenReturn(projection);
                    when(row.getLong(4)).thenReturn(2L);
                    return List.of(((RowMapper<?>) call.getArgument(1)).mapRow(row, 0));
                });
        when(jdbc.queryForList(anyString(), eq(String.class), any(), any(), any())).thenReturn(List.of());
        return new RunBehaviorCriteria(jdbc, mapper, memory);
    }

    @Test
    void aSavedAccountsAppKeepsItsApiAndSignedInChecksForAnEdit() throws Exception {
        // 2026-10-29: edits of a saved full-stack app inherited browser checks only, so they ran signed out.
        var contract = ContractFixtures.accounts().acceptance();
        var inherited = saved(contract).regression(edit);
        assertEquals(contract.browser(), inherited.browser());
        assertEquals(contract.api(), inherited.api());
        assertTrue(inherited.authRequired());
        assertEquals(contract.crudRequired(), inherited.crudRequired());
    }

    @Test
    void advisoryModelSpecChecksStayBrowserOnly() throws Exception {
        var advisory = new BehaviorCriteria(1, List.of(new BehaviorCriteria.BrowserAction("menu",
                BehaviorCriteria.BrowserKind.ASSERT_TEXT, "#menu", "Menu")), List.of(), false, false,
                BehaviorCriteria.Provenance.MODEL_SPEC);
        var inherited = saved(advisory).regression(edit);
        assertTrue(inherited.modelSpec());
        assertTrue(inherited.api().isEmpty());
        assertFalse(inherited.authRequired());
    }
}
