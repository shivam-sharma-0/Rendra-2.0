package com.code.pal.agent.workspace;

import com.code.pal.agent.config.AgentProperties;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The Windows backend reading a project that lives in WSL ext4 through the WSL share, with Linux links
 * planted the way generated code would plant them. Opt-in:
 * {@code mvnw test -Dtest=HostProjectFilesWslIT -Dcodepal.wsl.it=true}
 */
@EnabledIfSystemProperty(named = "codepal.wsl.it", matches = "true")
class HostProjectFilesWslIT {

    private static final String DISTRO = System.getProperty("codepal.wsl.distro", "Ubuntu-24.04");
    private static final String LINUX_BASE = System.getProperty("codepal.wsl.base", "/home/shivam/codepal-it");
    private final UUID projectId = UUID.randomUUID();

    @AfterEach
    void cleanUp() throws Exception {
        wsl("rm", "-rf", LINUX_BASE);
    }

    @Test
    void linuxLinksAreNeitherFollowedNorAllowedToBreakListing() throws Exception {
        String project = LINUX_BASE + "/" + projectId;
        wsl("mkdir", "-p", project + "/src");
        wsl("sh", "-c", "echo 'export const ok = 1;' > " + project + "/src/ok.ts");
        wsl("ln", "-s", "/etc/passwd", project + "/evil-link");
        wsl("ln", "-s", "/etc", project + "/evil-dir");
        wsl("ln", "-s", "/etc", project + "/src/evil-nested");

        AgentProperties properties = new AgentProperties();
        properties.setWorkspaceBasePath("//wsl.localhost/" + DISTRO + LINUX_BASE);
        HostProjectFiles files = new HostProjectFiles(new WorkspaceStorage(properties));

        assertEquals("export const ok = 1;\n", files.readText(projectId, "src/ok.ts"));
        List<String> listed = files.list(projectId, 5).stream().map(SandboxPort.Entry::path).toList();
        assertTrue(listed.contains("src/ok.ts"), listed.toString());
        assertTrue(listed.stream().noneMatch(path -> path.contains("evil")), listed.toString());

        assertThrows(IllegalStateException.class, () -> files.readText(projectId, "evil-link"));
        assertThrows(IllegalStateException.class, () -> files.readText(projectId, "evil-dir/passwd"));
        assertFalse(files.regularFile(projectId, ".", "evil-link"));
        assertThrows(IllegalStateException.class, () -> files.writeText(projectId, "evil-dir/planted.txt", "x"));
        assertEquals("no", wslOutput("sh", "-c", "test -e /etc/planted.txt && echo yes || echo no"),
                "nothing may be written through the link");

        files.writeText(projectId, "src/new.ts", "export const n = 2;\n");
        assertEquals("export const n = 2;\n", wslOutput("cat", project + "/src/new.ts") + "\n");
        assertEquals("1000:1000", wslOutput("stat", "-c", "%u:%g", project + "/src/new.ts"),
                "files written by the backend must belong to the sandbox user");
    }

    private static void wsl(String... command) throws Exception {
        run(command);
    }

    private static String wslOutput(String... command) throws Exception {
        return run(command).trim();
    }

    private static String run(String... command) throws Exception {
        List<String> full = new ArrayList<>(List.of("wsl.exe", "-d", DISTRO, "--exec"));
        full.addAll(List.of(command));
        Process process = new ProcessBuilder(full).redirectErrorStream(true).start();
        String output = new String(process.getInputStream().readAllBytes());
        process.waitFor(60, TimeUnit.SECONDS);
        return output;
    }
}
