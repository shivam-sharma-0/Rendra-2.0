package com.code.pal.agent.usage;

import com.code.pal.testsupport.CodexJpaFixture;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.JdbcTemplate;

import static org.junit.jupiter.api.Assertions.*;

@EnabledIfSystemProperty(named = "codepal.disposable.it", matches = "true")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class BudgetMigrationIT {
    private org.springframework.context.annotation.AnnotationConfigApplicationContext context;
    private JdbcTemplate jdbc;
    @BeforeAll void open() { context = CodexJpaFixture.open(Empty.class); jdbc = context.getBean(JdbcTemplate.class); }
    @AfterAll void close() { if (context != null) context.close(); }

    @Test void historicalRunAndLedgerTokensAreCountedOnce() {
        assertEquals(36L, jdbc.queryForObject("select sum(known_tokens) from ai_budget_periods where user_id=7", Long.class));
        assertEquals(30L, jdbc.queryForObject("select sum(prompt_tokens + completion_tokens) from agent_runs where user_id=7", Long.class));
        assertEquals(5, jdbc.queryForObject("select count(*) from ai_legacy_usage where user_id=7", Integer.class));
    }

    @Test void missingLegacyUsageRetainsEstimateAndAnUnknownMarker() {
        assertEquals(30L, jdbc.queryForObject("select sum(unknown_tokens) from ai_budget_periods where user_id=7", Long.class));
        assertEquals(1L, jdbc.queryForObject("select sum(unknown_calls) from ai_budget_periods where user_id=7", Long.class));
        assertEquals(66L, jdbc.queryForObject("select sum(charged_tokens) from ai_budget_periods where user_id=7", Long.class));
        assertEquals(0L, jdbc.queryForObject("select sum(reserved_tokens) from ai_budget_periods where user_id=7", Long.class));
    }

    @Test void reportedLegacyCallKeepsItsActualUtcDayAcrossMidnight() {
        assertEquals(0L, jdbc.queryForObject("select charged_tokens from ai_budget_periods where user_id=9 and period_start='2026-10-09'", Long.class));
        assertEquals(15L, jdbc.queryForObject("select charged_tokens from ai_budget_periods where user_id=9 and period_start='2026-10-10'", Long.class));
    }

    @Configuration static class Empty { }
}
