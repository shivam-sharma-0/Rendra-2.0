package com.code.pal.agent.usage;

import com.code.pal.agent.config.AgentProperties;
import com.code.pal.agent.contract.ContractStore;
import com.code.pal.agent.llm.*;
import com.code.pal.agent.runtime.RunCancellation;
import com.code.pal.agent.lease.ProjectLeaseService;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.*;
import org.springframework.transaction.support.TransactionTemplate;
import java.util.*;
import java.util.concurrent.TimeUnit;
import java.sql.Timestamp;

/** Short DB admission transactions only; no row/advisory lock is held across wait or HTTP. */
@Service
public final class ProviderScheduler {
    private final JdbcTemplate jdbc;
    private final TransactionTemplate tx;
    private final AgentProperties props;
    private final ProjectLeaseService leases;

    public ProviderScheduler(JdbcTemplate jdbc, PlatformTransactionManager manager, AgentProperties props,
            ProjectLeaseService leases) {
        this.jdbc = jdbc;
        this.props = props;
        this.leases = leases;
        tx = new TransactionTemplate(manager);
        tx.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        tx.setTimeout(5);
    }

    public final class Permit implements AutoCloseable {
        private final UUID id;
        private boolean possiblySent, knownComplete;

        private Permit(UUID id) {
            this.id = id;
        }

        public void sent() {
            possiblySent = true;
        }

        public void completed() {
            knownComplete = true;
        }

        @Override
        public void close() {
            if (id == null)
                return;
            tx.executeWithoutResult(s -> jdbc.update(
                    "update agent_provider_slots set state=?,finished_at=clock_timestamp() where id=? and state='ACTIVE'",
                    possiblySent && !knownComplete ? "UNKNOWN" : "RELEASED", id));
        }
    }

    public Permit acquire(LlmCallContext caller, LlmClient.LlmRequest request) {
        var cfg = props.getScheduling();
        if (!cfg.isEnabled())
            return new Permit(null);
        check(caller);
        var pools = configuration();
        var pool = cfg.getPools().get(request.provider());
        if (pool == null)
            throw new LlmAccountingException("Verified scheduling account/cap unavailable for provider");
        UUID id = UUID.randomUUID();
        long maxWait = Math.max(1, Math.min(cfg.getMaxWaitMs(),
                request.budget() == null ? cfg.getMaxWaitMs() : request.budget().toMillis()));
        long end = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(maxWait);
        tx.executeWithoutResult(s -> {
            lock();
            var aliases = new TreeMap<String, String>();
            cfg.getPools().forEach((provider, p) -> aliases.put(provider, p.getAccount()));
            String fingerprint = ContractStore.hash(cfg.getGlobalConcurrency() + "/" + cfg.getUserConcurrency() + "/"
                    + cfg.getRunConcurrency() + "/" + pools + "/" + aliases);
            jdbc.update("insert into agent_scheduler_config(id,fingerprint) values(true,?) on conflict do nothing",
                    fingerprint);
            if (!fingerprint.equals(
                    jdbc.queryForObject("select fingerprint from agent_scheduler_config where id=true", String.class)))
                throw new LlmAccountingException(
                        "Scheduling limits differ between instances; quiesced configuration review required");
            jdbc.update(
                    "insert into agent_provider_slots(id,provider,account_pool,user_id,run_id,state,wait_until) values (?,?,?,?,?,'WAITING',clock_timestamp()+(? * interval '1 millisecond'))",
                    id, request.provider(), pool.getAccount(), caller.userId(), caller.runId(), maxWait);
        });
        boolean active = false, handedOff = false;
        try {
            while (System.nanoTime() < end) {
                check(caller);
                if (admit(id, pools)) {
                    active = true;
                    check(caller);
                    handedOff = true;
                    return new Permit(id);
                }
                try {
                    Thread.sleep(25);
                } catch (InterruptedException stop) {
                    Thread.currentThread().interrupt();
                    throw new LlmCancelledException();
                }
            }
            throw new LlmException("Provider scheduling wait limit reached; no request was transmitted");
        } finally {
            if (!active)
                tx.executeWithoutResult(s -> jdbc.update(
                        "update agent_provider_slots set state='CANCELLED',finished_at=clock_timestamp() where id=? and state='WAITING'",
                        id));
            else if (!handedOff)
                tx.executeWithoutResult(s -> jdbc.update(
                        "update agent_provider_slots set state='RELEASED',finished_at=clock_timestamp() where id=? and state='ACTIVE'",
                        id));
        }
    }

    private Map<String, Integer> configuration() {
        var cfg = props.getScheduling();
        if (cfg.getGlobalConcurrency() < 1 || cfg.getGlobalConcurrency() > 32 || cfg.getUserConcurrency() < 1
                || cfg.getRunConcurrency() < 1 || cfg.getMaxWaitMs() < 1 || cfg.getMaxWaitMs() > 60000)
            throw new LlmAccountingException("Invalid configured scheduling limits");
        var pools = new TreeMap<String, Integer>();
        for (var e : cfg.getPools().entrySet()) {
            var p = e.getValue();
            if (!e.getKey().matches("[a-z][a-z0-9_-]{0,47}") || p == null || p.getAccount() == null
                    || !p.getAccount().matches("[A-Za-z0-9_-]{1,80}") || p.getConcurrency() < 1
                    || p.getConcurrency() > 32)
                throw new LlmAccountingException("Invalid verified scheduling account/cap");
            var old = pools.putIfAbsent(p.getAccount(), p.getConcurrency());
            if (old != null && old != p.getConcurrency())
                throw new LlmAccountingException("One account group has inconsistent limits");
        }
        return pools;
    }

    private void lock() {
        jdbc.execute("set local lock_timeout='2s'");
        jdbc.queryForObject("select pg_advisory_xact_lock(82010001)", Object.class);
    }

    private boolean admit(UUID mine, Map<String, Integer> pools) {
        return Boolean.TRUE.equals(tx.execute(s -> {
            lock();
            jdbc.update(
                    "update agent_provider_slots set state='CANCELLED',finished_at=clock_timestamp() where state='WAITING' and wait_until<=clock_timestamp()");
            var active = jdbc.query(
                    "select account_pool,user_id,run_id from agent_provider_slots where state in ('ACTIVE','UNKNOWN')",
                    (r, n) -> new Object[] { r.getString(1), r.getLong(2), r.getObject(3, UUID.class) });
            var cfg = props.getScheduling();
            if (active.size() >= cfg.getGlobalConcurrency())
                return false;
            var waiting = jdbc.query(
                    "select q.id,q.account_pool,q.user_id,q.run_id,coalesce(t.admitted_at,to_timestamp(0)) from agent_provider_slots q left join agent_scheduler_user_turns t on t.user_id=q.user_id where q.state='WAITING' and q.wait_until>clock_timestamp() order by q.queued_at,q.id",
                    (r, n) -> new Object[] { r.getObject(1, UUID.class), r.getString(2), r.getLong(3),
                            r.getObject(4, UUID.class), r.getTimestamp(5) });
            Object[] chosen = null;
            long least = Long.MAX_VALUE;
            Timestamp last = null;
            for (var row : waiting) {
                long user = active.stream().filter(a -> a[1].equals(row[2])).count(),
                        account = active.stream().filter(a -> a[0].equals(row[1])).count(),
                        run = row[3] == null ? 0 : active.stream().filter(a -> Objects.equals(a[2], row[3])).count();
                if (user >= cfg.getUserConcurrency() || run >= cfg.getRunConcurrency()
                        || account >= pools.getOrDefault(row[1], 0))
                    continue;
                var served = (Timestamp) row[4];
                if (user < least || user == least && (last == null || served.before(last))) {
                    chosen = row;
                    least = user;
                    last = served;
                }
            }
            if (chosen == null || !mine.equals(chosen[0]))
                return false;
            boolean admitted = jdbc
                    .update("update agent_provider_slots set state='ACTIVE' where id=? and state='WAITING'", mine) == 1;
            if (admitted)
                jdbc.update(
                        "insert into agent_scheduler_user_turns(user_id,admitted_at) values (?,clock_timestamp()) on conflict(user_id) do update set admitted_at=excluded.admitted_at",
                        chosen[2]);
            return admitted;
        }));
    }

    private void check(LlmCallContext caller) {
        if (RunCancellation.current().isCancelled() || Thread.currentThread().isInterrupted())
            throw new LlmCancelledException();
        if (caller.userId() == null)
            throw new LlmAccountingException("Scheduler caller has no owner");
        if (caller.runId() != null) {
            leases.checkCurrent(caller.projectId());
            if (!Boolean.TRUE.equals(jdbc.queryForObject(
                    "select exists(select 1 from agent_runs where id=? and user_id=? and status='RUNNING' and cancel_requested_at is null)",
                    Boolean.class, caller.runId(), caller.userId())))
                throw new LlmCancelledException();
        }
    }
}
