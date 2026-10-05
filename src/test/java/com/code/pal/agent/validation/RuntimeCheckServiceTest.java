package com.code.pal.agent.validation;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;

class RuntimeCheckServiceTest {

    @Test
    void theSameProblemInADifferentBuildIsRecognizedAsPreExisting() {
        var live = new RuntimeCheckService.Issue("exception",
                "TypeError: x is undefined\n    at App (assets/index-CvWstFoJ.js:40:12)", "assets/index-CvWstFoJ.js:40:12 (App)");
        var staged = new RuntimeCheckService.Issue("exception",
                "TypeError: x is undefined\n    at App (assets/index-CWWTwJHN.js:41:3)", "assets/index-CWWTwJHN.js:41:3 (App)");

        assertEquals(RuntimeCheckService.fingerprint(live), RuntimeCheckService.fingerprint(staged));
    }

    @Test
    void aDifferentProblemIsNot() {
        var live = new RuntimeCheckService.Issue("exception", "TypeError: x is undefined", null);
        var staged = new RuntimeCheckService.Issue("exception", "ReferenceError: y is not defined", null);
        var missing = new RuntimeCheckService.Issue("asset", "404 assets/logo.svg", null);

        assertNotEquals(RuntimeCheckService.fingerprint(live), RuntimeCheckService.fingerprint(staged));
        assertNotEquals(RuntimeCheckService.fingerprint(live), RuntimeCheckService.fingerprint(missing));
    }
}
