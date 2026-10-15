package com.code.pal.agent.workspace;

import com.code.pal.agent.change.PromotionJournal;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;

class ProjectPathsPortabilityTest {
    @ParameterizedTest
    @ValueSource(strings = {".git./config", ".secrets /key", ".codepal./history", "a./b",
            "src /file.ts", "src/file.ts.", "src/file.ts ", "a.\\b", "folder.../file"})
    void trailingDotsAndSpacesCannotAliasProtectedOrOrdinaryPaths(String path) {
        assertThrows(IllegalArgumentException.class, () -> ProjectPaths.normalize(path, false));
        assertThrows(IllegalArgumentException.class, () -> ProjectPaths.agentPath(path, false));
        var proof = List.of(new PromotionJournal.FileState(path, "absent", "sha256:" + "a".repeat(64)));
        assertThrows(IllegalArgumentException.class, () -> PromotionJournal.validate(proof));
    }

    @ParameterizedTest
    @ValueSource(strings = {".", "src/./App.tsx", "folder..name/file.ts", "src/.hidden/file", "src/main.ts"})
    void normalDotSegmentsAndPortableNamesRemainUsable(String path) {
        assertDoesNotThrow(() -> ProjectPaths.normalize(path, true));
        assertDoesNotThrow(() -> ProjectPaths.agentPath(path, true));
    }
}
