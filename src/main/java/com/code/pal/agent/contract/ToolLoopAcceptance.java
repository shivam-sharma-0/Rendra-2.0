package com.code.pal.agent.contract;

import com.code.pal.agent.validation.BehaviorCriteria;
import com.code.pal.agent.validation.BehaviorCriteria.BrowserAction;
import com.code.pal.agent.validation.BehaviorCriteria.BrowserKind;
import com.code.pal.project.spec.ApprovedSpec;
import com.code.pal.agent.prompt.PromptLoader;
import com.code.pal.agent.prompt.PromptLoader.Prompt;
import tools.jackson.databind.ObjectMapper;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

/** Pure approved-spec adapter for the tool loop; no planner, model, persistence or mutation authority. */
public final class ToolLoopAcceptance {
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final int MAX_GUIDANCE_BYTES = 24_000;
    private static final Set<BrowserKind> ASSERTIONS = Set.of(
            BrowserKind.ASSERT_TEXT, BrowserKind.ASSERT_COUNT, BrowserKind.ASSERT_EXISTS, BrowserKind.ASSERT_ROUTE);

    public record Hook(String testId, String originalSelector) {
    }

    public record Compiled(BehaviorCriteria criteria, List<Hook> requiredHooks) {
        public Compiled {
            Objects.requireNonNull(criteria);
            requiredHooks = List.copyOf(requiredHooks);
        }
    }

    private record Guidance(List<Hook> requiredHooks, List<BrowserAction> checks) {
    }

    private ToolLoopAcceptance() {
    }

    /** Empty only for a missing legacy spec or recipes with no observable assertion; invalid requirements are never discarded. */
    public static Optional<Compiled> from(ApprovedSpec approved) {
        return compile(approved, false);
    }

    /** Browser evidence only; callers must also run the independent isolated schema/auth/data adapter. */
    public static Optional<Compiled> browserForSupabase(ApprovedSpec approved) {
        if (approved == null || approved.desired().storage() != ApprovedSpec.Storage.SUPABASE) {
            throw new IllegalArgumentException("Supabase browser acceptance requires an approved cloud storage choice");
        }
        return compile(approved, true);
    }

    private static Optional<Compiled> compile(ApprovedSpec approved, boolean supabaseAdapter) {
        if (approved == null) {
            return Optional.empty();
        }
        if (approved.readiness() != ApprovedSpec.Readiness.READY) {
            throw new IllegalArgumentException("Tool-loop acceptance requires a ready approved spec");
        }
        if (!supabaseAdapter && (approved.desired().storage() == ApprovedSpec.Storage.SUPABASE || approved.desired().auth() != ApprovedSpec.Auth.NONE)) {
            throw new IllegalArgumentException("Tool-loop connector acceptance requires its dedicated adapter; browser checks cannot certify cloud data or accounts");
        }
        var source = SpecAcceptance.compileForToolLoop(approved);
        if (source.criteria().browser().stream().noneMatch(action -> ASSERTIONS.contains(action.kind()))) {
            return Optional.empty();
        }
        var hooks = source.targets().stream().map(target -> new Hook(target.testId(), target.originalSelector())).toList();
        var compiled = new Compiled(source.criteria(), hooks);
        // Check admission before the caller installs immutable criteria, never truncate approved checks or hook guidance.
        promptSection(compiled);
        return Optional.of(compiled);
    }

    /** Include both hooks and the exact executable recipe so navigation, expected values and reloads cannot drift apart. */
    public static String promptSection(Compiled compiled) {
        Objects.requireNonNull(compiled);
        String data = JSON.writeValueAsString(new Guidance(compiled.requiredHooks(), compiled.criteria().browser()))
                .replace("<", "\\u003c").replace(">", "\\u003e").replace("&", "\\u0026");
        String section = PromptLoader.load(Prompt.TOOL_LOOP_ACCEPTANCE) + "\n" + data + "\n</tool_loop_acceptance>";
        if (section.getBytes(StandardCharsets.UTF_8).length > MAX_GUIDANCE_BYTES) {
            throw new IllegalArgumentException("Tool-loop acceptance guidance exceeds its 24000-byte capacity; approved checks were not truncated");
        }
        return section;
    }
}
