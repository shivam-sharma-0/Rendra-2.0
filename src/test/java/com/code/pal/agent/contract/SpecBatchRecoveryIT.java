package com.code.pal.agent.contract;

import com.code.pal.agent.config.AgentProperties;
import com.code.pal.agent.context.ProjectContextTrace;
import com.code.pal.agent.domain.AgentRun;
import com.code.pal.agent.lease.ProjectLeaseService;
import com.code.pal.agent.lease.RunFence;
import com.code.pal.agent.plan.AgentPlanState;
import com.code.pal.agent.tool.AgentContext;
import com.code.pal.agent.workspace.ProjectFiles;
import com.code.pal.message.dto.CreateMessageRequest;
import com.code.pal.message.domain.Message;
import com.code.pal.message.domain.MessageStatus;
import com.code.pal.project.domain.ProjectType;
import com.code.pal.message.repository.MessageRepository;
import com.code.pal.message.service.ConversationProposals;
import com.code.pal.testsupport.CodexJpaFixture;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.ObjectMapper;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

@EnabledIfSystemProperty(named = "codepal.disposable.it", matches = "true")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class SpecBatchRecoveryIT {
    private AnnotationConfigApplicationContext app;
    private ContractStoreIT helper;
    private UnitOutputCheckpoints outputs;
    private ProjectLeaseService leases;

    @BeforeAll
    void open() {
        app = CodexJpaFixture.open(UnitOutputCheckpointIT.Services.class);
        helper = new ContractStoreIT();
        helper.app = app;
        helper.jdbc = app.getBean(JdbcTemplate.class);
        helper.store = app.getBean(ContractStore.class);
        helper.mapper = app.getBean(ObjectMapper.class);
        helper.files = app.getBean(ProjectFiles.class);
        outputs = app.getBean(UnitOutputCheckpoints.class);
        leases = app.getBean(ProjectLeaseService.class);
    }

    @AfterAll
    void close() { if (app != null) app.close(); }

    private final class Fixture {
        final SpecStoreFixture spec = new SpecStoreFixture(helper);
        final AgentRun run = spec.base.run(ProjectType.BUILD);
        final RunFence fence;
        final AgentContext ctx;

        Fixture() {
            spec.base.done();
            run.setRequestFingerprint("b".repeat(64));
            helper.jdbc.update("update agent_runs set request_fingerprint=? where id=?", run.getRequestFingerprint(), run.getId());
            fence = leases.claim(spec.base.project, run.getId(), UUID.randomUUID());
            assertNotNull(fence);
            ctx = new AgentContext(spec.base.project, 7L, ProjectType.BUILD, ".codepal/spec-batch-stage", null,
                    new ProjectContextTrace(), run.getId(), new AgentPlanState(), helper.files);
            helper.files.writeText(ctx.projectId(), ctx.root(), "index.html", "before");
            try (var bound = RunFence.bind(fence)) {
                helper.store.create(run, spec.contract, spec.basis, spec.contract.limits());
            }
        }

        List<UnitGenerator.Input> inputs() {
            var snapshot = Map.of("index.html", new UnitGenerator.FileView(helper.files.version(ctx.projectId(), ctx.root(), "index.html"), null),
                    "styles.css", new UnitGenerator.FileView(helper.files.version(ctx.projectId(), ctx.root(), "styles.css"), null));
            return spec.contract.units().stream().map(unit -> new UnitGenerator.Input(spec.contract, unit, "Build bakery", "Approved", snapshot, "")).toList();
        }

        List<UnitGenerator.Output> proposals() {
            return inputs().stream().map(input -> new UnitGenerator.Output(input.unit().ownedPaths().stream()
                    .map(path -> new UnitGenerator.Delta(path, input.snapshot().get(path).version(), path.endsWith("html") ? "<h1>Solar Bakery</h1>" : "body {}"))
                    .toList())).toList();
        }

        void save() {
            try (var bound = RunFence.bind(fence)) { outputs.saveBatch(run, inputs(), proposals()); }
        }
    }

    @Test
    void workerRestartReusesTheWholeBatchAcrossPartialAndCompleteMerge() {
        var fixture = new Fixture();
        fixture.save();
        assertEquals(1, helper.jdbc.queryForObject("select count(*) from agent_unit_outputs where run_id=?", Integer.class, fixture.run.getId()));
        helper.files.writeText(fixture.ctx.projectId(), fixture.ctx.root(), "index.html", "<h1>Solar Bakery</h1>");
        var restarted = new UnitOutputCheckpoints(helper.jdbc, app.getBean(PlatformTransactionManager.class), helper.mapper, helper.store,
                leases, helper.files, app.getBean(AgentProperties.class));
        var replay = restarted.reuseBatch(fixture.run, fixture.inputs(), fixture.ctx);
        assertEquals(2, replay.size());
        assertTrue(replay.getFirst().files().isEmpty());
        assertEquals("styles.css", replay.getLast().files().getFirst().path());
        helper.files.writeText(fixture.ctx.projectId(), fixture.ctx.root(), "styles.css", "body {}");
        assertTrue(restarted.reuseBatch(fixture.run, fixture.inputs(), fixture.ctx).stream().allMatch(output -> output.files().isEmpty()));
        assertEquals("GENERATED", helper.jdbc.queryForObject("select state from agent_unit_outputs where run_id=?", String.class, fixture.run.getId()));
    }

    @Test
    void incompleteBatchAndStopCannotPersistAReceipt() {
        var fixture = new Fixture();
        try (var bound = RunFence.bind(fixture.fence)) {
            assertThrows(IllegalArgumentException.class, () -> outputs.saveBatch(fixture.run, fixture.inputs(), fixture.proposals().subList(0, 1)));
            helper.jdbc.update("update agent_runs set cancel_requested_at=clock_timestamp() where id=?", fixture.run.getId());
            assertThrows(IllegalStateException.class, () -> outputs.saveBatch(fixture.run, fixture.inputs(), fixture.proposals()));
        }
        assertEquals(0, helper.jdbc.queryForObject("select count(*) from agent_unit_outputs where run_id=?", Integer.class, fixture.run.getId()));
    }

    @Test
    void changedSpecAndForeignStageEditsCannotReuseAReceipt() {
        var fixture = new Fixture();
        fixture.save();
        helper.files.writeText(fixture.ctx.projectId(), fixture.ctx.root(), "styles.css", "foreign edit");
        assertThrows(IllegalStateException.class, () -> outputs.reuseBatch(fixture.run, fixture.inputs(), fixture.ctx));
        helper.jdbc.update("update requirement_projections set payload_json=replace(payload_json,'Solar Bakery','Changed Bakery') where id=?",
                fixture.spec.base.projection);
        assertThrows(IllegalStateException.class, () -> outputs.reuseBatch(fixture.run, fixture.inputs(), fixture.ctx));
    }

    @Test
    void corruptedOrLegacyPartialV2ReceiptsCannotAuthorizeResume() {
        var fixture = new Fixture();
        fixture.save();
        assertTrue(outputs.canResume(fixture.run));
        helper.jdbc.update("update agent_unit_outputs set output_json=output_json || ' ' where run_id=?", fixture.run.getId());
        assertThrows(IllegalStateException.class, () -> outputs.reuseBatch(fixture.run, fixture.inputs(), fixture.ctx));
        assertThrows(IllegalStateException.class, () -> outputs.canResume(fixture.run));
        var partial = new Fixture();
        partial.save();
        helper.jdbc.update("update agent_unit_outputs set unit_id='page' where run_id=?", partial.run.getId());
        assertFalse(outputs.canResume(partial.run), "A legacy per-unit row is not a whole-batch proof");
    }

    @Test
    void stopAfterGenerationCannotMarkTheReceiptVerified() {
        var fixture = new Fixture();
        fixture.save();
        helper.jdbc.update("update agent_runs set cancel_requested_at=clock_timestamp() where id=?", fixture.run.getId());
        try (var bound = RunFence.bind(fixture.fence)) {
            assertThrows(IllegalStateException.class, () -> outputs.verified(fixture.run));
        }
        assertEquals("GENERATED", helper.jdbc.queryForObject("select state from agent_unit_outputs where run_id=?", String.class, fixture.run.getId()));
    }

    @ParameterizedTest
    @ValueSource(booleans = {true, false})
    void exactV2OfferUsesTheReviewedContractForBuildThisAndHaan(boolean button) {
        var fixture = new SpecStoreFixture(helper);
        var draft = helper.store.create(fixture.base.plan, fixture.contract, fixture.basis, fixture.contract.limits());
        fixture.base.done();
        helper.store.approve(7L, fixture.base.project, fixture.base.plan.getId(), fixture.contract.id(), draft.hash());
        String key = UUID.randomUUID().toString().replace("-", "");
        var messages = app.getBean(MessageRepository.class);
        var user = messages.saveAndFlush(Message.user(fixture.base.project, "u_" + key, "Build the bakery", ProjectType.PLAN, 7L));
        var offer = Message.assistantPlaceholder(fixture.base.project, "a_" + key, user.getId(), ProjectType.PLAN);
        offer.setStatus(MessageStatus.COMPLETE);
        offer.setContent("The bakery plan is ready.");
        offer.setSuggestedBuildText(user.getContent());
        offer.setProposalPlanId(fixture.base.plan.getId());
        messages.saveAndFlush(offer);
        helper.jdbc.update("update agent_runs set message_id=? where id=?", offer.getId(), fixture.base.plan.getId());
        var request = new CreateMessageRequest("b_" + key, button ? user.getContent() : "haan", ProjectType.BUILD,
                "c_" + key, List.of(), button ? offer.getId() : null);
        var transaction = new TransactionTemplate(app.getBean(PlatformTransactionManager.class));
        var resolved = transaction.execute(status -> new ConversationProposals(messages).resolve(7L, fixture.base.project, request, ProjectType.BUILD));
        assertNotNull(resolved);
        var approved = helper.store.reusable(fixture.base.project, resolved.prompt(), fixture.contract.limits());
        assertNotNull(approved);
        assertEquals(fixture.contract, helper.store.attach(fixture.base.run(ProjectType.BUILD), approved, fixture.contract.limits()).contract());
        assertEquals(1, helper.jdbc.queryForObject("select count(*) from agent_build_contracts where project_id=?", Integer.class, fixture.base.project));
    }

    @Test
    void exactReviewedV2PlanAttachesWithoutReplanningAndRejectsStaleSpec() {
        var fixture = new SpecStoreFixture(helper);
        var draft = helper.store.create(fixture.base.plan, fixture.contract, fixture.basis, fixture.contract.limits());
        fixture.base.done();
        helper.store.approve(7L, fixture.base.project, fixture.base.plan.getId(), fixture.contract.id(), draft.hash());
        var approved = helper.store.reusable(fixture.base.project, "build the approved plan", fixture.contract.limits());
        var build = fixture.base.run(ProjectType.BUILD);
        assertEquals(fixture.contract, helper.store.attach(build, approved, fixture.contract.limits()).contract());
        assertEquals(1, helper.jdbc.queryForObject("select count(*) from agent_build_contracts where project_id=?", Integer.class, fixture.base.project));
        helper.jdbc.update("update requirement_projections set payload_json=replace(payload_json,'Solar Bakery','New Bakery') where id=?", fixture.base.projection);
        assertThrows(RuntimeException.class, () -> helper.store.reusable(fixture.base.project, "build the approved plan", fixture.contract.limits()));
    }
}
