package com.code.pal.project.database;

import com.mongodb.client.MongoClient;
import lombok.RequiredArgsConstructor;
import org.bson.Document;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/** Owns the per-project Mongo database namespace used by generated MERN apps. */
@Service
@RequiredArgsConstructor
public class GeneratedDatabaseService {
    private final MongoClient client;

    public DatabaseSnapshot inspect(UUID projectId) {
        String database = databaseName(projectId);
        try {
            List<String> collections = client.getDatabase(database).listCollectionNames().into(new java.util.ArrayList<>());
            return new DatabaseSnapshot("MongoDB", database, true, collections, null, Instant.now());
        } catch (RuntimeException error) {
            return new DatabaseSnapshot("MongoDB", database, false, List.of(), "MongoDB is unavailable", Instant.now());
        }
    }

    public void ensure(UUID projectId) {
        try {
            var metadata = client.getDatabase(databaseName(projectId)).getCollection("_codepal_metadata");
            if (!metadata.find(new Document("_id", "project")).iterator().hasNext()) {
                metadata.insertOne(new Document("_id", "project").append("projectId", projectId.toString()).append("createdAt", Instant.now().toString()));
            }
        } catch (RuntimeException ignored) {
            // Database availability must not turn a successful code build into a failed build.
        }
    }

    private static String databaseName(UUID projectId) { return "codepal_" + projectId.toString().replace('-', '_'); }

    public record DatabaseSnapshot(String provider, String database, boolean reachable, List<String> collections,
                                    String error, Instant checkedAt) {}
}
