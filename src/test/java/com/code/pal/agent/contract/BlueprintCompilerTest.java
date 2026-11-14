package com.code.pal.agent.contract;

import com.code.pal.agent.validation.BehaviorCriteria;
import com.code.pal.project.capability.BuildDecisions;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;

import static org.junit.jupiter.api.Assertions.*;

class BlueprintCompilerTest {
    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final BuildContract.Limits LIMITS = new BuildContract.Limits(10, 40, 8192, 60000);
    private static final BuildDecisions.Choice ACCOUNTS = new BuildDecisions.Choice(1, BuildDecisions.Storage.MERN, BuildDecisions.Auth.ACCOUNTS, null);

    @Test
    void messyGameBlueprintBecomesAValidAccountContractWithPlatformOwnedCrud() throws Exception {
        var blueprint = BlueprintCompiler.normalize(json("""
                {"name":"Shadow Maze","preset":"gaming","accent":"#8B5CF6","auth":"accounts",
                 "entities":[{"name":"level","access":"admin","fields":[{"name":"Title","kind":"string"},
                     {"name":"difficulty","type":"select","options":["easy","hard","easy"]},{"name":"id"},{"name":"story","kind":"textarea"}],
                   "filter":"difficulty","search":"title"},
                   {"name":"Score","plural":"scores","access":"public","fields":[{"name":"player","kind":"string","required":true},{"name":"points","kind":"int","required":true}]}],
                 "pages":[{"path":"home","title":"Shadow Maze","kind":"home"},{"path":"/levels","kind":"list","entity":"Levels"},
                   {"path":"/play/:id","title":"Play","kind":"game","feature":"Maze Play"},{"path":"/login","kind":"auth"},
                   {"path":"/leaderboard","title":"Leaderboard","kind":"custom","entity":"Score","feature":"unknown"}],
                 "features":[{"id":"maze-play","description":"Timed maze with traps, hints and stars"}],
                 "later":["Sound effects"]}
                """));
        assertEquals("midnight", blueprint.preset());
        var level = blueprint.entity("Level");
        assertEquals("levels", level.plural());
        assertEquals(Blueprint.Access.ADMIN, level.access());
        assertEquals(List.of("title", "difficulty", "story"), level.fields().stream().map(Blueprint.Field::name).toList());
        assertTrue(level.field("title").required());
        assertEquals(List.of("easy", "hard"), level.field("difficulty").options());
        assertEquals("/levels/:id", blueprint.detailPath(level));
        assertEquals("maze-play", blueprint.pages().stream().filter(page -> page.path().equals("/play/:id")).findFirst().orElseThrow().feature());
        assertTrue(blueprint.pages().stream().noneMatch(page -> page.path().equals("/login")));

        var contract = BlueprintCompiler.compile(blueprint, UUID.randomUUID(), ACCOUNTS, LIMITS);
        var stored = MAPPER.readValue(MAPPER.writeValueAsString(contract), BuildContract.class);
        assertEquals(contract, stored);
        assertEquals(Set.of("crud_levels", "crud_scores", BlueprintCompiler.ACCOUNT_UNIT, "page_shadowMaze", "page_play", "page_leaderboard"),
                new HashSet<>(contract.units().stream().map(BuildContract.Unit::id).toList()));
        for (String path : List.of("server/src/routers/levels.ts", "client/src/pages/LevelListPage.tsx", "client/src/pages/LevelDetailPage.tsx", "server/src/routers/scores.ts"))
            assertTrue(ContractScaffold.platformPage(contract, path), path);
        assertFalse(ContractScaffold.platformPage(contract, "client/src/pages/PlayPage.tsx"));
        assertTrue(contract.acceptance().api().stream().anyMatch(step -> step.id().equals("levels_search") && step.path().equals("/api/levels?q=sample")));
        assertTrue(contract.acceptance().browser().stream().anyMatch(step -> step.id().equals("levels_created")));

        var files = new ContractScaffold(MAPPER).render(contract, blueprint.name(), path -> null, UUID.randomUUID());
        String models = files.get(contract.serverRoot() + "models.ts");
        assertTrue(models.contains("Level: sharedModel(") && models.contains("}, \"admin\")))"), models);
        assertTrue(models.contains("Score: sharedModel(") && models.contains("}, \"shared\")))"), models);
        assertTrue(files.get("server/src/routers/levels.ts").contains("requireAdmin"));
        assertFalse(files.get("server/src/routers/scores.ts").contains("requireAdmin"));
        assertTrue(files.get(contract.clientRoot() + "entities.ts").contains("canEdit: record => record.accountId === auth.getSession()?.userId"));
        assertTrue(files.get(contract.clientRoot() + "api.ts").contains("listLevels: (params: Record<string,string> = {}, body?: undefined, token?: string, query?: Record<string, string>)"));
        assertEquals("export default function PendingRoute() { return null }\n", files.get("client/src/pages/PlayPage.tsx"));
        assertTrue(files.get("client/src/pages/LevelListPage.tsx").contains("<RecordList spec={levels} title={\"Levels\"} />"));
        export("shadow-maze", files, contract);
    }

    @Test
    void largestCrmBlueprintFitsEveryContractBound() throws Exception {
        var entities = new ArrayList<Map<String, Object>>();
        var pages = new ArrayList<Map<String, Object>>();
        for (String name : List.of("Contact", "Company", "Deal", "Activity", "Task", "Note", "Invoice")) {
            entities.add(Map.of("name", name, "fields", List.of(Map.of("name", "name", "kind", "string", "required", true),
                    Map.of("name", "stage", "kind", "enum", "options", List.of("new", "active", "closed")),
                    Map.of("name", "value", "kind", "number"), Map.of("name", "due", "kind", "date"), Map.of("name", "notes", "kind", "text"))));
            pages.add(Map.of("path", "/" + name.toLowerCase(Locale.ROOT), "kind", "list", "entity", name));
        }
        for (String title : List.of("Home", "Dashboard", "Reports", "Settings", "Pipeline", "Calendar"))
            pages.add(Map.of("path", title.equals("Home") ? "/" : "/" + title.toLowerCase(Locale.ROOT), "title", title, "kind", title.toLowerCase(Locale.ROOT)));
        var blueprint = BlueprintCompiler.normalize(Map.of("name", "Pipeline CRM", "preset", "corporate", "entities", entities, "pages", pages));
        assertEquals(Blueprint.ENTITIES, blueprint.entities().size());
        assertTrue(blueprint.later().contains("Manage invoices") && blueprint.later().contains("Calendar page"), blueprint.later().toString());
        var contract = BlueprintCompiler.compile(blueprint, UUID.randomUUID(), ACCOUNTS, LIMITS);
        assertEquals(30, contract.api().size());
        assertEquals(12, contract.units().size());
        assertTrue(contract.acceptance().api().size() <= BehaviorCriteria.API_CAP);
        assertTrue(contract.acceptance().browser().size() <= BehaviorCriteria.BROWSER_CAP);
        export("crm", new ContractScaffold(MAPPER).render(contract, blueprint.name(), path -> null, UUID.randomUUID()), contract);
    }

    @Test
    void browserStorageAppKeepsRecordsLocallyWithTheSamePages() throws Exception {
        var blueprint = BlueprintCompiler.normalize(json("""
                {"name":"Habit Garden","preset":"playful","entities":[{"name":"Habit","fields":[{"name":"name","required":true},
                 {"name":"done","kind":"boolean"},{"name":"category","options":["health","mind"]}]}],
                 "pages":[{"path":"/","kind":"list","entity":"Habit","title":"My habits","description":"Small steps, every day."}]}
                """));
        assertEquals("/habits/:id", blueprint.detailPath(blueprint.entity("Habit")));
        var contract = BlueprintCompiler.compile(blueprint, UUID.randomUUID(),
                new BuildDecisions.Choice(1, BuildDecisions.Storage.BROWSER, BuildDecisions.Auth.NONE, null), LIMITS);
        assertEquals(BuildContract.Stack.VITE, contract.stack());
        assertTrue(contract.api().isEmpty() && contract.acceptance().api().isEmpty());
        var files = new ContractScaffold(MAPPER).render(contract, blueprint.name(), path -> null, UUID.randomUUID());
        assertTrue(files.get(contract.clientRoot() + "entities.ts").contains("localStore<Types.Habit, Types.HabitInput>("));
        assertTrue(files.get("src/pages/HabitListPage.tsx").contains("description={\"Small steps, every day.\"}"));
        export("habit-garden", files, contract);
    }

    private static Map<?, ?> json(String text) {
        return MAPPER.readValue(text, Map.class);
    }

    private static void export(String name, Map<String, String> files, BuildContract contract) throws Exception {
        Path root = Path.of("target/blueprint-apps", name);
        if (Files.exists(root))
            try (var paths = Files.walk(root)) {
                for (Path path : paths.sorted(Comparator.reverseOrder()).toList())
                    Files.delete(path);
            }
        Files.createDirectories(root);
        Files.writeString(root.resolve("build-contract.json"), MAPPER.writeValueAsString(contract));
        for (var file : files.entrySet()) {
            Path target = root.resolve(file.getKey());
            Files.createDirectories(target.getParent());
            Files.writeString(target, file.getValue());
        }
    }
}
