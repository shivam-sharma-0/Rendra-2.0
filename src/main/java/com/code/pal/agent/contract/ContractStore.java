package com.code.pal.agent.contract;

import com.code.pal.agent.domain.AgentRun;
import com.code.pal.agent.intent.RequestRouter;
import com.code.pal.agent.lease.ProjectLeaseService;
import com.code.pal.agent.runtime.ReleaseSource;
import com.code.pal.agent.starter.Starter;
import com.code.pal.agent.workspace.ProjectFiles;
import com.code.pal.message.domain.Message;
import com.code.pal.message.service.ConversationProposals;
import com.code.pal.project.brief.ApprovedProjectMemory;
import com.code.pal.project.capability.BuildDecisions;
import com.code.pal.project.domain.ProjectType;
import com.code.pal.project.exception.WorkflowStageException;
import com.code.pal.project.service.ProjectService;
import com.fasterxml.jackson.annotation.JsonInclude;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.*;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.ObjectMapper;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.*;

/** Immutable contract + server-owned approval/code/capability binding. Imported/model text is not approval. */
@Service
public final class ContractStore {
    public record Binding(int version, UUID requirementsProjection, long requirementsRevision, String codeRevision,
            String capabilityRevision, Set<String> integrationIds,
            @JsonInclude(JsonInclude.Include.NON_NULL) UUID specId,
            @JsonInclude(JsonInclude.Include.NON_NULL) Long specRevision,
            @JsonInclude(JsonInclude.Include.NON_NULL) String specHash,
            @JsonInclude(JsonInclude.Include.NON_NULL) String templateVersion,
            @JsonInclude(JsonInclude.Include.NON_NULL) String scaffoldRevision,
            @JsonInclude(JsonInclude.Include.NON_NULL) String specDocumentHash) {
        public Binding(int version, UUID projection, long revision, String code, String capability, Set<String> integrations) {
            this(version, projection, revision, code, capability, integrations, null, null, null, null, null, null);
        }

        public Binding {
            integrationIds = Set.copyOf(integrationIds);
            if (version != 1 && version != 2) {
                throw new IllegalArgumentException("Unknown contract binding version");
            }
            if (version == 2 && (specId == null || specRevision == null || specRevision < 1 || specHash == null
                    || !specHash.matches("sha256:[a-f0-9]{64}") || templateVersion == null || scaffoldRevision == null || specDocumentHash == null)) {
                throw new IllegalArgumentException("Incomplete v2 contract binding");
            }
        }
    }

    public record Stored(UUID runId, UUID projectId, BuildContract contract, String hash, Binding binding, String state,
            String approvalKind) {
    }

    public record Review(UUID id, String hash, String state, String storage, String auth,
            List<BuildContract.Unit> units) {
    }

    private final JdbcTemplate jdbc;
    private final TransactionTemplate tx;
    private final ObjectMapper mapper;
    private final ProjectFiles files;
    private final ApprovedProjectMemory memory;
    private final BuildDecisions decisions;
    private final ProjectLeaseService leases;
    private final ProjectService projects;

    public ContractStore(JdbcTemplate jdbc, PlatformTransactionManager manager, ObjectMapper mapper, ProjectFiles files,
            ApprovedProjectMemory memory, BuildDecisions decisions, ProjectLeaseService leases,
            ProjectService projects) {
        this.jdbc = jdbc;
        this.mapper = mapper;
        this.files = files;
        this.memory = memory;
        this.decisions = decisions;
        this.leases = leases;
        this.projects = projects;
        tx = new TransactionTemplate(manager);
        tx.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        tx.setTimeout(10);
    }

    public Binding current(UUID project) {
        var approved = memory.current(project);
        var state = decisions.state(project);
        if (approved == null || state.blocked() || state.choice() == null)
            throw conflict(
                    "Approve current requirements and available storage/auth before creating a reusable contract");
        var ids = new HashSet<String>();
        if (state.choice().storage() == BuildDecisions.Storage.MERN)
            ids.add("platform:mongodb");
        if (state.choice().storage() == BuildDecisions.Storage.SUPABASE)
            ids.add("supabase:" + state.choice().connectionRevision());
        String code = ReleaseSource.capture(files, project, ".");
        String capability = hash(mapper.writeValueAsString(new TreeMap<>(Map.of("choice", state.choice(), "available", state.available()))));
        if (approved.specJson() != null && state.choice().storage() != BuildDecisions.Storage.MERN) {
            var spec = ContractSpecIdentity.of(approved, state.choice().storage(), mapper);
            return new Binding(2, approved.projectionId(), approved.revision(), code, capability, ids, spec.id(), spec.revision(),
                    spec.hash(), Starter.TEMPLATE_VERSION, ContractScaffold.revision(), spec.documentHash());
        }
        return new Binding(1, approved.projectionId(), approved.revision(), code, capability, ids);
    }

    public Stored create(AgentRun run, BuildContract contract, Binding expected, BuildContract.Limits allowed) {
        return leases.guarded(run.getProjectId(), () -> {
            projects.requireCanEdit(run.getUserId(), run.getProjectId());
            if (jdbc.queryForObject(
                    "select count(*) from agent_runs where id=? and project_id=? and user_id=? and mode=? and status in ('RUNNING','DONE')",
                    Integer.class,
                    run.getId(), run.getProjectId(), run.getUserId(), run.getMode().name()) != 1
                    || (run.getMode() != ProjectType.PLAN && run.getMode() != ProjectType.BUILD))
                throw conflict("Contract run identity mismatch");
            if (!current(run.getProjectId()).equals(expected))
                throw conflict("Code, capabilities or requirements changed during planning; request a current plan");
            if (!contract.limits().within(allowed))
                throw conflict("Contract exceeds the approved run caps");
            if (contract.schemaVersion() == 2 && (expected.version() != 2 || !contract.specId().equals(expected.specId())
                    || !contract.specRevision().equals(expected.specRevision()) || !contract.specHash().equals(expected.specHash())
                    || !contract.templateVersion().equals(expected.templateVersion()))) {
                throw conflict("Contract differs from the current approved spec/template");
            }
            var choice = decisions.state(run.getProjectId()).choice();
            if (contract.storage() != choice.storage() || contract.auth() != choice.auth())
                throw conflict("Contract differs from approved capabilities");
            validateSources(run.getProjectId(), contract);
            String json = mapper.writeValueAsString(contract), digest = hash(json),
                    binding = mapper.writeValueAsString(expected);
            boolean build = run.getMode() == ProjectType.BUILD;
            tx.executeWithoutResult(s -> jdbc.update(
                    "insert into agent_build_contracts(id,run_id,project_id,contract_json,contract_hash,binding_json,state,approval_kind,approved_by,approved_at) "
                            + "values (?,?,?,?,?,?,?,? ,?,case when ? then clock_timestamp() else null end) on conflict(run_id) do nothing",
                    contract.id(), run.getId(), run.getProjectId(), json, digest, binding, build ? "APPROVED" : "DRAFT",
                    build ? "BUILD_REQUEST" : null, build ? run.getUserId() : null, build));
            var original = jdbc.query(
                    "select id,contract_hash,binding_json from agent_build_contracts where run_id=? and project_id=?",
                    (r, n) -> new String[] { r.getString(1), r.getString(2), r.getString(3) }, run.getId(),
                    run.getProjectId());
            if (original.size() != 1 || !original.getFirst()[0].equals(contract.id().toString())
                    || !original.getFirst()[1].equals(digest)
                    || !mapper.readValue(original.getFirst()[2], Binding.class).equals(expected))
                throw conflict("Contract for this accepted run is immutable");
            link(run, contract.id());
            var stored = forRun(run.getProjectId(), run.getId());
            if (stored == null || !stored.contract().equals(contract) || !stored.binding().equals(expected))
                throw conflict("Contract for this accepted run is immutable");
            return stored;
        });
    }

    public Stored attach(AgentRun run, Stored approved, BuildContract.Limits allowed) {
        return leases.guarded(run.getProjectId(), () -> {
            projects.requireCanEdit(run.getUserId(), run.getProjectId());
            var source = forRun(approved.projectId(), approved.runId());
            if (source == null || !source.projectId().equals(run.getProjectId()) || !"APPROVED".equals(source.state())
                    || !source.contract().limits().within(allowed)
                    || !source.binding().equals(current(run.getProjectId()))
                    || jdbc.queryForObject(
                            "select count(*) from agent_runs where id=? and project_id=? and user_id=? and mode='BUILD' and status='RUNNING'",
                            Integer.class, run.getId(), run.getProjectId(), run.getUserId()) != 1)
                throw conflict("Compatible approved Plan is unavailable for this Build");
            link(run, source.contract().id());
            var attached = forRun(run.getProjectId(), run.getId());
            if (attached == null || !attached.contract().id().equals(source.contract().id()))
                throw conflict("Run contract binding is immutable");
            return attached;
        });
    }

    private void link(AgentRun run, UUID id) {
        tx.executeWithoutResult(s -> jdbc.update(
                "insert into agent_run_contracts(run_id,project_id,contract_id) values (?,?,?) on conflict(run_id) do nothing",
                run.getId(), run.getProjectId(), id));
    }

    public Review approve(Long actor, UUID project, UUID run, UUID id, String reviewedHash) {
        return leases.guarded(project, () -> {
            projects.requireCanEdit(actor, project);
            var stored = forRun(project, run);
            if (stored == null || !stored.contract().id().equals(id) || !stored.hash().equals(reviewedHash)
                    || jdbc.queryForObject(
                            "select count(*) from agent_runs where id=? and project_id=? and mode='PLAN' and status='DONE'",
                            Integer.class, run, project) != 1)
                throw conflict("Approve the exact completed Plan revision you reviewed");
            if (!compatible(stored, current(project)))
                throw conflict("Plan is stale; create a plan for the current code/requirements/capabilities");
            if (!Set.of("DRAFT", "APPROVED").contains(stored.state()))
                throw conflict("Plan is invalidated");
            tx.executeWithoutResult(s -> jdbc.update(
                    "update agent_build_contracts set state='APPROVED',approval_kind='EXPLICIT_PLAN',approved_by=?,approved_at=clock_timestamp() where id=? and project_id=? and state='DRAFT'",
                    actor, id, project));
            return review(forRun(project, run));
        });
    }

    public Stored reusable(UUID project, String request, BuildContract.Limits allowed) {
        var rows = jdbc.query("select run_id from agent_build_contracts where project_id=? "
                + "order by created_at desc,id desc limit 1", (row, index) -> row.getObject(1, UUID.class), project);
        if (rows.isEmpty()) {
            return null;
        }
        UUID latestPlan = rows.getFirst();
        if (!RequestRouter.buildApprovedPlan(request)) {
            // Only the latest plan's one persisted reply can match; never scan the conversation history.
            var candidates = jdbc.query("select p.proposal_plan_id,p.suggested_build_text,p.content "
                    + "from messages p join agent_runs r on r.id=p.proposal_plan_id and r.project_id=p.project_id "
                    + "where p.project_id=? and p.proposal_plan_id=? and p.role='ASSISTANT' and p.status='COMPLETE' "
                    + "and p.proposal_consumed_by is not null and r.message_id=p.id "
                    + "and r.mode='PLAN' and r.status='DONE'", (row, index) -> {
                        Message proposal = new Message();
                        proposal.setProposalPlanId(row.getObject(1, UUID.class));
                        proposal.setSuggestedBuildText(row.getString(2));
                        proposal.setContent(row.getString(3));
                        return proposal;
                    }, project, latestPlan);
            boolean matches = candidates.stream().filter(proposal -> proposal.getSuggestedBuildText() != null)
                    .anyMatch(proposal -> ConversationProposals.executionPrompt(proposal).equals(request));
            if (!matches) {
                if (RequestRouter.route(request, List.of(), true) == RequestRouter.Route.CONTINUE) {
                    throw conflict("A continuation needs the current reviewed plan offer before Build");
                }
                return null;
            }
        }
        var stored = forRun(project, latestPlan);
        if (!compatible(stored, current(project))) {
            throw conflict("Approved plan is stale; request and review an updated plan before building it");
        }
        if (!"APPROVED".equals(stored.state())) {
            throw conflict("The latest plan needs explicit approval before Build");
        }
        if (!stored.contract().limits().within(allowed)) {
            throw conflict("Approved plan exceeds this run's current limits; request a smaller plan");
        }
        return stored;
    }

    /** Execution authority comes from the accepted request binding, never a model-authored wrapper. */
    public UUID acceptedPlanRun(AgentRun run) {
        var ids = jdbc.query("select p.proposal_plan_id from agent_runs r "
                + "join messages a on a.id=r.message_id and a.project_id=r.project_id "
                + "join messages u on u.id=a.reply_to_id and u.project_id=r.project_id "
                + "join messages p on p.id=u.source_proposal_id and p.project_id=r.project_id "
                + "join agent_runs original on original.id=p.proposal_plan_id and original.project_id=r.project_id "
                + "where r.id=? and r.project_id=? and r.user_id=? and u.author_id=r.user_id "
                + "and r.mode='BUILD' and r.status='RUNNING' and a.role='ASSISTANT' "
                + "and p.proposal_consumed_by=u.id and original.user_id=r.user_id "
                + "and original.message_id=p.id and u.role='USER' and p.role='ASSISTANT' "
                + "and original.mode='PLAN' and original.status='DONE' and p.status='COMPLETE'",
                (row, index) -> row.getObject(1, UUID.class), run.getId(), run.getProjectId(), run.getUserId());
        return ids.stream().findFirst().orElse(null);
    }

    public Review view(UUID project, UUID run) {
        var row = forRun(project, run);
        if (row == null)
            return null;
        try {
            compatible(row, current(project));
        } catch (WorkflowStageException unavailable) {
            invalidate(row, "Current approval/capabilities unavailable");
        }
        return review(forRun(project, run));
    }

    public Stored forRun(UUID project, UUID run) {
        return jdbc.query(
                "select c.* from agent_build_contracts c join agent_run_contracts b on b.contract_id=c.id and b.project_id=c.project_id where b.project_id=? and b.run_id=?",
                (r, n) -> {
                    String json = r.getString("contract_json");
                    if (!hash(json).equals(r.getString("contract_hash")))
                        throw conflict("Contract integrity proof failed");
                    return new Stored(run, project, mapper.readValue(json, BuildContract.class),
                            r.getString("contract_hash"), mapper.readValue(r.getString("binding_json"), Binding.class),
                            r.getString("state"), r.getString("approval_kind"));
                }, project, run).stream().findFirst().orElse(null);
    }

    /** Cheap listing hint. Fresh revision/permission checks remain mandatory when reviewing/approving. */
    public Set<UUID> reviewableRuns(UUID project) {
        return Set.copyOf(jdbc.query(
                "select b.run_id from agent_run_contracts b join agent_runs r on r.id=b.run_id where b.project_id=? and r.project_id=? and r.mode='PLAN' and r.status='DONE'",
                (r, n) -> r.getObject(1, UUID.class), project, project));
    }

    private boolean compatible(Stored stored, Binding current) {
        if (stored.binding().equals(current))
            return true;
        invalidate(stored, "Code, requirements or capability revision changed");
        return false;
    }

    private void invalidate(Stored stored, String reason) {
        tx.executeWithoutResult(s -> jdbc.update(
                "update agent_build_contracts set state='INVALIDATED',invalidation_reason=? where id=? and project_id=? and state<>'INVALIDATED'",
                reason, stored.contract().id(), stored.projectId()));
    }

    void validateSources(UUID project, BuildContract contract) {
        var owned = contract.ownership();
        for (var unit : contract.units())
            for (String path : unit.imports())
                if (!owned.containsKey(path) && !path.startsWith(contract.clientRoot())
                        && !path.startsWith(contract.serverRoot()) && !files.regularFile(project, ".", path))
                    throw conflict(
                            "Contract references a missing existing import; use availableCoordinatorPaths or declared owned files");
        for (var asset : contract.assets())
            if (!files.regularFile(project, ".", asset.source())
                    || !files.version(project, ".", asset.source()).equals(asset.fingerprint()))
                throw conflict("Asset source identity changed or was invented");
        String app = contract.stack() == BuildContract.Stack.MERN ? "client/src/App.tsx" : "src/App.tsx";
        if (contract.stack() != BuildContract.Stack.STATIC && !files.regularFile(project, ".", app)
                && !owned.containsKey(app) && contract.routes().isEmpty())
            throw conflict("New app needs an owned root component or declared implemented route");
    }

    private Review review(Stored stored) {
        return new Review(stored.contract().id(), stored.hash(), stored.state(), stored.contract().storage().name(),
                stored.contract().auth().name(), stored.contract().waves().stream().flatMap(List::stream).toList());
    }

    public static String hash(String text) {
        try {
            return HexFormat.of()
                    .formatHex(MessageDigest.getInstance("SHA-256").digest(text.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException(impossible);
        }
    }

    private WorkflowStageException conflict(String reason) {
        return new WorkflowStageException(reason);
    }
}
