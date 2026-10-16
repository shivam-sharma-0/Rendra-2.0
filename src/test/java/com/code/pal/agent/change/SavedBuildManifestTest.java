package com.code.pal.agent.change;

import com.code.pal.agent.workspace.LocalProject;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class SavedBuildManifestTest {
    private final LocalProject project = LocalProject.create();

    @Test
    void capturesSourceAndBriefButNotSecretsDependenciesOrPrivateHistory() {
        project.write("src/App.tsx", "app").write("RENDRA.md", "brief").write(".env.example", "PUBLIC=")
                .write(".env", "private").write("client/.env.local", "private")
                .write(".codepal/history/x/file", "private").write("node_modules/pkg/index.js", "dependency")
                .write("dist/index.html", "output").write(".secrets/token", "secret");
        var manifest = SavedBuildManifest.scan(project.files, project.projectId, ".");
        assertEquals(List.of(".env.example", "RENDRA.md", "src/App.tsx"),
                manifest.files().stream().map(SavedBuildManifest.File::path).toList());
    }

    @Test
    void digestIsIndependentOfListOrderAndChangesForAddedDeletedOrEditedCode() {
        project.write("a.txt", "a").write("b.txt", "b");
        var original = SavedBuildManifest.scan(project.files, project.projectId, ".");
        var reversed = new SavedBuildManifest(List.of(original.files().get(1), original.files().getFirst()));
        assertEquals(original.digest(), reversed.digest());
        project.write("a.txt", "edited");
        assertNotEquals(original.digest(), SavedBuildManifest.scan(project.files, project.projectId, ".").digest());
        project.write("a.txt", "a").write("c.txt", "new");
        assertNotEquals(original.digest(), SavedBuildManifest.scan(project.files, project.projectId, ".").digest());
        project.files.delete(project.projectId, ".", "c.txt");
        project.files.delete(project.projectId, ".", "b.txt");
        assertNotEquals(original.digest(), SavedBuildManifest.scan(project.files, project.projectId, ".").digest());
    }

    @Test
    void rejectsProtectedDuplicateAndNonCanonicalManifestPaths() {
        String hash = "sha256:" + "a".repeat(64);
        for (String path : List.of(".codepal/x", "../x", "GIT~1/x", ".env.local", "a/./b", "./x")) {
            assertThrows(IllegalArgumentException.class, () -> new SavedBuildManifest(
                    List.of(new SavedBuildManifest.File(path, hash))));
        }
        assertThrows(IllegalArgumentException.class, () -> new SavedBuildManifest(List.of(
                new SavedBuildManifest.File("App.tsx", hash), new SavedBuildManifest.File("app.tsx", hash))));
    }

    @Test
    void refusesAnOverBudgetCaptureInsteadOfReturningATruncatedVersion() {
        project.write("large.txt", "x".repeat(SavedBuildManifest.MAX_FILE_BYTES + 1));
        assertThrows(IllegalStateException.class, () -> SavedBuildManifest.scan(project.files, project.projectId, "."));
    }
}
