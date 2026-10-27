package com.code.pal.agent.contract;

import com.code.pal.agent.validation.DiagnosticSeverity;
import com.code.pal.agent.validation.ProjectDiagnostic;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

class MissingImportsTest {
    private ProjectDiagnostic missing(String file, String name) {
        return new ProjectDiagnostic(file, 8, 3, DiagnosticSeverity.ERROR, "Cannot find name '" + name + "'.", "compiler", "TS2304");
    }

    @Test void forgottenHookImportsAreAddedButImportedQualifiedOrLocalHooksAreLeft() {
        // Live E2E 2026-10-27: DeleteTodoButton called useState without importing it.
        String file = "client/src/components/DeleteTodoButton.tsx";
        String source = "export default function Delete() {\n  const [busy, setBusy] = useState<boolean>(false)\n  useEffect(() => {}, [])\n  return null\n}\n";
        assertEquals("import { useEffect, useState } from 'react'\n" + source, MissingImports.hooks(file, source));
        for (String fine : List.of("import { useState, useEffect } from 'react'\n" + source,
                "import React, {\n  useState,\n  useEffect,\n} from 'react'\n" + source,
                "import React from 'react'\nconst a = React.useState(0)\n",
                "function useState(value: number) { return value }\nconst a = useState(1)\n"))
            assertEquals(fine, MissingImports.hooks(file, fine));
        assertEquals(source, MissingImports.hooks("server/src/routers/todos.ts", source), "server code has no React hooks");
    }

    @Test void aDiagnosedContractTypeIsImportedFromItsPackagesCoordinatorTypes() {
        String client = "client/src/components/TodoList.tsx", server = "server/src/routers/todos.ts";
        String source = "export function List({ todos }: { todos: Todo[] }) { return todos.length }\n";
        assertEquals("import type { Todo } from '../rendra/id/types'\n" + source, MissingImports.types(client, source,
                List.of(missing(client, "Todo"), missing(client, "Unknown"), missing(server, "TodoInput")), Set.of("Todo", "TodoInput"), "client/src/rendra/id/types.ts"));
        assertEquals("import type { TodoInput } from '../rendra/id/types.js'\n" + source, MissingImports.types(server, source,
                List.of(missing(server, "TodoInput")), Set.of("Todo", "TodoInput"), "server/src/rendra/id/types.ts"));
        String declared = "type Todo = { id: string }\n" + source;
        assertEquals(declared, MissingImports.types(client, declared, List.of(missing(client, "Todo")), Set.of("Todo"), "client/src/rendra/id/types.ts"));
        assertNull(MissingImports.missingName(new ProjectDiagnostic(client, 1, 1, DiagnosticSeverity.ERROR, "Cannot find name 'Todo'.", "compiler", "TS2345")));
    }
}
