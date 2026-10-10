package com.code.pal.project.database;

import lombok.RequiredArgsConstructor;
import org.bson.Document;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * Owns the per-project databases of generated MERN apps, on the apps' own MongoDB server: one database and
 * one user per project, the user allowed only that database.
 */
@Service
@RequiredArgsConstructor
public class GeneratedDatabaseService {
    private final AppDatabaseConnection connection;

    public DatabaseSnapshot inspect(UUID projectId) {
        String database = databaseName(projectId);
        try {
            List<String> collections = connection.client().getDatabase(database).listCollectionNames().into(new java.util.ArrayList<>());
            return new DatabaseSnapshot("MongoDB", database, true, collections, null, Instant.now());
        } catch (RuntimeException error) {
            return new DatabaseSnapshot("MongoDB", database, false, List.of(), "MongoDB is unavailable", Instant.now());
        }
    }

    public void ensure(UUID projectId) {
        try {
            var metadata = connection.client().getDatabase(databaseName(projectId)).getCollection("_codepal_metadata");
            if (!metadata.find(new Document("_id", "project")).iterator().hasNext()) {
                metadata.insertOne(new Document("_id", "project").append("projectId", projectId.toString()).append("createdAt", Instant.now().toString()));
            }
        } catch (RuntimeException ignored) {
            // Database availability must not turn a successful code build into a failed build.
        }
    }

    /**
     * A connection string for the project's own database, for its app server: the user exists only in that
     * database and may only read and write it. Each call sets a fresh random password, so only the server
     * started with it can sign in. {@code check}: a separate scratch database for validation runs, so a
     * check never touches the app's data.
     */
    public String connectionUri(UUID projectId, String address, boolean check) {
        String database = databaseName(projectId) + (check ? "_check" : "");
        String user = "app";
        String password = randomPassword();
        Document roles = new Document("role", "readWrite").append("db", database);
        var db = connection.client().getDatabase(database);
        try {
            db.runCommand(new Document("createUser", user).append("pwd", password).append("roles", List.of(roles)));
        } catch (com.mongodb.MongoCommandException exists) {
            if (exists.getErrorCode() != 51003) throw exists;   // 51003: the user already exists
            db.runCommand(new Document("updateUser", user).append("pwd", password).append("roles", List.of(roles)));
        }
        return "mongodb://" + user + ":" + password + "@" + address + "/" + database + "?authSource=" + database;
    }

    /** Removes the validation scratch database (and its user). */
    public void dropCheck(UUID projectId) {
        drop(databaseName(projectId) + "_check");
    }

    /** Removes everything the project stored, for a deleted project. */
    public void dropAll(UUID projectId) {
        drop(databaseName(projectId) + "_check");
        drop(databaseName(projectId));
    }

    private void drop(String database) {
        try {
            var db = connection.client().getDatabase(database);
            try { db.runCommand(new Document("dropAllUsersFromDatabase", 1)); } catch (RuntimeException none) { }
            db.drop();
        } catch (RuntimeException error) {
            // Best effort: an unreachable database keeps its data until the next cleanup.
        }
    }

    private static String randomPassword() {
        byte[] bytes = new byte[24];
        RANDOM.nextBytes(bytes);
        return java.util.HexFormat.of().formatHex(bytes);
    }

    private static final java.security.SecureRandom RANDOM = new java.security.SecureRandom();

    private static String databaseName(UUID projectId) { return "codepal_" + projectId.toString().replace('-', '_'); }

    public record DatabaseSnapshot(String provider, String database, boolean reachable, List<String> collections,
                                    String error, Instant checkedAt) {}
}
