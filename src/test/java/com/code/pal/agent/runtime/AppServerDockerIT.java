package com.code.pal.agent.runtime;

import com.code.pal.agent.config.AgentProperties;
import com.code.pal.agent.stack.DependencyService;
import com.code.pal.agent.stack.StackPacks;
import com.code.pal.agent.workspace.DockerProjectEnvironment;
import com.code.pal.agent.workspace.HostProjectFiles;
import com.code.pal.agent.workspace.WorkspaceStorage;
import com.code.pal.project.database.GeneratedDatabaseService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import tools.jackson.databind.ObjectMapper;

import java.nio.charset.StandardCharsets;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

/**
 * A MERN server deployed and reached the way a preview reaches it: built from server/, started on the
 * internal network with its own database user on the apps' own MongoDB, called through the gateway.
 * Opt-in (pulls mongo:7 on first use): {@code mvnw test -Dtest=AppServerDockerIT -Dcodepal.docker.it=true}.
 */
@EnabledIfSystemProperty(named = "codepal.docker.it", matches = "true")
class AppServerDockerIT {
    private static final String DISTRO = System.getProperty("codepal.wsl.distro", "Ubuntu-24.04");
    private static final String LINUX_BASE = System.getProperty("codepal.wsl.base", "/home/shivam/codepal-it/workspaces");

    private final AgentProperties properties = properties();
    private final DockerProjectEnvironment sandbox = new DockerProjectEnvironment(properties);
    private final HostProjectFiles files = new HostProjectFiles(new WorkspaceStorage(properties));
    private final ObjectMapper mapper = new ObjectMapper();
    private final StackPacks stacks = new StackPacks(mapper);
    private final SandboxAppDatabase mongo = new SandboxAppDatabase(sandbox);
    private final GeneratedDatabaseService databases = new GeneratedDatabaseService(mongo);
    private final AppServerService servers = new AppServerService(stacks, files, sandbox,
            new DependencyService(stacks, files, sandbox, properties), databases, properties, mapper);
    private final UUID projectId = UUID.randomUUID();

    @AfterEach
    void cleanUp() {
        databases.dropAll(projectId);
        sandbox.destroy(projectId);
        mongo.close();
    }

    @Test
    void theServerKeepsDataInItsOwnDatabaseHasNoInternetAndIsReachedThroughTheGateway() throws Exception {
        sandbox.ensure(projectId);
        files.writeText(projectId, "client/package.json", "{\"name\":\"client\"}");
        files.writeText(projectId, "server/package.json", "{\"name\":\"server\",\"main\":\"index.js\",\"dependencies\":{\"mongodb\":\"6.10.0\"}}");
        files.writeText(projectId, "server/index.js", """
                const http = require('http');
                const { MongoClient } = require('mongodb');
                const client = new MongoClient(process.env.MONGODB_URI);
                const notes = client.db().collection('notes');
                http.createServer(async (req, res) => {
                  const send = (status, body) => { res.writeHead(status, { 'content-type': 'application/json' }); res.end(JSON.stringify(body)); };
                  try {
                    if (req.url === '/api/health') return send(200, { ok: true });
                    if (req.url === '/api/notes' && req.method === 'POST') {
                      let body = ''; for await (const chunk of req) body += chunk;
                      await notes.insertOne(JSON.parse(body));
                      return send(201, { saved: true });
                    }
                    if (req.url === '/api/notes') return send(200, await notes.find({}, { projection: { _id: 0 } }).toArray());
                    if (req.url === '/api/internet') {
                      try { await fetch('https://registry.npmjs.org/'); return send(200, { internet: true }); }
                      catch { return send(200, { internet: false }); }
                    }
                    if (req.url === '/api/other') {
                      try { await client.db('codepal_someone_else').collection('x').findOne(); return send(200, { other: 'readable' }); }
                      catch (e) { return send(200, { other: 'denied' }); }
                    }
                    send(404, { error: 'not found' });
                  } catch (e) { send(500, { error: String(e) }); }
                }).listen(process.env.PORT);
                """);

        servers.deploy(projectId);
        assertTrue(servers.hasServer(projectId), String.valueOf(servers.status(projectId)));

        MockHttpServletResponse saved = call("POST", "/api/notes", "{\"text\":\"hello\"}");
        assertEquals(201, saved.getStatus(), saved.getContentAsString());
        assertEquals("[{\"text\":\"hello\"}]", call("GET", "/api/notes", null).getContentAsString());
        assertEquals("{\"internet\":false}", call("GET", "/api/internet", null).getContentAsString());
        assertEquals("{\"other\":\"denied\"}", call("GET", "/api/other", null).getContentAsString(), "a project reaches only its own database");
        assertEquals(1, mongo.client().getDatabase("codepal_" + projectId.toString().replace('-', '_')).getCollection("notes").countDocuments());
        assertEquals(AppServerService.Status.State.RUNNING, servers.status(projectId).state());
    }

    private MockHttpServletResponse call(String method, String path, String body) throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest(method, path);
        if (body != null) {
            request.setContent(body.getBytes(StandardCharsets.UTF_8));
            request.setContentType("application/json");
        }
        MockHttpServletResponse response = new MockHttpServletResponse();
        servers.proxy(projectId, request, response);
        return response;
    }

    private static AgentProperties properties() {
        AgentProperties properties = new AgentProperties();
        properties.getSandbox().setDockerCommand(System.getProperty("codepal.docker.command", "wsl.exe"));
        properties.getSandbox().setDockerCommandArgs(System.getProperty("codepal.docker.args", "-e docker"));
        properties.getSandbox().setMemoryMb(512);
        properties.setWorkspaceBasePath("//wsl.localhost/" + DISTRO + LINUX_BASE);
        return properties;
    }
}
