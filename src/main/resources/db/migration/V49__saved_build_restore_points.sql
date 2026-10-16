-- AFTER source versions: never reinterpret the old BEFORE snapshot_ref as a saved build.
create table agent_saved_builds (
    run_id uuid primary key references agent_runs(id),
    project_id uuid not null references projects(id),
    manifest_json text not null,
    manifest_hash varchar(71) not null check (manifest_hash ~ '^sha256:[a-f0-9]{64}$'),
    storage varchar(24) not null,
    auth varchar(24) not null,
    body_retired_at timestamptz,
    created_at timestamptz not null default clock_timestamp()
);
create index agent_saved_builds_project on agent_saved_builds(project_id,created_at desc);

alter table agent_code_restores add column actor_id bigint;
alter table agent_code_restores add column expected_digest varchar(71);
alter table agent_code_restores add column restore_files_json text;
alter table agent_code_restores add constraint saved_restore_proof_complete check (
    (restore_files_json is null and actor_id is null and expected_digest is null)
    or (restore_files_json is not null and actor_id is not null and expected_digest is not null
        and expected_digest ~ '^sha256:[a-f0-9]{64}$')
);

create function protect_saved_build_evidence() returns trigger language plpgsql as $$
begin
    if tg_op = 'DELETE' then
        raise exception 'Saved build evidence is immutable';
    end if;
    if old.run_id is distinct from new.run_id or old.project_id is distinct from new.project_id
        or old.manifest_json is distinct from new.manifest_json or old.manifest_hash is distinct from new.manifest_hash
        or old.storage is distinct from new.storage or old.auth is distinct from new.auth
        or old.created_at is distinct from new.created_at or old.body_retired_at is not null or new.body_retired_at is null then
        raise exception 'Saved build evidence is immutable';
    end if;
    return new;
end;
$$;
create trigger agent_saved_builds_immutable before update or delete on agent_saved_builds
    for each row execute function protect_saved_build_evidence();

create function protect_saved_restore_evidence() returns trigger language plpgsql as $$
begin
    if tg_op = 'DELETE' then
        if old.restore_files_json is not null then
            raise exception 'Saved restore audit is immutable';
        end if;
        return old;
    end if;
    if old.restore_files_json is not null and (
        old.id is distinct from new.id or old.run_id is distinct from new.run_id
        or old.project_id is distinct from new.project_id or old.backup_root is distinct from new.backup_root
        or old.actor_id is distinct from new.actor_id or old.expected_digest is distinct from new.expected_digest
        or old.restore_files_json is distinct from new.restore_files_json or old.created_at is distinct from new.created_at
        or old.state in ('RESTORED','ROLLED_BACK')
    ) then
        raise exception 'Saved restore proof or terminal outcome is immutable';
    end if;
    return new;
end;
$$;
create trigger agent_saved_restore_immutable before update or delete on agent_code_restores
    for each row execute function protect_saved_restore_evidence();
