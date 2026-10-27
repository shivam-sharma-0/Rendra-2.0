package com.code.pal.agent.contract;

import com.code.pal.agent.validation.*;
import com.code.pal.project.domain.ProjectType;
import org.junit.jupiter.api.Test;

import java.util.*;

import static org.junit.jupiter.api.Assertions.*;

class RelativeImportsTest {
    private ProjectDiagnostic missing(String file, String module) {
        return new ProjectDiagnostic(file, 1, 1, DiagnosticSeverity.ERROR,
                "Cannot find module '" + module + "' or its corresponding type declarations.", "compiler", "TS2307");
    }

    @Test void uniqueSourceAndCssTargetsAreRewrittenWithoutChangingQuotedExamplesOrComments() {
        String file = "client/src/pages/Home.tsx";
        String source = "// import '../theme.css'\nconst example = \"import '../theme.css'\";\nimport '../theme.css';\nimport {Task} from '../types';";
        String fixed = RelativeImports.fix(file, source, Set.of("client/src/rendra/id/theme.css", "client/src/rendra/id/types.ts"),
                List.of(missing(file, "../theme.css"), missing(file, "../types")));
        assertTrue(fixed.contains("// import '../theme.css'"));
        assertTrue(fixed.contains("\"import '../theme.css'\""));
        assertTrue(fixed.contains("import '../rendra/id/theme.css'"));
        assertTrue(fixed.contains("from '../rendra/id/types'"));
    }

    @Test void ambiguousMissingBareAndOtherPackageTargetsStayWithModelRepair() {
        String file = "server/src/routes/todos.ts";
        String source = "import {models} from '../models'; import X from 'uninstalled';";
        for (var paths : List.of(Set.<String>of(), Set.of("client/src/models.ts"),
                Set.of("server/src/one/models.ts", "server/src/two/models.ts"))) {
            assertEquals(source, RelativeImports.fix(file, source, paths, List.of(missing(file, "../models"), missing(file, "uninstalled"))));
        }
        assertEquals(source, RelativeImports.fix(file, source, Set.of("server/src/models.ts"),
                List.of(new ProjectDiagnostic(file, 1, 1, DiagnosticSeverity.ERROR, "Cannot find module '../models'", "compiler", "TS2304"))));
    }

    @Test void theBundlersUnresolvedSideEffectCssImportIsRewrittenToTheUniqueExistingFile() {
        // Live E2E 2026-10-27: tsc accepted the CSS import, so only the build reported it, with no TS code.
        String file = "client/src/pages/EditTodo.tsx", id = "2a428af9-b4a2-40f7-9798-e5df3cd2c9ec";
        var unresolved = new ProjectDiagnostic(file, 4, null, DiagnosticSeverity.ERROR,
                "[UNRESOLVED_IMPORT] Could not resolve '../../rendra/" + id + "/theme.css' in " + file, "build", null);
        assertTrue(RelativeImports.missingModule(unresolved));
        assertEquals("import '../rendra/" + id + "/theme.css'", RelativeImports.fix(file, "import '../../rendra/" + id + "/theme.css'",
                Set.of("client/src/rendra/" + id + "/theme.css"), List.of(unresolved)));
        assertFalse(RelativeImports.missingModule(new ProjectDiagnostic(file, 4, null, DiagnosticSeverity.ERROR,
                "Could not resolve '../theme.css'", "build", null)));
    }

    @Test void oneUndiagnosedPassFixesEveryUnresolvedCoordinatorImportButNotALaterWavesFile() {
        // Live E2E 2026-10-27: each off-by-one import cost a type-check plus a build round until the repair limit ran out.
        String file = "client/src/pages/EditTask.tsx", root = "client/src/rendra/id/";
        String source = "import { api } from '../../rendra/id/api'\nimport type { Task } from '../../rendra/id/types'\n"
                + "import '../../rendra/id/theme.css'\nimport Card from '../components/Card'\nimport Later from './Later'\n"
                + "import { auth } from '../rendra/id/auth-client'\nimport { useState } from 'react'\n";
        var coordinator = Set.of(root + "api.ts", root + "types.ts", root + "theme.css", root + "auth-client.ts");
        var existing = new HashSet<>(coordinator);
        existing.addAll(Set.of("client/src/components/Card.tsx", "client/src/Later.tsx"));
        String fixed = RelativeImports.fixAll(file, source, existing, coordinator);
        assertEquals(source.replace("'../../rendra/id/", "'../rendra/id/"), fixed);
        assertEquals(fixed, RelativeImports.fixAll(file, fixed, existing, coordinator));
        assertEquals(Set.of("../../rendra/id/api", "../../rendra/id/types", "../../rendra/id/theme.css", "../components/Card",
                "./Later", "../rendra/id/auth-client", "react"), ContractImports.importedModules(source));
    }

    @Test void nodeJsSpecifierAndDirectoryIndexAreResolvedToExistingSource() {
        assertEquals("export {models} from '../rendra/id/models.js'", RelativeImports.fix("server/src/routes/tasks.ts",
                "export {models} from '../models.js'", Set.of("server/src/rendra/id/models.ts"), List.of(missing("server/src/routes/tasks.ts", "../models.js"))));
        assertEquals("import Card from '../components/Card/index'", RelativeImports.fix("src/pages/Home.tsx",
                "import Card from './Card'", Set.of("src/components/Card/index.tsx"), List.of(missing("src/pages/Home.tsx", "./Card"))));
    }

    @Test void coordinatorAppliesOnlyOwnedFixesAsAnAtomicRevisionAndNextAttemptIsANoop() {
        var fixture = new ContractCoordinatorTest();
        var contract = ContractFixtures.mern();
        var ctx = fixture.context(fixture.run(ProjectType.BUILD));
        ctx.plan().revise(List.of("Implement tasks"), "Fixture");
        String file = "server/src/items.ts";
        fixture.project.files.writeText(ctx.projectId(), ctx.root(), file, "import {models} from './models'");
        fixture.project.files.writeText(ctx.projectId(), ctx.root(), contract.serverRoot() + "models.ts", "export const models = {}");
        int revision = fixture.changes.view(ctx).revision();
        var report = new ValidationReport(ValidationOperation.TYPE_CHECK, true, false, false, false, 1, "tsc", "", List.of(missing(file, "./models")),
                List.of(), 0, 3, revision, "Failed", List.of());
        assertTrue(fixture.coordinator.fixRelativeImports(ctx, contract, contract.units(), report));
        assertTrue(fixture.project.files.readText(ctx.projectId(), ctx.root(), file).contains("./rendra/" + contract.id() + "/models"));
        assertEquals(revision + 1, fixture.changes.view(ctx).revision());
        assertFalse(fixture.coordinator.fixRelativeImports(ctx, contract, contract.units(), report));
    }

    @Test void repairEvidenceContainsOnlyExistingScopedPathsAndDependencySections() {
        var fixture = new ContractCoordinatorTest();
        var contract = ContractFixtures.mern();
        var ctx = fixture.context(fixture.run(ProjectType.BUILD));
        fixture.project.files.writeText(ctx.projectId(), ctx.root(), "client/src/App.tsx", "export default () => null");
        fixture.project.files.writeText(ctx.projectId(), ctx.root(), "server/src/items.ts", "export {}");
        fixture.project.files.writeText(ctx.projectId(), ctx.root(), "client/package.json", "{\"dependencies\":{\"react-router-dom\":\"7.18.4\"},\"privateConfig\":\"DO_NOT_SEND\"}");
        fixture.project.files.writeText(ctx.projectId(), ctx.root(), ".env", "DO_NOT_SEND");
        fixture.project.files.writeText(ctx.projectId(), ctx.root(), "unrelated.ts", "DO_NOT_SEND");
        var evidence = RepairProjectContext.capture(fixture.project.files, ctx, contract, List.of(contract.units().getLast()), fixture.mapper);
        assertEquals(List.of("client/src/App.tsx", "server/src/items.ts"), evidence.get("fileTree"));
        String json = fixture.mapper.writeValueAsString(evidence);
        assertTrue(json.contains("react-router-dom"));
        assertFalse(json.contains("DO_NOT_SEND"));
        assertFalse(json.contains("unrelated.ts"));
    }
}
