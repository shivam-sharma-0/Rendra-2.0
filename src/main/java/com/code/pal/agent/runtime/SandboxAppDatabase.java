package com.code.pal.agent.runtime;

import com.code.pal.agent.workspace.SandboxPort;
import com.code.pal.project.database.AppDatabaseConnection;
import com.mongodb.client.MongoClient;
import com.mongodb.client.MongoClients;
import jakarta.annotation.PreDestroy;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/** The apps' MongoDB as the sandbox runs it, opened on first use and reopened when its address changes. */
@Component
@RequiredArgsConstructor
public class SandboxAppDatabase implements AppDatabaseConnection {
    private final SandboxPort sandbox;
    private MongoClient client;
    private String uri;

    @Override
    public synchronized MongoClient client() {
        String current = sandbox.databaseAdminUri();
        if (client == null || !current.equals(uri)) {
            if (client != null) client.close();
            client = MongoClients.create(current);
            uri = current;
        }
        return client;
    }

    @PreDestroy
    synchronized void close() {
        if (client != null) client.close();
    }
}
