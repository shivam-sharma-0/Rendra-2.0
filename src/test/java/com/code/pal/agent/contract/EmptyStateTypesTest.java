package com.code.pal.agent.contract;

import com.code.pal.agent.validation.DiagnosticSeverity;
import com.code.pal.agent.validation.ProjectDiagnostic;
import org.junit.jupiter.api.Test;

import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

class EmptyStateTypesTest {
    private static final Set<String> TYPES = Set.of("Todo", "TodoInput", "Category");

    @Test
    void untypedEmptyListsOutsideReactStateAreTypedButAnnotatedOnesAreKept() {
        String source = """
                import type { Todo } from './types'
                type Shape = { items: [] }
                export function board(rows: Todo[]) {
                  const todos = []
                  let ranking = [ ]
                  const kept: string[] = []
                  const cast = [] as Todo[]
                  const result = { items: [], total: 0 }
                  const nested = call({ entries: [] })
                  const pick = rows.length ? rows : []
                  return { todos, ranking, kept, cast, result, nested, pick }
                }
                """;
        String fixed = EmptyStateTypes.fix("server/src/routers/game.ts", source, TYPES);
        assertEquals("""
                import type { Todo } from './types'
                type Shape = { items: [] }
                export function board(rows: Todo[]) {
                  const todos: Todo[] = []
                  let ranking: any[] = []
                  const kept: string[] = []
                  const cast = [] as Todo[]
                  const result = { items: [] as any[], total: 0 }
                  const nested = call({ entries: [] as any[] })
                  const pick = rows.length ? rows : []
                  return { todos, ranking, kept, cast, result, nested, pick }
                }
                """, fixed);
        assertEquals(fixed, EmptyStateTypes.fix("server/src/routers/game.ts", fixed, TYPES));
    }

    @Test
    void emptyListStateGetsTheImportedRecordTypeItsNameImplies() {
        // 2026-10-29 E2E 38-40, 44: useState([]) became never[] and both free repair routes made no progress.
        String source = """
                import { useState } from 'react'
                import type { Todo } from '../rendra/x/types'
                export default function Home() {
                  const [todos, setTodos] = useState([])
                  const [categories, setCategories] = React.useState( [ ] );
                  const [editingId, setEditingId] = useState(null)
                  const [draft, setDraft] = useState(null)
                  const [title, setTitle] = useState('')
                  const [typed, setTyped] = useState<Todo[]>([])
                """;
        String fixed = EmptyStateTypes.fix("client/src/pages/Home.tsx", source, TYPES);
        assertTrue(fixed.contains("const [todos, setTodos] = useState<Todo[]>([])"), fixed);
        assertTrue(fixed.contains("React.useState<any[]>( [ ] )"), "Category is not imported here: " + fixed);
        assertTrue(fixed.contains("useState<string | null>(null)"), fixed);
        assertTrue(fixed.contains("const [draft, setDraft] = useState<any>(null)"), fixed);
        assertTrue(fixed.contains("useState('')") && fixed.contains("useState<Todo[]>([])"), "typed and non-empty states stay");
        assertEquals(fixed, EmptyStateTypes.fix("client/src/pages/Home.tsx", fixed, TYPES), "idempotent");
        assertEquals(source, EmptyStateTypes.fix("client/src/pages/Home.jsx", source, TYPES), "JavaScript has no types");
    }

    @Test
    void recognisesTheTypeErrorsAnEmptyStateCauses() {
        assertTrue(EmptyStateTypes.diagnosed(error("Argument of type 'Dispatch<SetStateAction<never[]>>' is not assignable")));
        assertTrue(EmptyStateTypes.diagnosed(error("Property 'completed' does not exist on type 'never'.")));
        assertTrue(EmptyStateTypes.diagnosed(error("Argument of type 'string' is not assignable to parameter of type 'SetStateAction<null>'.")));
        assertFalse(EmptyStateTypes.diagnosed(error("Cannot find name 'Todo'.")));
    }

    private static ProjectDiagnostic error(String message) {
        return new ProjectDiagnostic("client/src/pages/Home.tsx", 1, 1, DiagnosticSeverity.ERROR, message, "tsc", "TS2345");
    }
}
