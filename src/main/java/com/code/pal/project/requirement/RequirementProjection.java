package com.code.pal.project.requirement;

import com.code.pal.agent.lease.ProjectLeaseService;
import com.code.pal.agent.workspace.ProjectFiles;
import com.code.pal.project.brief.ProjectBriefFile;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.ObjectMapper;

import java.util.List;
import java.util.UUID;

/** Durable approval projection. Pending/partial files never authorize build dispatch. */
@Service @Slf4j
public class RequirementProjection {
    public record Payload(String requirementsJson, String brief) {}
    private record Entry(UUID id, UUID session, UUID project, long revision, String payload, String state) {}
    static final String BEGIN = "<!-- rendra-approved-requirements:start -->";
    static final String END = "<!-- rendra-approved-requirements:end -->";
    private final JdbcTemplate jdbc;
    private final TransactionTemplate tx;
    private final ProjectFiles files;
    private final ProjectLeaseService leases;
    private final RequirementCoordinator coordinator;
    private final ObjectMapper mapper;
    public RequirementProjection(JdbcTemplate jdbc, PlatformTransactionManager manager, ProjectFiles files,
                                 ProjectLeaseService leases, RequirementCoordinator coordinator, ObjectMapper mapper) {
        this.jdbc = jdbc; this.files = files; this.leases = leases; this.coordinator = coordinator; this.mapper = mapper;
        tx = new TransactionTemplate(manager);
        tx.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        tx.setTimeout(10);
    }

    /** Called in the same transaction as approval and message/run acceptance. */
    @org.springframework.transaction.annotation.Transactional(propagation = org.springframework.transaction.annotation.Propagation.MANDATORY)
    public UUID enqueue(UUID session, UUID project, long revision, Payload payload) {
        String json = mapper.writeValueAsString(payload);
        if (json.length() > 512_000) throw new IllegalArgumentException("Requirements projection exceeds limit");
        UUID id = UUID.randomUUID();
        jdbc.update("insert into requirement_projections(id,session_id,project_id,revision,payload_json) values (?,?,?,?,?)",
                id, session, project, revision, json);
        return id;
    }

    public void tryProject(UUID id) {
        if (id == null) return;
        try { project(id); }
        catch (RuntimeException unavailable) {
            // No error messages/provider output/keys in persisted diagnostics.
            log.warn("requirement_projection_pending id={} reason={}", id, unavailable.getClass().getSimpleName());
            try { tx.executeWithoutResult(status -> jdbc.update("update requirement_projections set error_code='PROJECTION_UNAVAILABLE',retry_at=clock_timestamp()+interval '5 seconds',updated_at=clock_timestamp() where id=? and state='PENDING'", id)); }
            catch (RuntimeException storeUnavailable) { log.warn("requirement_projection_retry_deferred id={}", id); }
        }
    }

    public void project(UUID id) {
        Entry initial = read(id);
        if (initial == null || !"PENDING".equals(initial.state())) return;
        leases.guarded(initial.project(), () -> {
            coordinator.lock(initial.project());
            if (leases.hasLiveLease(initial.project())) throw new IllegalStateException("Project build is active");
            Entry entry = read(id);
            if (entry == null || !"PENDING".equals(entry.state())) return;
            if (!current(entry)) {
                jdbc.update("update requirement_projections set state='SUPERSEDED',updated_at=clock_timestamp() where id=? and state='PENDING'", id);
                return;
            }
            Payload payload = mapper.readValue(entry.payload(), Payload.class);
            if (payload.requirementsJson() == null || payload.brief() == null || payload.brief().length() > 128_000) {
                throw new IllegalStateException("Invalid projection payload");
            }
            String before = files.regularFile(entry.project(), ".", ProjectBriefFile.NAME)
                    ? boundedBrief(entry.project()) : "";
            String brief = renderBrief(before, payload.brief(), entry.revision());
            if (brief.length() > 128_000) throw new IllegalStateException("Projected brief exceeds limit");
            files.writeText(entry.project(), ".codepal", "requirements.json", payload.requirementsJson());
            files.writeText(entry.project(), ".", ProjectBriefFile.NAME, brief);
            if (!payload.requirementsJson().equals(files.readText(entry.project(), ".codepal", "requirements.json"))
                    || !brief.equals(files.readText(entry.project(), ".", ProjectBriefFile.NAME))) {
                throw new IllegalStateException("Projection verification failed");
            }
            // Files are proved while project and requirement locks are held. A DB failure keeps PENDING.
            jdbc.update("update requirement_projections set state='READY',error_code=null,updated_at=clock_timestamp() where id=? and state='PENDING'", id);
        });
    }

    private String boundedBrief(UUID project) {
        if (files.size(project, ".", ProjectBriefFile.NAME) > 128_000) throw new IllegalStateException("Brief exceeds projection limit");
        return files.readText(project, ".", ProjectBriefFile.NAME);
    }
    public static String renderBrief(String existing, String content, long revision) {
        content = content.replace(BEGIN, "[approval marker]").replace(END, "[approval marker]");
        String block = BEGIN + "\n## Approved requirements revision " + revision + "\n\n" + content + "\n" + END;
        int begin = existing.indexOf(BEGIN), end = existing.indexOf(END);
        if (begin < 0 && end < 0) return existing.stripTrailing() + (existing.isBlank() ? "" : "\n\n") + block + "\n";
        if (begin < 0 || end < begin || existing.indexOf(BEGIN, begin + BEGIN.length()) >= 0
                || existing.indexOf(END, end + END.length()) >= 0) throw new IllegalStateException("Brief contains ambiguous approval markers");
        return existing.substring(0, begin) + block + existing.substring(end + END.length());
    }
    private boolean current(Entry entry) {
        return Boolean.TRUE.equals(jdbc.queryForObject("select exists(select 1 from requirement_sessions where id=? and project_id=? and projection_id=? and answer_revision=? and approved_revision=? and status='APPROVED')",
                Boolean.class, entry.session(), entry.project(), entry.id(), entry.revision(), entry.revision()));
    }
    private Entry read(UUID id) {
        List<Entry> rows = jdbc.query("select * from requirement_projections where id=?", (r, n) ->
                new Entry(r.getObject("id", UUID.class), r.getObject("session_id", UUID.class), r.getObject("project_id", UUID.class),
                        r.getLong("revision"), r.getString("payload_json"), r.getString("state")), id);
        return rows.isEmpty() ? null : rows.getFirst();
    }
    @Scheduled(fixedDelayString = "${app.agent.requirement-projection-poll-ms:1000}")
    public void poll() {
        for (UUID id : jdbc.query("select id from requirement_projections where state='PENDING' and retry_at<=clock_timestamp() order by created_at limit 100", (r, n) -> r.getObject(1, UUID.class))) {
            tryProject(id);
        }
    }
}
