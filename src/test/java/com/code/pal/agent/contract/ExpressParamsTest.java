package com.code.pal.agent.contract;

import com.code.pal.agent.validation.DiagnosticSeverity;
import com.code.pal.agent.validation.ProjectDiagnostic;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

class ExpressParamsTest {
    @Test
    void onlyTheDiagnosedLineReadsItsParameterAsAString() {
        String source = "function validId(req: AccountRequest) {\n  return pattern.test(req.params.id ?? '')\n}\nconst other = req.params.id\n";
        var diagnostic = new ProjectDiagnostic("server/src/routers/tasks.ts", 2, 22, DiagnosticSeverity.ERROR,
                "Argument of type 'string | string[]' is not assignable to parameter of type 'string'.", "tsc", "TS2345");
        String fixed = ExpressParams.fix("server/src/routers/tasks.ts", source, List.of(diagnostic));
        assertEquals("function validId(req: AccountRequest) {\n  return pattern.test(String(req.params.id ?? '') ?? '')\n}\nconst other = req.params.id\n", fixed);
        assertEquals(fixed, ExpressParams.fix("server/src/routers/tasks.ts", fixed, List.of(diagnostic)));
    }
}
