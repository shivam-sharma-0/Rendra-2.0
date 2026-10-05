package com.code.pal.agent.stack;

import com.code.pal.agent.validation.ValidationOperation;
import com.code.pal.agent.workspace.LocalProject;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/** A client + server project is one stack: both packages are checked, from the root, with fixed commands. */
class MernPackTest {
    private final LocalProject project = LocalProject.create();
    private final StackPacks stacks = new StackPacks(new ObjectMapper());

    MernPackTest() {
        project.write("package.json", "{\"scripts\":{\"dev\":\"concurrently ...\"}}")
                .write("client/package.json", "{\"devDependencies\":{\"vite\":\"7.0.0\",\"typescript\":\"5.9.0\"}}")
                .write("client/tsconfig.json", "{\"files\":[],\"references\":[{\"path\":\"./tsconfig.app.json\"}]}")
                .write("server/package.json", "{\"dependencies\":{\"express\":\"5.1.0\"},\"devDependencies\":{\"typescript\":\"5.9.0\"},"
                        + "\"scripts\":{\"build\":\"rm -rf / # never run\"}}")
                .write("server/tsconfig.json", "{\"compilerOptions\":{\"outDir\":\"dist\"}}");
    }

    @Test
    void outranksTheRootPackageAndChecksBothPackagesFromTheRoot() {
        StackPack stack = detect();
        assertEquals("mern", stack.id());

        CommandPlan typeCheck = stack.resolve(project.files, project.projectId, ".", ValidationOperation.TYPE_CHECK, null);
        String script = typeCheck.command().get(2);
        assertEquals(List.of("sh", "-c"), typeCheck.command().subList(0, 2));
        assertTrue(script.contains("node client/node_modules/typescript/bin/tsc -b client --pretty false"), script);
        assertTrue(script.contains("node server/node_modules/typescript/bin/tsc -p server --noEmit --pretty false"), script);
        assertTrue(script.endsWith("exit $status"), "both run even when the first fails: " + script);

        String build = stack.resolve(project.files, project.projectId, ".", ValidationOperation.BUILD, null).command().get(2);
        assertTrue(build.contains("vite.js build client") && build.contains("tsc -p server --pretty false"), build);
        assertFalse(build.contains("rm -rf"), "package scripts are never run");
    }

    @Test
    void thePreviewIsTheClientBuiltWithARelativeBase() {
        StackPack.PreviewBuild preview = detect().previewBuild(project.files, project.projectId, ".");
        assertEquals(List.of("node", "client/node_modules/vite/bin/vite.js", "build", "client", "--base", "./", "--outDir", "dist", "--emptyOutDir"),
                preview.command());
        assertEquals(List.of("client/dist"), preview.outputDirectories());
        assertEquals(List.of("client", "server"), detect().packageRoots(project.files, project.projectId, "."));
    }

    private StackPack detect() {
        return stacks.detect(project.files, project.projectId, ".");
    }
}
