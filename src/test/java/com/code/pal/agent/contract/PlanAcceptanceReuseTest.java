package com.code.pal.agent.contract;

import com.code.pal.agent.llm.LlmClient;
import com.code.pal.agent.route.RunSize;
import com.code.pal.agent.route.SizeTier;
import com.code.pal.agent.validation.BehaviorCriteria;
import com.code.pal.agent.validation.BehaviorCriteria.BrowserAction;
import com.code.pal.agent.validation.BehaviorCriteria.BrowserKind;
import com.code.pal.project.domain.ProjectType;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class PlanAcceptanceReuseTest {
    // Wiring control only: fixture validation is mocked. This does not certify a working counter UI.
    @Test
    void acceptedContractKeepsIncrementThenResetValueAcceptanceWithoutAnotherPlanner() {
        var fixture = new ContractCoordinatorTest();
        var actions = List.of(new BrowserAction("increment", BrowserKind.CLICK, "#increment", null),
                new BrowserAction("increment-value", BrowserKind.ASSERT_TEXT, "#count", "1"),
                new BrowserAction("reset", BrowserKind.CLICK, "#reset", null),
                new BrowserAction("reset-value", BrowserKind.ASSERT_TEXT, "#count", "0"));
        var criteria = new BehaviorCriteria(1, actions, List.of(), false, false);
        var base = fixture.c;
        var unit = new BuildContract.Unit("page", "Counter reset", List.of("index.html"), List.of(), List.of(),
                actions.stream().map(BrowserAction::id).toList());
        var contract = new BuildContract(1, base.id(), base.stack(), base.storage(), base.auth(), base.limits(), base.theme(),
                base.routes(), base.types(), base.models(), base.api(), base.assets(), Set.of(), List.of(unit), criteria);
        var run = fixture.run(ProjectType.BUILD);
        var ctx = fixture.context(run);
        var approved = new ContractStore.Stored(run.getId(), ctx.projectId(), contract, "hash", fixture.binding, "APPROVED", "EXPLICIT_PLAN");
        when(fixture.store.reusable(eq(ctx.projectId()), anyString(), any())).thenReturn(approved);
        when(fixture.store.attach(eq(run), eq(approved), any())).thenReturn(approved);
        var calls = new AtomicInteger();
        var result = fixture.coordinator.execute(ctx, run, RunSize.of(SizeTier.M, List.of(), fixture.props), "build the approved plan", "map", request -> {
            calls.incrementAndGet();
            var input = fixture.mapper.readValue(request.messages().get(1).content(), UnitGenerator.Input.class);
            assertEquals(criteria, input.contract().acceptance());
            var output = new UnitGenerator.Output(List.of(new UnitGenerator.Delta("index.html", input.snapshot().get("index.html").version(),
                    "<main><output id='count'>0</output><button id='reset'>Reset</button></main>")));
            return new LlmClient.LlmResult(fixture.mapper.writeValueAsString(output), null, List.of(), null, "stop");
        }, () -> {}, ignored -> {});
        assertEquals(1, calls.get());
        assertEquals(criteria, result.contract().acceptance());
        verify(fixture.criteria).install(run.getId(), ctx.projectId(), fixture.binding.requirementsProjection(), fixture.binding.requirementsRevision(), criteria);
        verify(fixture.store, never()).create(any(), any(), any(), any());
    }
}
