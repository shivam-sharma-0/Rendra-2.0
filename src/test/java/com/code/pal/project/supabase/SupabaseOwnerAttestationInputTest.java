package com.code.pal.project.supabase;

import com.code.pal.shared.config.DataCipher;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;

class SupabaseOwnerAttestationInputTest {
    private final JdbcTemplate jdbc = mock(JdbcTemplate.class);
    private final PlatformTransactionManager transactions = mock(PlatformTransactionManager.class);
    private final SupabaseMigrationLedger ledger = new SupabaseMigrationLedger(jdbc, transactions, new DataCipher("synthetic-input-key-0123456789"));
    private final UUID project = UUID.randomUUID();
    private final UUID operation = UUID.randomUUID();
    private final String hash = "sha256:" + "a".repeat(64);

    @Test
    void malformedAndDuplicateDecisionsFailBeforeAnyDatabaseOrAuthorizationWork() {
        UUID attempt = UUID.randomUUID();
        var invalid = new ArrayList<List<SupabaseOwnerAttestations.ResolutionDecision>>();
        invalid.add(null);
        invalid.add(List.of());
        var nullChoice = new ArrayList<SupabaseOwnerAttestations.ResolutionDecision>();
        nullChoice.add(null);
        invalid.add(nullChoice);
        invalid.add(List.of(new SupabaseOwnerAttestations.ResolutionDecision(null, "APPLIED")));
        invalid.add(List.of(new SupabaseOwnerAttestations.ResolutionDecision(attempt, null)));
        invalid.add(List.of(new SupabaseOwnerAttestations.ResolutionDecision(attempt, "RETRY")));
        invalid.add(List.of(new SupabaseOwnerAttestations.ResolutionDecision(attempt, "APPLIED"),
                new SupabaseOwnerAttestations.ResolutionDecision(attempt, "NOT_APPLIED")));
        for (var choices : invalid) {
            assertThrows(SupabaseMigrationException.class,
                    () -> ledger.resolveRecovery(7L, project, operation, hash, "one", choices, true, () -> { throw new AssertionError("invalid input reached authorization"); }));
        }
        verifyNoInteractions(jdbc, transactions);
    }

    @Test
    void missingReviewKeyAndOwnerConfirmationNeverReachTheDatabase() {
        assertThrows(SupabaseMigrationException.class, () -> ledger.adoptLegacy(7L, project, hash, "one", false, () -> {}));
        assertThrows(SupabaseMigrationException.class, () -> ledger.adoptLegacy(7L, project, null, "one", true, () -> {}));
        assertThrows(SupabaseMigrationException.class, () -> ledger.adoptLegacy(7L, project, hash, null, true, () -> {}));
        assertThrows(SupabaseMigrationException.class, () -> ledger.adoptLegacy(7L, project, hash, "bad\nkey", true, () -> {}));
        verifyNoInteractions(jdbc, transactions);
    }
}
