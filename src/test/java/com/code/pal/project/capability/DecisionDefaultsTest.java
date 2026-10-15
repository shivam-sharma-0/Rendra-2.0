package com.code.pal.project.capability;

import com.code.pal.project.capability.BuildDecisions.Auth;
import com.code.pal.project.capability.BuildDecisions.Storage;
import com.code.pal.project.capability.DecisionDefaults.Inferred;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import static org.junit.jupiter.api.Assertions.assertEquals;

class DecisionDefaultsTest {
    @ParameterizedTest
    @CsvSource(delimiter = '|', textBlock = """
            A landing page for my bakery                      | false | NONE     | NONE
            ek calculator banao                               | false | NONE     | NONE
            a portfolio for my design team                    | false | NONE     | NONE
            a todo app that remembers my tasks                | false | BROWSER  | NONE
            mujhe ek expense tracker chahiye                  | false | BROWSER  | NONE
            a notes app without login                         | false | BROWSER  | NONE
            a notes app, login nahi chahiye                   | false | BROWSER  | NONE
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
}
