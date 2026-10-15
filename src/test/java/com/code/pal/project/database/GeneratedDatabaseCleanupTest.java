package com.code.pal.project.database;
import com.mongodb.client.*;
import org.bson.Document;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;

class GeneratedDatabaseCleanupTest {
    @Test void unavailableDatabaseCannotBeAcknowledgedAsCleaned() {
        var client=mock(MongoClient.class);var db=mock(MongoDatabase.class);when(client.getDatabase(anyString())).thenReturn(db);
        when(db.runCommand(any(Document.class))).thenThrow(new IllegalStateException("Unavailable"));
        assertThrows(IllegalStateException.class,()->new GeneratedDatabaseService(()->client).dropAll(java.util.UUID.randomUUID()));verify(db,never()).drop();
    }
}
