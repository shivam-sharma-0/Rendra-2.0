package com.code.pal.agent.change;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;
import java.util.UUID;
import java.util.List;

@Service
public class RestoreJournal {
    private final JdbcTemplate jdbc;
    private final TransactionTemplate tx;
    public RestoreJournal(JdbcTemplate jdbc, PlatformTransactionManager manager) {
        this.jdbc = jdbc; tx = new TransactionTemplate(manager);
        tx.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW); tx.setTimeout(10);
    }
    public void prepare(UUID id, UUID run, UUID project, String backup) {
        tx.executeWithoutResult(s -> jdbc.update("insert into agent_code_restores(id,run_id,project_id,backup_root,state) values (?,?,?,?,'PREPARING')", id,run,project,backup));
    }
    public void state(UUID id, String state) {
        if (!java.util.Set.of("APPLYING","RESTORED","ROLLED_BACK","RECOVERY_REQUIRED").contains(state)) throw new IllegalArgumentException();
        tx.executeWithoutResult(s -> {
            if (jdbc.update("update agent_code_restores set state=?,updated_at=clock_timestamp() where id=? and state not in ('RESTORED','ROLLED_BACK')", state,id) != 1)
                throw new IllegalStateException("Restore evidence update refused");
        });
    }
    public String readState(UUID id) {
        return tx.execute(s -> jdbc.queryForObject("select state from agent_code_restores where id=?",String.class,id));
    }

    public record Entry(UUID id, UUID runId, UUID projectId, String backup, String state) {}

    public List<Entry> pending() {
        return jdbc.query("select id,run_id,project_id,backup_root,state from agent_code_restores "
                        + "where state not in ('RESTORED','ROLLED_BACK') order by created_at limit 100",
                (row, index) -> new Entry(row.getObject("id", UUID.class), row.getObject("run_id", UUID.class),
                        row.getObject("project_id", UUID.class), row.getString("backup_root"), row.getString("state")));
    }

    public Entry read(UUID id) {
        return jdbc.query("select id,run_id,project_id,backup_root,state from agent_code_restores where id=?",
                (row, index) -> new Entry(row.getObject("id", UUID.class), row.getObject("run_id", UUID.class),
                        row.getObject("project_id", UUID.class), row.getString("backup_root"), row.getString("state")),
                id).stream().findFirst().orElse(null);
    }
}
