package com.code.pal.agent.contract;

import com.code.pal.agent.AgentRunner;
import com.code.pal.agent.change.SequentialCheckpoints;
import com.code.pal.agent.config.AgentProperties;
import com.code.pal.agent.lease.LeaseLostException;
import com.code.pal.agent.lease.RunFence;
import com.code.pal.agent.llm.LlmClient;
import com.code.pal.agent.llm.RetryableLlmException;
import com.code.pal.agent.runtime.AgentRuntimeContext;
import com.code.pal.agent.usage.DailyTokenBudget;
import com.code.pal.agent.workspace.ProjectFiles;
import com.code.pal.testsupport.CodexJpaFixture;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.PlatformTransactionManager;
import tools.jackson.databind.ObjectMapper;

import java.time.Duration;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Function;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/** Opt-in disposable PostgreSQL only. Faults bypass compensation to model worker loss; no process is killed. */
@EnabledIfSystemProperty(named = "codepal.disposable.it", matches = "true")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class RepairRecoveryIT {
    private AnnotationConfigApplicationContext app;
    private ContractStoreIT helper;

    @Configuration
    @Import({UnitOutputCheckpointIT.Services.class, DailyTokenBudget.class})
    static class Services {
        /** Real audit rows: crash cuts inside the audit write must leave them consistent. */
        @org.springframework.context.annotation.Bean
        org.springframework.data.jpa.repository.support.JpaRepositoryFactoryBean<com.code.pal.agent.change.FileChangeRepository,
                com.code.pal.agent.change.FileChange, UUID> fileChanges(jakarta.persistence.EntityManagerFactory factory) {
            var bean = new org.springframework.data.jpa.repository.support.JpaRepositoryFactoryBean<com.code.pal.agent.change.FileChangeRepository,
                    com.code.pal.agent.change.FileChange, UUID>(com.code.pal.agent.change.FileChangeRepository.class);
            bean.setEntityManager(org.springframework.orm.jpa.SharedEntityManagerCreator.createSharedEntityManager(factory));
            bean.setTransactionManager("transactionManager");
            return bean;
        }
    }

    @BeforeAll
    void open() {
        app = CodexJpaFixture.open(Services.class);
        helper = new ContractStoreIT();
        helper.app = app;
        helper.jdbc = app.getBean(JdbcTemplate.class);
        helper.store = app.getBean(ContractStore.class);
        helper.mapper = app.getBean(ObjectMapper.class);
        helper.files = app.getBean(ProjectFiles.class);
    }

    @AfterAll
    void close() {
        if (app != null) {
            app.close();
        }
    }

    @ParameterizedTest
    @EnumSource(RepairRecoveryFixture.Cut.class)
    void workerLossAtEveryReceiptFileAndAuditBoundaryNeverRepeatsAModelResponseOrCharge(RepairRecoveryFixture.Cut cut) {
        var f = new RepairRecoveryFixture(app, helper);
        f.cut = cut;
        try (var bound = RunFence.bind(f.fence)) {
            assertThrows(RepairRecoveryFixture.ProcessLoss.class, f::repair);
        }
        assertTrue(f.fired, "The requested fault must actually be reached");
        int responses = cut == RepairRecoveryFixture.Cut.BEFORE_RESERVE || cut == RepairRecoveryFixture.Cut.AFTER_RESERVE ? 0 : 1;
        assertEquals(responses, f.modelResponses.get());
        assertEquals(responses, f.ledgerCount());
        assertEquals(15L * responses, f.chargedTokens());
        var progress = f.checkpoints.progress(f.run.getId());
        String oldStage = f.ctx.root();
        f.restartAndRecover();
        assertNotEquals(oldStage, f.ctx.root(), "A new lease epoch must use a fresh stage");
        boolean prepared = switch (cut) {
            case AFTER_PREPARE, AFTER_FIRST_FILE, DURING_AUDIT, BEFORE_APPLIED, AFTER_APPLIED -> true;
            default -> false;
        };
        assertEquals(prepared ? "<h1>Fixed</h1>" : "<h1>Broken</h1>", f.body("index.html"));
        assertEquals(prepared ? "body { color: blue; }" : "body { color: red; }", f.body("styles.css"));
        if (prepared) {
            assertEquals("APPLIED", f.receipt().state().rounds().getLast().state());
        }
        assertEquals(responses, f.modelResponses.get(), "Recovery must not ask a model to repeat the repair");
        assertEquals(responses, f.ledgerCount(), "Recovery must not create another physical ledger attempt");
        assertEquals(15L * responses, f.chargedTokens());
        assertEquals(progress.modelCalls(), f.checkpoints.progress(f.run.getId()).modelCalls());
        assertTrue(f.changes.hasUnvalidatedChanges(f.ctx), "Recovery is output evidence, never a validation certificate");
        assertEquals("GENERATED", helper.jdbc.queryForObject("select state from agent_unit_outputs where run_id=?",
                String.class, f.run.getId()));
        assertEquals("before", f.files.readText(f.run.getProjectId(), ".", "index.html"), "Live code is untouched");
        assertEquals(2, helper.jdbc.queryForObject("select count(distinct path) from agent_file_changes where run_id=? "
                        + "and ((path='index.html' and after_version=?) or (path='styles.css' and after_version=?))",
                Integer.class, f.run.getId(), f.version(f.ctx.root(), "index.html"), f.version(f.ctx.root(), "styles.css")),
                "Replayed after hashes regain a real durable audit");
    }

    @ParameterizedTest
    @ValueSource(strings = {"STOP", "FAILED", "DONE"})
    void stoppedOrTerminalRunCannotPrepareApplyOrRequestAnotherRepair(String state) {
        var f = new RepairRecoveryFixture(app, helper);
        f.cut = RepairRecoveryFixture.Cut.AFTER_PREPARE;
        try (var bound = RunFence.bind(f.fence)) {
            assertThrows(RepairRecoveryFixture.ProcessLoss.class, f::repair);
            if (state.equals("STOP")) {
                helper.jdbc.update("update agent_runs set cancel_requested_at=clock_timestamp() where id=?", f.run.getId());
            } else {
                helper.jdbc.update("update agent_runs set status=? where id=?", state, f.run.getId());
            }
            assertThrows(IllegalStateException.class, () -> f.repairs.recover(f.ctx, f.run, f.spec.contract));
            assertThrows(IllegalStateException.class, f::repair);
        }
        assertEquals("PREPARED", f.receipt().state().rounds().getLast().state());
        assertEquals("<h1>Broken</h1>", f.body("index.html"));
        assertEquals(1, f.modelResponses.get());
        assertEquals(1, f.ledgerCount());
    }

    @Test
    void leaseTakeoverRejectsOldWorkerAndNewWorkerRecoversPreparedRepair() {
        var f = new RepairRecoveryFixture(app, helper);
        f.cut = RepairRecoveryFixture.Cut.AFTER_PREPARE;
        RunFence old = f.fence;
        try (var bound = RunFence.bind(old)) {
            assertThrows(RepairRecoveryFixture.ProcessLoss.class, f::repair);
        }
        f.takeover();
        try (var bound = RunFence.bind(old)) {
            assertThrows(LeaseLostException.class, () -> f.repairs.recover(f.ctx, f.run, f.spec.contract));
            assertThrows(LeaseLostException.class, f::repair);
            assertThrows(LeaseLostException.class, () -> f.checkpoints.refundModelIteration(f.run, UUID.randomUUID(), 1));
        }
        try (var bound = RunFence.bind(f.fence)) {
            f.repairs.recover(f.ctx, f.run, f.spec.contract);
        }
        assertEquals("<h1>Fixed</h1>", f.body("index.html"));
        assertEquals("APPLIED", f.receipt().state().rounds().getLast().state());
        assertEquals(1, f.modelResponses.get());
        assertEquals(1, f.ledgerCount());
    }

    @ParameterizedTest
    @ValueSource(strings = {"STOP", "TAKEOVER"})
    void guardedPrepareRejectsStopOrLeaseTakeoverAfterModelResponse(String event) {
        var f = new RepairRecoveryFixture(app, helper);
        var io = new AtomicInteger();
        Class<? extends RuntimeException> expected = event.equals("STOP") ? IllegalStateException.class : LeaseLostException.class;
        try (var bound = RunFence.bind(f.fence)) {
            assertThrows(expected, () -> f.repairs.repair(f.ctx, f.run, f.spec.contract,
                    f.spec.contract.units(), f.report(), f::model, () -> {
                        if (io.incrementAndGet() == 2) {
                            if (event.equals("STOP")) {
                                helper.jdbc.update("update agent_runs set cancel_requested_at=clock_timestamp() where id=?", f.run.getId());
                            } else {
                                f.takeover();
                            }
                        }
                    }));
        }
        assertEquals(2, io.get(), "Loss occurs after the model returns and before prepare takes its guard");
        assertEquals("RESERVED", f.receipt().state().rounds().getLast().state());
        assertEquals("<h1>Broken</h1>", f.body("index.html"));
        assertEquals(1, f.modelResponses.get());
        assertEquals(1, f.ledgerCount());
        assertEquals(15, f.chargedTokens());
    }

    @Test
    void bothRepairRoundsRemainSpentAcrossWorkersAndTheThirdNeverCallsAModel() {
        var f = new RepairRecoveryFixture(app, helper);
        try (var bound = RunFence.bind(f.fence)) {
            f.repair();
        }
        f.restartAndRecover();
        try (var bound = RunFence.bind(f.fence)) {
            f.repair();
        }
        f.restartAndRecover();
        try (var bound = RunFence.bind(f.fence)) {
            var failure = assertThrows(IllegalStateException.class, f::repair);
            assertTrue(failure.getMessage().contains("Repair budget exhausted"));
        }
        assertEquals(2, f.receipt().state().rounds().size());
        assertEquals("<h1>Final</h1>", f.body("index.html"));
        assertEquals("body { color: green; }", f.body("styles.css"));
        assertEquals(2, f.modelResponses.get());
        assertEquals(2, f.ledgerCount());
        assertEquals(30, f.chargedTokens());
        assertEquals(2, f.checkpoints.progress(f.run.getId()).modelCalls());
    }

    @Test
    void reservedButUnpreparedResponseStaysSpentAndCannotBypassTwoRoundLimit() {
        var f = new RepairRecoveryFixture(app, helper);
        f.cut = RepairRecoveryFixture.Cut.BEFORE_PREPARE;
        try (var bound = RunFence.bind(f.fence)) {
            assertThrows(RepairRecoveryFixture.ProcessLoss.class, f::repair);
        }
        f.restartAndRecover();
        assertEquals("RESERVED", f.receipt().state().rounds().getFirst().state());
        try (var bound = RunFence.bind(f.fence)) {
            f.repair();
            assertThrows(IllegalStateException.class, f::repair);
        }
        assertEquals(2, f.modelResponses.get());
        assertEquals(2, f.ledgerCount());
        assertEquals(30, f.chargedTokens());
    }

    @Test
    void unknownMixedFileIsQuarantinedBeforeAnyOtherTargetIsApplied() {
        var f = new RepairRecoveryFixture(app, helper);
        f.cut = RepairRecoveryFixture.Cut.AFTER_PREPARE;
        try (var bound = RunFence.bind(f.fence)) {
            assertThrows(RepairRecoveryFixture.ProcessLoss.class, f::repair);
            f.files.writeText(f.run.getProjectId(), f.ctx.root(), "styles.css", "foreign edit");
            assertThrows(IllegalStateException.class, () -> f.repairs.recover(f.ctx, f.run, f.spec.contract));
            assertThrows(IllegalStateException.class, () -> f.repairs.recover(f.ctx, f.run, f.spec.contract));
        }
        assertEquals("QUARANTINED", f.receipt().state().rounds().getLast().state());
        assertEquals("<h1>Broken</h1>", f.body("index.html"));
        assertEquals("foreign edit", f.body("styles.css"));
        assertEquals(1, f.modelResponses.get());
    }

    @Test
    void refundRetryAfterServiceRestartAndLeaseTakeoverNeverChangesLedgerOrRefundsTwice() {
        var f = new RepairRecoveryFixture(app, helper);
        UUID logical = UUID.randomUUID();
        try (var bound = RunFence.bind(f.fence)) {
            assertTrue(f.checkpoints.reserveModelIteration(f.run));
            f.charge(logical, new LlmClient.Usage(0, 0), "FAILED");
            assertTrue(f.checkpoints.refundModelIteration(f.run, logical, 1));
        }
        f.takeover();
        var restarted = new SequentialCheckpoints(helper.jdbc, app.getBean(PlatformTransactionManager.class), helper.mapper,
                f.files, f.leases, app.getBean(AgentProperties.class));
        try (var bound = RunFence.bind(f.fence)) {
            assertFalse(restarted.refundModelIteration(f.run, logical, 1));
        }
        assertEquals(0, restarted.progress(f.run.getId()).modelCalls());
        assertEquals(0, restarted.progress(f.run.getId()).iterations());
        assertEquals(1, f.ledgerCount());
        assertEquals(0, f.chargedTokens());
        assertEquals(1, helper.jdbc.queryForObject("select count(*) from agent_model_call_refunds where run_id=?", Integer.class, f.run.getId()));
    }

    @Test
    void refundWithoutAReservedAttemptMustNotEraseAnUnrelatedSuccessfulCall() {
        var f = new RepairRecoveryFixture(app, helper);
        try (var bound = RunFence.bind(f.fence)) {
            assertTrue(f.checkpoints.reserveModelIteration(f.run));
            f.charge(UUID.randomUUID(), new LlmClient.Usage(12, 3), "OK");
            assertFalse(f.checkpoints.refundModelIteration(f.run, UUID.randomUUID(), 1), "No matching reserved failed attempt exists");
        }
        assertEquals(1, f.checkpoints.progress(f.run.getId()).modelCalls());
        assertEquals(15, f.chargedTokens());
    }

    @Test
    void stoppedRunCannotRefundASlotEvenWhileItsLeaseIsStillLive() {
        var f = new RepairRecoveryFixture(app, helper);
        try (var bound = RunFence.bind(f.fence)) {
            assertTrue(f.checkpoints.reserveModelIteration(f.run));
            helper.jdbc.update("update agent_runs set cancel_requested_at=clock_timestamp() where id=?", f.run.getId());
            assertFalse(f.checkpoints.refundModelIteration(f.run, UUID.randomUUID(), 1), "Stop is not a failover/refund admission");
        }
        assertEquals(1, f.checkpoints.progress(f.run.getId()).modelCalls());
    }

    @Test
    void runnerMustNotRefundOrFailOverAnUnknownCompletionTransportFailure() {
        var f = new RepairRecoveryFixture(app, helper);
        var preferred = new AgentProperties.ModelSelection("mistral", "synthetic-first");
        var fallback = new AgentProperties.ModelSelection("nvidia", "synthetic-second");
        var props = mock(AgentProperties.class);
        when(props.freeStructuredRoutes()).thenReturn(List.of(preferred, fallback));
        // Invoke the real wired adapter, without constructing a runner or starting a backend.
        var runner = mock(AgentRunner.class, CALLS_REAL_METHODS);
        ReflectionTestUtils.setField(runner, "props", props);
        ReflectionTestUtils.setField(runner, "checkpoints", f.checkpoints);
        var runtime = new AgentRuntimeContext(f.run.getId(), f.run.getProjectId(), 7L, "Synthetic repair", List.of());
        var invocations = new AtomicInteger();
        Function<LlmClient.LlmRequest, LlmClient.LlmResult> attempt = request -> {
            assertTrue(f.checkpoints.reserveModelIteration(f.run));
            runtime.beginIteration();
            runtime.recordModelCall();
            if (invocations.incrementAndGet() == 1) {
                f.charge(UUID.randomUUID(), null, "FAILED");
                throw new RetryableLlmException("Connection ended before a published text delta; completion unknown", 0);
            }
            return new LlmClient.LlmResult("must not reach fallback", null, List.of(), null, "stop");
        };
        var request = new LlmClient.LlmRequest(preferred.provider(), preferred.model(),
                List.of(LlmClient.LlmMessage.user("Synthetic")), List.of(), 0.1, Duration.ofSeconds(30));
        try (var bound = RunFence.bind(f.fence)) {
            assertThrows(RetryableLlmException.class, () -> ReflectionTestUtils.invokeMethod(runner, "structuredCall",
                    f.run, runtime, System.nanoTime() + Duration.ofSeconds(30).toNanos(), request, attempt));
        }
        assertEquals(1, invocations.get());
        assertEquals(1, f.checkpoints.progress(f.run.getId()).modelCalls());
        assertEquals(0, helper.jdbc.queryForObject("select count(*) from agent_model_call_refunds where run_id=?", Integer.class, f.run.getId()));
        assertEquals(1, f.ledgerCount());
        assertFalse(helper.jdbc.queryForObject("select usage_known from agent_model_calls where run_id=?", Boolean.class, f.run.getId()));
        assertEquals(150, helper.jdbc.queryForObject("select unknown_charge_tokens from ai_token_attempts where run_id=?",
                Long.class, f.run.getId()), "UNKNOWN usage remains charged independently of model-slot accounting");
    }

    @Test
    void preparedRepairWaitsUntilEveryOwningFileHasBeenReplayed() {
        var f = new RepairRecoveryFixture(app, helper);
        f.cut = RepairRecoveryFixture.Cut.AFTER_PREPARE;
        try (var bound = RunFence.bind(f.fence)) {
            assertThrows(RepairRecoveryFixture.ProcessLoss.class, f::repair);
            f.repairs.recoverReady(f.ctx, f.run, f.spec.contract, Set.of("index.html"));
            assertEquals("PREPARED", f.receipt().state().rounds().getLast().state());
            assertEquals("<h1>Broken</h1>", f.body("index.html"));
            f.repairs.recover(f.ctx, f.run, f.spec.contract);
        }
        assertEquals("APPLIED", f.receipt().state().rounds().getLast().state());
        assertEquals(1, f.modelResponses.get());
    }
}
