package com.code.pal.agent.repair;

import com.code.pal.agent.contract.BuildContract;
import com.code.pal.agent.contract.ContractIdentity;
import com.code.pal.agent.contract.ContractStore;
import com.code.pal.agent.contract.UnitGenerator;
import com.code.pal.agent.domain.AgentRun;
import com.code.pal.agent.lease.ProjectLeaseService;
import com.code.pal.agent.workspace.ProjectFiles;
import tools.jackson.databind.ObjectMapper;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.Consumer;

/** Durable two-round journal, outside model-writable paths. No HTTP/build/install holds its short lease guard. */
public final class RepairJournal {
    public record Round(int number, String diagnosticsRevision, String state, List<UnitGenerator.Delta> files) {
        public Round {
            files = List.copyOf(files);
        }
    }

    public record State(String request, String contractHash, List<Round> rounds) {
        public State {
            rounds = List.copyOf(rounds);
        }
    }

    public record Envelope(String hash, State state) {
    }

    private final ProjectFiles files;
    private final ProjectLeaseService leases;
    private final ObjectMapper mapper;

    public RepairJournal(ProjectFiles files, ProjectLeaseService leases, ObjectMapper mapper) {
        this.files = files;
        this.leases = leases;
        this.mapper = mapper;
    }

    public int reserve(AgentRun run, BuildContract contract, String diagnosticsRevision, Runnable requireCurrent) {
        int[] result = {0};
        leases.guarded(run.getProjectId(), () -> {
            requireCurrent.run();
            var state = read(run, contract);
            if (state.rounds().size() >= 2 || state.rounds().stream().anyMatch(round -> List.of("PREPARED", "QUARANTINED").contains(round.state()))) {
                throw new IllegalStateException("Repair budget exhausted or recovery required");
            }
            if (diagnosticsRevision == null || !diagnosticsRevision.matches("sha256:[a-f0-9]{64}")) {
                throw new IllegalArgumentException("Repair requires current diagnostics");
            }
            result[0] = state.rounds().size() + 1;
            var rounds = new ArrayList<>(state.rounds());
            rounds.add(new Round(result[0], diagnosticsRevision, "RESERVED", List.of()));
            write(run, new State(state.request(), state.contractHash(), rounds));
        });
        return result[0];
    }

    public void prepare(AgentRun run, BuildContract contract, String root, int round, List<UnitGenerator.Delta> deltas, Runnable requireCurrent) {
        leases.guarded(run.getProjectId(), () -> {
            requireCurrent.run();
            var state = read(run, contract);
            if (round != state.rounds().size() || !state.rounds().getLast().state().equals("RESERVED") || deltas.isEmpty() || deltas.size() > 12) {
                throw new IllegalStateException("Repair reservation changed");
            }
            var paths = new HashMap<String, Boolean>();
            for (var delta : deltas) {
                BuildContract.canonical(delta.path());
                if (delta.content() == null || paths.put(delta.path(), true) != null
                        || !files.version(run.getProjectId(), root, delta.path()).equals(delta.expectedVersion())) {
                    throw new IllegalStateException("Repair targets changed before prepare");
                }
            }
            replace(run, state, new Round(round, state.rounds().getLast().diagnosticsRevision(), "PREPARED", deltas));
        });
    }

    public void recover(AgentRun run, BuildContract contract, String root, Runnable requireCurrent, Consumer<List<UnitGenerator.Delta>> apply) {
        recover(run, contract, root, null, requireCurrent, apply);
    }

    public void recover(AgentRun run, BuildContract contract, String root, Set<String> readyPaths,
            Runnable requireCurrent, Consumer<List<UnitGenerator.Delta>> apply) {
        leases.guarded(run.getProjectId(), () -> {
            requireCurrent.run();
            var state = read(run, contract);
            for (var round : state.rounds()) {
                if (round.state().equals("QUARANTINED")) {
                    throw new IllegalStateException("Repair targets require operator recovery");
                }
                if (!round.state().equals("PREPARED")) {
                    continue;
                }
                if (readyPaths != null && !readyPaths.containsAll(round.files().stream().map(UnitGenerator.Delta::path).toList())) {
                    continue; // A restored worker has not replayed the owning generation batch yet.
                }
                var changes = new ArrayList<UnitGenerator.Delta>();
                for (var delta : round.files()) {
                    String actual = files.version(run.getProjectId(), root, delta.path());
                    String after = "sha256:" + ContractStore.hash(delta.content());
                    if (!actual.equals(delta.expectedVersion()) && !actual.equals(after)) {
                        replace(run, state, new Round(round.number(), round.diagnosticsRevision(), "QUARANTINED", round.files()));
                        throw new IllegalStateException("Repair target has an unproved version");
                    }
                    // Include already-applied files: replaceBatch also restores their mutation audit/revision after process loss.
                    changes.add(new UnitGenerator.Delta(delta.path(), actual, delta.content()));
                }
                requireCurrent.run();
                apply.accept(List.copyOf(changes));
                for (var delta : changes) {
                    if (!files.version(run.getProjectId(), root, delta.path()).equals("sha256:" + ContractStore.hash(delta.content()))) {
                        throw new IllegalStateException("Repair apply did not prove every after hash");
                    }
                }
                replace(run, state, new Round(round.number(), round.diagnosticsRevision(), "APPLIED", round.files()));
                state = read(run, contract);
            }
        });
    }

    public List<UnitGenerator.Output> overlay(AgentRun run, BuildContract contract, List<UnitGenerator.Output> generated) {
        return overlay(run, contract, generated, Map.of());
    }

    public List<UnitGenerator.Output> overlay(AgentRun run, BuildContract contract, List<UnitGenerator.Output> generated, Map<String, String> actualVersions) {
        var rounds = read(run, contract).rounds();
        for (var round : rounds) {
            if (round.state().equals("QUARANTINED")) {
                throw new IllegalStateException("Repair targets require operator recovery");
            }
        }
        var invalid = new HashSet<UnitGenerator.Delta>();
        var result = new ArrayList<UnitGenerator.Output>();
        for (var output : generated) {
            var merged = new ArrayList<UnitGenerator.Delta>();
            for (var generatedFile : output.files()) {
                var repairs = rounds.stream().filter(round -> Set.of("APPLIED", "PREPARED").contains(round.state()))
                        .flatMap(round -> round.files().stream()).filter(file -> file.path().equals(generatedFile.path())).toList();
                String body = generatedFile.content();
                String hash = body == null ? "absent" : "sha256:" + ContractStore.hash(body);
                int matching = -1;
                for (int index = 0; index < repairs.size(); index++) {
                    var repair = repairs.get(index);
                    String after = "sha256:" + ContractStore.hash(repair.content());
                    if (hash.equals(repair.expectedVersion()) || hash.equals(after)) {
                        matching = index;
                    }
                }
                if (matching < 0) {
                    invalid.addAll(repairs);
                    merged.add(generatedFile);
                    continue;
                }
                var proved = new HashSet<String>();
                String ancestor = repairs.get(matching).expectedVersion();
                for (int index = matching - 1; index >= 0; index--) {
                    var repair = repairs.get(index);
                    String after = "sha256:" + ContractStore.hash(repair.content());
                    if (!after.equals(ancestor)) {
                        break;
                    }
                    proved.add(repair.expectedVersion());
                    proved.add(after);
                    ancestor = repair.expectedVersion();
                }
                for (int index = matching; index < repairs.size(); index++) {
                    var repair = repairs.get(index);
                    String after = "sha256:" + ContractStore.hash(repair.content());
                    if (hash.equals(repair.expectedVersion()) || hash.equals(after)) {
                        body = repair.content();
                        hash = after;
                        proved.add(repair.expectedVersion());
                        proved.add(after);
                    } else {
                        invalid.add(repair);
                    }
                }
                String actual = actualVersions.get(generatedFile.path());
                String expected = actual != null && proved.contains(actual) ? actual : generatedFile.expectedVersion();
                merged.add(new UnitGenerator.Delta(generatedFile.path(), expected, body));
            }
            result.add(new UnitGenerator.Output(merged));
        }
        if (!invalid.isEmpty()) {
            invalidate(run, contract, invalid);
        }
        return List.copyOf(result);
    }

    /** Keep the round spent, but never replay patches against unrelated regenerated source. */
    private void invalidate(AgentRun run, BuildContract contract, Set<UnitGenerator.Delta> invalid) {
        leases.guarded(run.getProjectId(), () -> {
            var state = read(run, contract);
            var rounds = state.rounds().stream().map(round -> {
                var retained = round.files().stream().filter(file -> !invalid.contains(file)).toList();
                return retained.size() == round.files().size() ? round
                        : new Round(round.number(), round.diagnosticsRevision(), retained.isEmpty() ? "INVALIDATED" : round.state(), retained);
            }).toList();
            write(run, new State(state.request(), state.contractHash(), rounds));
        });
    }

    private State read(AgentRun run, BuildContract contract) {
        String expected = ContractIdentity.hash(contract, mapper);
        if (run.getRequestFingerprint() == null) {
            throw new IllegalStateException("Repair requires a durable accepted request");
        }
        if (!files.exists(run.getProjectId(), root(run), "journal.json")) {
            return new State(run.getRequestFingerprint(), expected, List.of());
        }
        try {
            String json = new String(files.readBytes(run.getProjectId(), root(run), "journal.json", 1_600_000), StandardCharsets.UTF_8);
            var envelope = mapper.readValue(json, Envelope.class);
            var state = envelope.state();
            if (!Objects.equals(state.request(), run.getRequestFingerprint()) || !expected.equals(state.contractHash()) || state.rounds().size() > 2
                    || !ContractStore.hash(mapper.writeValueAsString(state)).equals(envelope.hash())) {
                throw new IllegalStateException("Repair journal identity/integrity changed");
            }
            return state;
        } catch (RuntimeException invalid) {
            throw new IllegalStateException("Repair journal cannot prove recovery", invalid);
        }
    }

    private void replace(AgentRun run, State state, Round updated) {
        var rounds = new ArrayList<>(state.rounds());
        rounds.set(updated.number() - 1, updated);
        write(run, new State(state.request(), state.contractHash(), rounds));
    }

    private void write(AgentRun run, State state) {
        String json = mapper.writeValueAsString(new Envelope(ContractStore.hash(mapper.writeValueAsString(state)), state));
        if (json.getBytes(StandardCharsets.UTF_8).length > 1_600_000) {
            throw new IllegalArgumentException("Repair journal exceeds capacity");
        }
        files.writeText(run.getProjectId(), root(run), "journal.json", json);
    }

    private static String root(AgentRun run) {
        return ".codepal/repairs/" + run.getId();
    }
}
