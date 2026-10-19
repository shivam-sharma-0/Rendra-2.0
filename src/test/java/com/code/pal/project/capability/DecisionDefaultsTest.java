package com.code.pal.project.capability;

import com.code.pal.project.capability.BuildDecisions.Auth;
import com.code.pal.project.capability.BuildDecisions.Storage;
import com.code.pal.project.capability.DecisionDefaults.Inferred;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

class DecisionDefaultsTest {
    @ParameterizedTest
    @CsvSource(delimiter = '|', textBlock = """
            Build a static website. Use static HTML/CSS, no framework, accounts, backend or database.
            Build a static website without accounts, backend, and database.
            Build a static HTML page, no database, backend or accounts.
            """)
    void negativeCapabilityListsCannotInventAccountsOrAnUnrequestedBackend(String prompt) {
        assertEquals(new Inferred(Storage.NONE, Auth.NONE), DecisionDefaults.infer(prompt,
                new BuildDecisions.Available(true, "connected"),
                new CapabilityProposal(CapabilityProposal.Persistence.SERVER, true)));
    }

    @Test
    void aLaterPositiveAccountRequirementIsNotErasedWithAnEarlierNegativeList() {
        assertEquals(new Inferred(Storage.MERN, Auth.ACCOUNTS), DecisionDefaults.infer(
                "Build an app without payments, backend or database. Add accounts with a server after all.",
                new BuildDecisions.Available(true, null), null));
    }

    @ParameterizedTest
    @CsvSource(delimiter = '|', textBlock = """
            Build an appointment booking website where customers reserve slots
            Build a customer CRM with contacts and a sales pipeline
            Build an online store with a cart and checkout
            """)
    void businessRecordsNeedARealBackend(String prompt) {
        assertEquals(new Inferred(null, null), DecisionDefaults.infer(prompt, false));
        assertEquals(new Inferred(Storage.SUPABASE, Auth.NONE), DecisionDefaults.infer(prompt, true));
        assertEquals(new Inferred(Storage.MERN, Auth.NONE), DecisionDefaults.infer(prompt,
                new BuildDecisions.Available(true, null), null));
    }

    @Test
    void semanticProposalUsesAvailableCapabilitiesWithoutGrantingAnUnavailableProvider() {
        var proposal = new CapabilityProposal(CapabilityProposal.Persistence.SERVER, true);
        assertEquals(new Inferred(null, null), DecisionDefaults.infer("Create a group workspace",
                new BuildDecisions.Available(false, null), proposal));
        assertEquals(new Inferred(Storage.MERN, Auth.ACCOUNTS), DecisionDefaults.infer("Create a group workspace",
                new BuildDecisions.Available(true, null), proposal));
        assertEquals(new Inferred(null, null), DecisionDefaults.infer("Use Supabase for shared notes",
                new BuildDecisions.Available(true, null), proposal));
    }

    @Test
    void explicitLocalAndNoAccountChoicesOverrideTheProposal() {
        var proposal = new CapabilityProposal(CapabilityProposal.Persistence.SERVER, false);
        assertEquals(new Inferred(Storage.BROWSER, Auth.NONE), DecisionDefaults.infer("Build a browser-only booking demo",
                new BuildDecisions.Available(true, "connected"), proposal));
        assertEquals(new Inferred(Storage.SUPABASE, Auth.NONE), DecisionDefaults.infer("Shared notes without login",
                new BuildDecisions.Available(true, "connected"),
                new CapabilityProposal(CapabilityProposal.Persistence.SERVER, true)));
    }

    @Test
    void malformedModelProposalsAreNotCapabilities() {
        var mapper = new tools.jackson.databind.ObjectMapper();
        assertNull(CapabilityProposal.from(mapper.readTree("{\"persistence\":\"MERN\",\"accounts\":true}")));
        assertNull(CapabilityProposal.from(mapper.readTree("{\"persistence\":\"SERVER\",\"accounts\":\"yes\"}")));
    }
    @ParameterizedTest
    @CsvSource(delimiter = '|', textBlock = """
            A landing page for my bakery                      | false | NONE     | NONE
            ek calculator banao                               | false | NONE     | NONE
            a portfolio for my design team                    | false | NONE     | NONE
            a todo app that remembers my tasks                | true  | SUPABASE | NONE
            mujhe ek expense tracker chahiye                  | true  | SUPABASE | NONE
            a notes app without login                         | true  | SUPABASE | NONE
            a notes app, login nahi chahiye                   | true  | SUPABASE | NONE
            a team task board where members sign in           | true  | SUPABASE | ACCOUNTS
            save my notes to supabase                         | true  | SUPABASE | NONE
            shared notes without sign in                      | true  | SUPABASE | NONE
            """)
    void clearRequestsNeedNoStorageOrSignInQuestion(String prompt, boolean supabase, Storage storage, Auth auth) {
        assertEquals(new Inferred(storage, auth), DecisionDefaults.infer(prompt, supabase));
    }

    @ParameterizedTest
    @CsvSource(delimiter = '|', textBlock = """
            a team task board where members sign in
            an app where users log in and share notes
            save my notes to supabase
            notes that sync across devices
            shared notes without sign in
            a collaborative whiteboard
            """)
    void cloudOrAccountsWithoutAConnectedBackendStayAnOpenDecision(String prompt) {
        assertEquals(new Inferred(null, null), DecisionDefaults.infer(prompt, false));
    }

    @Test
    void aNormalPersonalProductUsesTheAvailableBackendEvenIfTheModelProposesBrowserStorage() {
        var browserProposal = new CapabilityProposal(CapabilityProposal.Persistence.BROWSER, false);
        assertEquals(new Inferred(Storage.MERN, Auth.NONE), DecisionDefaults.infer("Build a complete Todo app",
                new BuildDecisions.Available(true, null), browserProposal));
        assertEquals(new Inferred(null, null), DecisionDefaults.infer("Build a complete Todo app",
                new BuildDecisions.Available(false, null), browserProposal));
        assertEquals(new Inferred(Storage.BROWSER, Auth.NONE), DecisionDefaults.infer("Build a browser-only Todo app",
                new BuildDecisions.Available(true, null), browserProposal));
    }
}
