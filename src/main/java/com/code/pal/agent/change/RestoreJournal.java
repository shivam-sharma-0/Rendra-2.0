package com.code.pal.agent.change;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.List;
import java.util.Set;
import java.util.UUID;

@Service
public class RestoreJournal {
    private static final long QUARANTINE_BACKOFF_SECONDS = 300;
    private static final String SELECT_ENTRY = "select id,run_id,project_id,backup_root,state from agent_code_restores ";
    private static final RowMapper<Entry> ENTRY_MAPPER = (row, index) -> new Entry(
            row.getObject("id", UUID.class), row.getObject("run_id", UUID.class),
            row.getObject("project_id", UUID.class), row.getString("backup_root"), row.getString("state"));

    private final JdbcTemplate jdbc;
    private final TransactionTemplate tx;

    public RestoreJournal(JdbcTemplate jdbc, PlatformTransactionManager manager) {
        this.jdbc = jdbc;
        tx = new TransactionTemplate(manager);
        tx.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        tx.setTimeout(10);
    }

    public void prepare(UUID id, UUID run, UUID project, String backup) {
        tx.executeWithoutResult(s -> jdbc.update(
                "insert into agent_code_restores(id,run_id,project_id,backup_root,state) values (?,?,?,?,'PREPARING')",
                id, run, project, backup));
    }

    public void state(UUID id, String state) {
        if (!Set.of("APPLYING", "RESTORED", "ROLLED_BACK", "RECOVERY_REQUIRED").contains(state)) {
            throw new IllegalArgumentException("Invalid restore state");
        }
        tx.executeWithoutResult(s -> {
            if (jdbc.update("update agent_code_restores set state=?,updated_at=clock_timestamp() "
                    + "where id=? and state not in ('RESTORED','ROLLED_BACK')", state, id) != 1) {
                throw new IllegalStateException("Restore evidence update refused");
            }
        });
    }

    public String readState(UUID id) {
        return tx.execute(s -> jdbc.queryForObject("select state from agent_code_restores where id=?", String.class, id));
    }

    public record Entry(UUID id, UUID runId, UUID projectId, String backup, String state) {}

    public List<Entry> pending() {
        // Filter before LIMIT: recent quarantines cannot crowd out a newer interrupted restore.
        return jdbc.query(SELECT_ENTRY
                        + "where state not in ('RESTORED','ROLLED_BACK') "
                        + "and (state <> 'RECOVERY_REQUIRED' or updated_at <= clock_timestamp() - (? * interval '1 second')) "
                        + "order by case when state='RECOVERY_REQUIRED' then 1 else 0 end,updated_at,id limit 100",
                ENTRY_MAPPER, QUARANTINE_BACKOFF_SECONDS);
    }

    public Entry read(UUID id) {
        return jdbc.query(SELECT_ENTRY + "where id=?", ENTRY_MAPPER, id).stream().findFirst().orElse(null);
    }
}
