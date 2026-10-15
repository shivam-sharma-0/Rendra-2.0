package com.code.pal.agent.prompt;

import com.code.pal.agent.prompt.PromptLoader.Prompt;
import com.code.pal.project.capability.BuildDecisions;

/**
 * BUILD prompt with only the stack section the run needs. The stack comes from server-owned facts: the
 * project's existing files and the approved storage decision, never from project text.
 */
public final class BuildPrompt {
    public enum Stack {
        STATIC(Prompt.STACK_STATIC),
        VITE(Prompt.STACK_VITE),
        SUPABASE(Prompt.STACK_SUPABASE),
        MERN(Prompt.STACK_MERN),
        UNDECIDED(Prompt.STACK_UNDECIDED);

        private final Prompt prompt;

        Stack(Prompt prompt) {
            this.prompt = prompt;
        }
    }

    /** Files that reveal an existing project's stack, checked in the project being built. */
    public record ProjectShape(boolean serverPackage, boolean packageJson, boolean indexHtml) {
    }

    private BuildPrompt() {
    }

    /** The approved storage decision wins; files only tell static, Vite and legacy MERN apart without one. */
    public static Stack stack(ProjectShape shape, BuildDecisions.Storage storage) {
        if (storage == BuildDecisions.Storage.MERN) {
            return Stack.MERN;
        }
        if (storage == BuildDecisions.Storage.SUPABASE) {
            return Stack.SUPABASE;
        }
        if (shape.serverPackage()) {
            return Stack.MERN;
        }
        if (shape.packageJson()) {
            return Stack.VITE;
        }
        if (shape.indexHtml()) {
            return Stack.STATIC;
        }
        return Stack.UNDECIDED;
    }

    public static String compose(Stack stack) {
        return PromptLoader.load(Prompt.MODE_BUILD) + "\n\n" + PromptLoader.load(stack.prompt);
    }
}
