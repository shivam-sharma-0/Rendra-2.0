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
        assertEquals("Map", blueprint.icon());
        assertEquals("Map", level.icon());
        assertEquals(Map.of("easy", "success", "hard", "danger"), level.field("difficulty").tones());
        assertTrue(files.get(contract.clientRoot() + "entities.ts").contains("import { Map as LevelIcon, Trophy as ScoreIcon } from 'lucide-react'"));
        assertTrue(files.get(contract.clientRoot() + "routes.ts").contains("{path:\"/account\",component: Route6,hidden: true}"),
                files.get(contract.clientRoot() + "routes.ts"));
        assertTrue(files.get("client/src/pages/AccountSettingsPage.tsx").contains("ProfilePage"));
        assertTrue(files.get("client/src/App.tsx").contains("import { Map as AppMark } from 'lucide-react'"));
        var retry = new ContractScaffold(MAPPER).render(contract, blueprint.name(), files::get, UUID.randomUUID());
        assertFalse(retry.containsKey("client/src/pages/LevelListPage.tsx"));
        assertFalse(retry.containsKey("client/src/pages/AccountSettingsPage.tsx"));
        export("shadow-maze", files, contract);
    }

    @Test
    void largestCrmBlueprintFitsEveryContractBound() throws Exception {
        var blueprint = BlueprintCompiler.normalize(json("""
                {"name":"Pipeline CRM","preset":"corporate","accent":"#2563eb",
                 "entities":[
                  {"name":"Deal","access":"shared","fields":[{"name":"title","required":true},{"name":"company"},
                    {"name":"stage","options":["lead","qualified","proposal","won","lost"],"required":true},{"name":"value","kind":"currency"},
                    {"name":"closeDate","kind":"date"},{"name":"notes","kind":"text"}],"filter":"stage","search":["title","company"]},
                  {"name":"Contact","fields":[{"name":"fullName","required":true},{"name":"email","kind":"email"},{"name":"phone"},
                    {"name":"company"},{"name":"status","options":["new","active","inactive"]},{"name":"photo","kind":"image","required":true}]},
                  {"name":"Company","plural":"companies","fields":[{"name":"name","required":true},{"name":"industry","options":["software","retail","health","finance"]},
                    {"name":"employees","kind":"number"},{"name":"website","kind":"url"}]},
                  {"name":"Activity","fields":[{"name":"subject","required":true},{"name":"type","options":["call","email","meeting"]},
                    {"name":"due","kind":"date"},{"name":"done","kind":"boolean"}]},
                  {"name":"Task","fields":[{"name":"title","required":true},{"name":"priority","options":["low","medium","high","urgent"]},
                    {"name":"dueDate","kind":"date"},{"name":"completed","kind":"boolean"},{"name":"details","kind":"text"}]},
                  {"name":"Note","fields":[{"name":"title","required":true},{"name":"body","kind":"text"}]},
                  {"name":"Invoice","fields":[{"name":"number","required":true},{"name":"amount","kind":"money"}]}],
                 "pages":[{"path":"/","title":"Dashboard","kind":"dashboard"},
                  {"path":"/deals","kind":"list","entity":"Deal","title":"Deals","description":"Every opportunity from first call to signature."},
                  {"path":"/contacts","kind":"list","entity":"Contact"},{"path":"/companies","kind":"list","entity":"Company"},
                  {"path":"/activities","kind":"list","entity":"Activity"},{"path":"/tasks","kind":"list","entity":"Task"},
                  {"path":"/notes","kind":"list","entity":"Note"},{"path":"/invoices","kind":"list","entity":"Invoice","title":"Invoices"},
                  {"path":"/reports","title":"Reports","kind":"stats"},{"path":"/settings","title":"Settings","kind":"settings"},
                  {"path":"/pipeline","title":"Pipeline","kind":"custom"},{"path":"/calendar","title":"Calendar","kind":"custom"},
                  {"path":"/inbox","title":"Inbox","kind":"custom"}]}
                """));
        assertEquals(Blueprint.ENTITIES, blueprint.entities().size());
        assertTrue(blueprint.later().containsAll(List.of("Manage invoices", "Invoices page", "Inbox page")), blueprint.later().toString());
        assertEquals(List.of("Handshake", "User", "Building2", "Activity", "ListTodo", "StickyNote"),
                blueprint.entities().stream().map(Blueprint.Entity::icon).toList());
        assertEquals(Map.of("lead", "accent", "qualified", "accent", "proposal", "accent", "won", "success", "lost", "danger"),
                blueprint.entity("Deal").field("stage").tones());
        assertFalse(blueprint.entity("Contact").field("photo").required());
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

    @Test
    void plannerCorrectsOneRejectedBlueprintAndKeepsTheApprovedSignInPath() {
        var replies = new ArrayDeque<>(List.of("```json\n{\"name\":\"Notes\",\"entities\":[],\"pages\":[{\"path\":\"/\",\"kind\":\"landing\"}]}\n```",
                "Here it is: {\"name\":\"Notes\",\"entities\":[{\"name\":\"Note\",\"fields\":[{\"name\":\"title\",\"required\":true}]}],"
                        + "\"pages\":[{\"path\":\"/\",\"kind\":\"landing\"},{\"path\":\"/notes\",\"kind\":\"list\",\"entity\":\"Note\"},"
                        + "{\"path\":\"/notes/:id\",\"title\":\"Note details\",\"kind\":\"custom\",\"entity\":\"Note\"}]}"));
        var requests = new ArrayList<com.code.pal.agent.llm.LlmClient.LlmRequest>();
        var input = new ContractPlanner.Input(UUID.randomUUID(), "A private notes app",
                "{\"requestedScope\":{\"pages\":[{\"path\":\"/\"},{\"path\":\"/notes\"},{\"path\":\"/login\"}]}}", "", ACCOUNTS, LIMITS, "client/src/App.tsx");
        var properties = new com.code.pal.agent.config.AgentProperties();
        properties.setEngine("smart");
        assertTrue(BlueprintPlanner.supports(properties, ACCOUNTS, "client/src/App.tsx"));
        assertFalse(BlueprintPlanner.supports(properties, ACCOUNTS, null));
        var contract = new BlueprintPlanner(MAPPER, properties).plan(input, new com.code.pal.agent.config.AgentProperties.ModelSelection("codex", "gpt"),
                request -> {
                    requests.add(request);
                    return new com.code.pal.agent.llm.LlmClient.LlmResult(replies.removeFirst(), null, List.of(), null, "stop");
                });
        assertEquals(2, requests.size());
        assertTrue(requests.getLast().messages().getLast().content().contains("A server app needs at least one kind of record"));
        assertTrue(contract.routes().stream().anyMatch(route -> route.path().equals("/login") && route.owner().equals(BlueprintCompiler.ACCOUNT_UNIT)));
        assertEquals(input.id(), contract.id());
        assertTrue(contract.routes().stream().anyMatch(route -> route.path().equals("/notes/:id") && route.owner().equals("crud_notes")));
        assertTrue(contract.units().stream().noneMatch(unit -> unit.id().equals("page_noteDetails")));
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
