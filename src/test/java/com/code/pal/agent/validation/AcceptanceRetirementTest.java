package com.code.pal.agent.validation;

import com.code.pal.agent.tool.AgentContext;
import com.code.pal.project.brief.ApprovedProjectMemory;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import tools.jackson.databind.ObjectMapper;

import java.util.Arrays;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.verifyNoInteractions;

class AcceptanceRetirementTest {
    @Test
    void modelReasonsCannotRetireAnyBlockingProvenanceOrWriteRetirementRows() {
        for (var provenance : Arrays.asList(null, BehaviorCriteria.Provenance.PLATFORM)) {
            var database = mock(JdbcTemplate.class);
            var store = spy(new RunBehaviorCriteria(database, new ObjectMapper(), mock(ApprovedProjectMemory.class)));
            var context = mock(AgentContext.class);
            var criteria = new BehaviorCriteria(1, List.of(new BehaviorCriteria.BrowserAction("heading",
                    BehaviorCriteria.BrowserKind.ASSERT_TEXT, "h1", "Bakery")), List.of(), false, false, provenance);
            doReturn(null).when(store).current(context);
            doReturn(criteria).when(store).regression(context);
            assertThrows(IllegalArgumentException.class,
                    () -> store.retire(context, List.of("heading"), "The user requested this change"));
            verifyNoInteractions(database);
        }
    }
}
