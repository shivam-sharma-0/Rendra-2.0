package com.code.pal.agent.validation;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ValidationOutputParserTest {
    private static final String ROOT = "/workspace/.codepal/changesets/run/candidate-build";

    @Test
    void nodeAsyncFramesWithAnOpeningBraceCannotHideTheActualUnlocatedError() {
        String output = """
                Error [ERR_MODULE_NOT_FOUND]: Cannot find package 'fixture-plugin' imported from vite.config.ts
                    at async onImport.tracePromise.__proto__ (node:internal/modules/esm/loader:681:26) {
                  code: 'ERR_MODULE_NOT_FOUND'
                }
                """;
        assertTrue(ValidationOutputParser.parse(output, ROOT, ValidationOperation.BUILD).isEmpty());
        assertTrue(ValidationOutputParser.summary(output, ROOT).contains("Cannot find package 'fixture-plugin'"));
    }

    @Test
    void viteErrorsKeepTheProblemAndDropTheStackTrace() {
        // Shape of a real `vite build` failure captured from an agent run.
        String output = """
                vite v7.1.3 building for production...
                error during build:
                [vite:build-html] Unable to parse HTML; parse5 error code missing-whitespace-between-attributes
                 at /workspace/.codepal/changesets/run/candidate-build/index.html:2:13
                file: /workspace/.codepal/changesets/run/candidate-build/index.html:2:13
                    at Parser.onParseError (file:///workspace/.codepal/deps/32e9/node_modules/vite/dist/node/chunks/dep-BK3b2jBa.js:35097:7)
                    at Parser.parse (file:///workspace/.codepal/deps/32e9/node_modules/vite/dist/node/chunks/dep-D-7KCb9p.js:4886:26)
                    at Tokenizer._err (file:///workspace/.codepal/deps/32e9/node_modules/vite/dist/node/chunks/dep-D-7KCb9p.js:1155:89)
                """;

        List<ProjectDiagnostic> diagnostics = ValidationOutputParser.parse(output, ROOT, ValidationOperation.BUILD);

        assertTrue(diagnostics.stream().allMatch(item -> item.file().equals("index.html")), diagnostics.toString());
        ProjectDiagnostic first = diagnostics.getFirst();
        assertEquals(2, first.line());
        assertEquals(13, first.column());
        assertTrue(first.message().contains("Unable to parse HTML"), first.message());
    }

    @Test
    void rolldownBoxLabelsAreAttributedToTheirFile() {
        // Shape of a Rolldown/Oxc parse failure (miette labels) from a benchmark build.
        String output = """
                [PARSE_ERROR] Error: Unexpected token. Did you mean `{'>'}` or `&gt;`?
                   ╭─[ src/pages/home.tsx:2:14 ]
                   │
                 2 │   return <main>></main>
                   ╰────
                [PARSE_ERROR] Error: Expected a semicolon
                   ,-[ /workspace/run/candidate-build/src/pages/menu.tsx:3:1 ]
                """;

        List<ProjectDiagnostic> diagnostics = ValidationOutputParser.parse(output, "/workspace/run/candidate-build",
                ValidationOperation.BUILD);

        assertEquals(List.of("src/pages/home.tsx", "src/pages/menu.tsx"), diagnostics.stream().map(ProjectDiagnostic::file).toList());
        assertEquals(2, diagnostics.getFirst().line());
        assertTrue(diagnostics.getFirst().message().contains("Unexpected token"), diagnostics.getFirst().message());
        assertTrue(diagnostics.get(1).message().contains("Expected a semicolon"), diagnostics.get(1).message());
    }

    @Test
    void typeScriptErrorsInBothFormats() {
        String output = """
                src/App.tsx(4,7): error TS2322: Type 'string' is not assignable to type 'number'.
                /workspace/.codepal/changesets/run/candidate-build/src/main.tsx:3:1 - error TS2304: Cannot find name 'foo'.
                """;

        List<ProjectDiagnostic> diagnostics = ValidationOutputParser.parse(output, ROOT, ValidationOperation.TYPE_CHECK);

        assertEquals(2, diagnostics.size());
        assertEquals("src/App.tsx", diagnostics.get(0).file());
        assertEquals("TS2322", diagnostics.get(0).code());
        assertEquals("src/main.tsx", diagnostics.get(1).file());
        assertTrue(diagnostics.get(1).message().contains("Cannot find name"));
    }

    @Test
    void anUnlocatedFailureIsSummarizedWithoutBannersOrFrames() {
        String output = """
                > counter@0.0.0 build
                > tsc -b && vite build
                error during build:
                Error: Cannot find module '@vitejs/plugin-react'
                    at Module._resolveFilename (node:internal/modules/cjs/loader:1225:15)
                npm error Lifecycle script `build` failed with error:
                """;

        assertEquals("Error: Cannot find module '@vitejs/plugin-react'", ValidationOutputParser.summary(output, ROOT));
        assertTrue(ValidationOutputParser.parse(output, ROOT, ValidationOperation.BUILD).isEmpty());
    }
}
