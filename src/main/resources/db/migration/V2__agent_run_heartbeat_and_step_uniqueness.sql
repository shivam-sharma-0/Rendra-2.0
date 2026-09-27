-- Reliability fixes for the agent run engine.

-- 1) Live-run heartbeat: a worker beats this timestamp while executing so stale-run recovery can tell
--    a crashed worker from one merely busy in a long LLM stream, and never rolls back a live run.
ALTER TABLE agent_runs ADD COLUMN heartbeat_at timestamp(6) with time zone;
CREATE INDEX ix_agent_runs_status_heartbeat ON agent_runs (status, heartbeat_at);

-- 2) Timeline integrity: two concurrent emits must never share a (run_id, seq). Remove any duplicates
--    left by the old read-then-insert path, then enforce uniqueness (which also serves ordered scans,
--    so the plain index is now redundant).
DELETE FROM agent_steps a
      USING agent_steps b
      WHERE a.run_id = b.run_id
        AND a.seq = b.seq
        AND a.ctid > b.ctid;

DROP INDEX IF EXISTS ix_agent_steps_run_seq;
ALTER TABLE agent_steps ADD CONSTRAINT uq_agent_steps_run_seq UNIQUE (run_id, seq);
