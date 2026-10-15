package com.code.pal.agent.contract;

import com.code.pal.agent.config.AgentProperties;
import com.code.pal.agent.llm.LlmClient;
import com.code.pal.project.spec.SpecFixtures;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import tools.jackson.databind.ObjectMapper;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

class ContractImportPolicyTest {
    private final ObjectMapper mapper = new ObjectMapper();
    private final BuildContract contract = new SpecPlanningTest().deterministic(SpecFixtures.browser());

    private UnitGenerator.Input input() {
        return new UnitGenerator.Input(contract, contract.units().getFirst(), "Build tasks", "Approved",
                Map.of("src/App.tsx", new UnitGenerator.FileView("absent", null),
                        "src/index.css", new UnitGenerator.FileView("absent", null),
                        "src/local-state.ts", new UnitGenerator.FileView("absent", null)), "");
    }

    private UnitGenerator.Output output(String app) {
        return new UnitGenerator.Output(List.of(new UnitGenerator.Delta("src/App.tsx", "absent", app),
                new UnitGenerator.Delta("src/index.css", "absent", ""),
                new UnitGenerator.Delta("src/local-state.ts", "absent", "export const tasks = []")));
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "import { Link } from 'react-router-dom'; export default Link",
            "import type { RouteObject }\n from \"react-router-dom\"; export default null",
            "export { Link } from 'react-router-dom'",
            "import 'react-router-dom'",
            "const router = import('react-router-dom')",
            "const router = require('react-router-dom')",
            "const name = 'react-router-dom'; const router = import(name)",
            "import icon from 'lucide-react'",
            "import x from '../../outside.ts'"
    })
    void disallowedOrComputedImportsAreRejectedBeforeAnyMerge(String source) {
        assertThrows(IllegalArgumentException.class, () -> UnitGenerator.validate(input(), output(source)));
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "import { useState } from 'react'; import './index.css'; export default () => null",
            "import { tasks } from './local-state'; export default () => tasks.length",
            "// import X from 'react-router-dom'\nexport default () => null",
            "const text = \"import X from 'react-router-dom'\"; export default () => text",
            "/* export { Link } from 'react-router-dom' */ export default () => null",
            "const url = import.meta.env.MODE; export default () => url"
    })
    void reviewedReactRelativeImportsAndQuotedExamplesRemainValid(String source) {
        assertDoesNotThrow(() -> UnitGenerator.validate(input(), output(source)));
    }

    @Test
    void disallowedImportUsesTheExistingSingleCorrectionWithAnExactNavigationPolicy() {
        var calls = new AtomicInteger();
        var results = new BatchUnitGenerator(mapper).generatePlanned(contract, contract.batches().getFirst(), List.of(input()), Set.of(),
                new AgentProperties.ModelSelection("mistral", "synthetic"), request -> {
                    assertTrue(request.messages().getFirst().content().contains("react-router-dom"));
                    assertTrue(request.messages().getFirst().content().contains("<a href"));
                    String source;
                    if (calls.incrementAndGet() == 1) {
                        source = "import { Link } from 'react-router-dom'; export default Link";
                    } else {
                        assertTrue(request.messages().getLast().content().contains("Disallowed bare module import"));
                        source = "export default () => <a href='/'>Home</a>";
                    }
                    var value = new BatchUnitGenerator.Output(List.of(new BatchUnitGenerator.UnitOutput("app", output(source).files())));
                    return new LlmClient.LlmResult(mapper.writeValueAsString(value), null, List.of(), null, "stop");
                });
        assertEquals(2, calls.get());
        assertTrue(results.getFirst().files().getFirst().content().contains("<a href"));
    }

    @Test
    void legacyMernPackageImportsRemainOnTheirExistingPath() {
        var legacy = ContractFixtures.mern();
        var unit = legacy.units().getFirst();
        var snapshot = new HashMap<String, UnitGenerator.FileView>();
        unit.ownedPaths().forEach(path -> snapshot.put(path, new UnitGenerator.FileView("absent", null)));
        var input = new UnitGenerator.Input(legacy, unit, "MERN", "Approved", snapshot, "");
        var output = new UnitGenerator.Output(unit.ownedPaths().stream()
                .map(path -> new UnitGenerator.Delta(path, "absent", "import express from 'express'")).toList());
        assertDoesNotThrow(() -> UnitGenerator.validate(input, output));
    }
}
