package com.code.pal.project.database;

import com.mongodb.client.MongoClient;

/**
 * Administrator access to the MongoDB server that generated apps use: a server of their own, never the
 * platform's database. Provided by the sandbox (which runs it); opened on first use.
 */
@FunctionalInterface
public interface AppDatabaseConnection {
    MongoClient client();
}
