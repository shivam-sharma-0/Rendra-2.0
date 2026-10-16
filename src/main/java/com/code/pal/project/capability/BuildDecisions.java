package com.code.pal.project.capability;

import com.code.pal.agent.config.AgentProperties;
import com.code.pal.agent.workspace.ProjectFiles;
import com.code.pal.project.domain.RequirementQuestion;
import com.code.pal.project.domain.RequirementStatus;
import com.code.pal.project.exception.WorkflowStageException;
import com.code.pal.project.repository.RequirementSessionRepository;
import com.code.pal.project.supabase.SupabaseConnectionRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.ObjectMapper;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.List;
import java.util.UUID;

/** Server-owned choices. Labels, model text, files and environment names never activate a connector. */
@Service
public class BuildDecisions {
    public static final String STORAGE_KEY = "platform_storage";
    public static final String AUTH_KEY = "platform_auth";
    public enum Storage { NONE, BROWSER, SUPABASE, MERN }
    public enum Auth { NONE, ACCOUNTS }
    public record Choice(int version, Storage storage, Auth auth, String connectionRevision) {}
    /** Supported/configured is deliberately distinct from live database and behavior verification. */
    public record Available(boolean mernConfigured, String supabaseRevision) {
        public boolean supabaseConnected() { return supabaseRevision != null; }
    }
    public record State(Choice choice, Available available, String problem) {
        public boolean blocked() { return problem != null; }
        public String prompt() {
            String choiceText = choice == null ? "Storage/auth are not approved; do not guess a cloud provider."
                    : "Approved storage=" + choice.storage() + "; auth=" + choice.auth() + ".";
            return "<platform-build-decision version=\"1\">\n" + choiceText
                    + "\nMERN configured=" + available.mernConfigured()
                    + "; Supabase connection recorded=" + available.supabaseConnected()
                    + ". Live database readiness and application behavior still require validation."
                    + (blocked() ? "\nBUILD BLOCKED: " + problem : "")
                    + "\nUse browser persistence only for BROWSER; never substitute it for server data/accounts."
                    + "\n</platform-build-decision>\n";
        }
    }

    private final RequirementSessionRepository sessions;
    private final SupabaseConnectionRepository connections;
    private final AgentProperties properties;
    private final ObjectMapper mapper;
    private final org.springframework.jdbc.core.JdbcTemplate jdbc;

    public BuildDecisions(RequirementSessionRepository sessions, SupabaseConnectionRepository connections,
                          AgentProperties properties, ObjectMapper mapper) {
        this(sessions, connections, properties, mapper, null);
    }
    @org.springframework.beans.factory.annotation.Autowired
    public BuildDecisions(RequirementSessionRepository sessions, SupabaseConnectionRepository connections,
                          AgentProperties properties, ObjectMapper mapper, org.springframework.jdbc.core.JdbcTemplate jdbc) {
        this.sessions = sessions;
        this.connections = connections;
        this.properties = properties;
        this.mapper = mapper;
        this.jdbc = jdbc;
    }

    private com.code.pal.project.supabase.SelfHostedSupabase selfHosted;

    @org.springframework.beans.factory.annotation.Autowired(required = false)
    public void useSelfHosted(com.code.pal.project.supabase.SelfHostedSupabase selfHosted) {
        this.selfHosted = selfHosted;
    }

    @Transactional(readOnly = true)
    public Available available(UUID projectId) {
        var sandbox = properties.getSandbox();
        boolean mern = sandbox.isMernEnabled() && (sandbox.isRequired()
                ? nonBlank(sandbox.getAppsDatabaseImage()) && nonBlank(sandbox.getAppsNetwork())
                : sandbox.isAllowHostExecution() && nonBlank(sandbox.getLocalAppsDatabaseUri()));
        // The projection excludes URLs and both public/private key values.
        // A connection to the operator's own instance is usable only where the operator enabled its SQL path.
        String revision = connections.capability(projectId)
                .filter(connection -> connection.getMode() != com.code.pal.project.supabase.SupabaseConnection.Mode.SELF_HOSTED
                        || selfHosted != null && selfHosted.operatorSqlEnabled())
                .map(connection -> digest(
                        connection.getProjectRef() + "/" + connection.getMode() + "/" + connection.getUpdatedAt())).orElse(null);
        return new Available(mern, revision);
    }

    public Choice approve(List<RequirementQuestion> questions, Available available) {
        String storage = selection(questions, STORAGE_KEY);
        String auth = selection(questions, AUTH_KEY);
        if (storage == null || auth == null) throw new WorkflowStageException("Choose storage and sign-in before approval");
        Choice choice;
        try {
            choice = new Choice(1, Storage.valueOf(storage), Auth.valueOf(auth),
                    "SUPABASE".equals(storage) ? available.supabaseRevision() : null);
        } catch (IllegalArgumentException invalid) {
            throw new WorkflowStageException("Choose one of the supported storage and sign-in options");
        }
        String problem = problem(choice, available);
        if (problem != null) throw new WorkflowStageException(problem);
        return choice;
    }

    public String encode(Choice choice) { return mapper.writeValueAsString(choice); }

    @Transactional(readOnly = true)
    public State state(UUID projectId) {
        Available available = available(projectId);
        var session = sessions.findByProjectId(projectId).orElse(null);
        if (session == null) return new State(null, available, null);
        if (session.getStatus() != RequirementStatus.APPROVED) return new State(null, available,
                "Requirements are not approved; confirm storage and sign-in before building");
        if (!nonBlank(session.getBuildDecisionJson())) {
            return new State(null, available, "Legacy approval needs an explicit storage and sign-in choice; reopen requirements");
        }
        try {
            Choice choice = mapper.readValue(session.getBuildDecisionJson(), Choice.class);
            String problem = problem(choice, available);
            if (jdbc != null && problem == null) {
                if (session.getApprovedRevision() == null || session.getApprovedRevision() != session.getAnswerRevision()) {
                    problem = "Approval does not match the current answer revision; confirm requirements again";
                } else if (session.getProjectionId() == null) problem = "Legacy approval needs its requirements files projected; confirm requirements again";
                else if (!"READY".equals(jdbc.queryForObject("select state from requirement_projections where id=?", String.class, session.getProjectionId()))) {
                    problem = "Approval files are pending; the build will wait for verified projection";
                }
            }
            return new State(choice, available, problem);
        } catch (RuntimeException invalid) {
            return new State(null, available, "Approved storage decision is invalid; reopen requirements");
        }
    }

    @Transactional(readOnly = true)
    public boolean requiresConfirmation(UUID projectId) {
        var session = sessions.findByProjectId(projectId).orElse(null);
        if (session == null || session.getStatus() != RequirementStatus.APPROVED) return false;
        if (!nonBlank(session.getBuildDecisionJson())) return true;
        try {
            return problem(mapper.readValue(session.getBuildDecisionJson(), Choice.class), available(projectId)) != null
                    || (jdbc != null && (session.getApprovedRevision() == null || session.getProjectionId() == null
                    || session.getApprovedRevision() != session.getAnswerRevision()));
        } catch (RuntimeException invalid) { return true; }
    }

    public void requireStarter(UUID projectId, String stack) {
        State state = state(projectId);
        if (state.blocked()) throw new WorkflowStageException(state.problem());
        if ("mern".equals(stack) && !state.available().mernConfigured()) {
            throw new WorkflowStageException("MERN is not configured on this server");
        }
        if (state.choice() != null && (state.choice().storage() == Storage.MERN) != "mern".equals(stack)) {
            throw new WorkflowStageException("Starter does not match approved storage=" + state.choice().storage());
        }
    }

    /** Deterministic capability gate; functional auth/CRUD verification is a separate required check. */
    public String validate(UUID projectId, ProjectFiles files, String root) {
        State state = state(projectId);
        if (state.blocked()) return state.problem();
        Choice choice = state.choice();
        if (choice == null) return null;
        boolean server = files.regularFile(projectId, root, "server/package.json");
        if (choice.storage() == Storage.MERN && (!server || !files.regularFile(projectId, root, "client/package.json"))) {
            return "Approved MERN storage requires client and server manifests; browser-only data is insufficient";
        }
        if (choice.storage() != Storage.MERN && server) return "Generated MERN server conflicts with approved storage=" + choice.storage();
        for (String manifest : List.of("package.json", "client/package.json", "server/package.json")) {
            if (!files.regularFile(projectId, root, manifest)) continue;
            // Bounded manifests only; dependency declarations are untrusted data.
            if (files.size(projectId, root, manifest) > 256_000) return "Manifest exceeds capability validation limit";
            try {
                var json = mapper.readTree(files.readText(projectId, root, manifest));
                boolean sdk = json.path("dependencies").has("@supabase/supabase-js")
                        || json.path("devDependencies").has("@supabase/supabase-js");
                if (sdk && choice.storage() != Storage.SUPABASE) return "Supabase SDK conflicts with approved storage=" + choice.storage();
            } catch (RuntimeException invalid) { return "Manifest cannot be read for capability validation"; }
        }
        return null;
    }

    static String problem(Choice choice, Available available) {
        if (choice == null || choice.version() != 1 || choice.storage() == null || choice.auth() == null) return "Unsupported storage decision version";
        if (choice.auth() == Auth.ACCOUNTS && (choice.storage() == Storage.NONE || choice.storage() == Storage.BROWSER)) {
            return "Real sign-in requires connected Supabase or a configured MERN backend; browser storage cannot provide accounts";
        }
        if (choice.storage() == Storage.MERN && !available.mernConfigured()) return "MERN is not configured on this server";
        if (choice.storage() == Storage.SUPABASE && (!available.supabaseConnected()
                || !available.supabaseRevision().equals(choice.connectionRevision()))) {
            return "Supabase is disconnected or changed; confirm the storage decision again";
        }
        return null;
    }

    private static String selection(List<RequirementQuestion> questions, String key) {
        var matches = questions.stream().filter(q -> key.equals(q.getQuestionKey())).toList();
        if (matches.size() != 1 || nonBlank(matches.getFirst().getOtherText())) return null;
        return matches.getFirst().getSelectedOption();
    }
    private static boolean nonBlank(String value) { return value != null && !value.isBlank(); }
    private static String digest(String value) {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8))); }
        catch (java.security.NoSuchAlgorithmException impossible) { throw new IllegalStateException(impossible); }
    }
}
