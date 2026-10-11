package com.hsbc.automation.robotgenerator.domain;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class GenerationRequestTest {
    @Test
    void trimsRequiredValuesAndNormalizesEndpoint() {
        GenerationRequest request = new GenerationRequest(" EIM-1 ", " Project ", "https://example.com/", "api/status");

        assertEquals("EIM-1", request.getEimId());
        assertEquals("Project", request.getProjectName());
        assertEquals("https://example.com", request.getBaseUrl());
        assertEquals("/api/status", request.getEndpoint());
        assertTrue(request.hasEndpoint());
    }

    @Test
    void defaultsBlankEndpointToRoot() {
        GenerationRequest request = new GenerationRequest("EIM-1", "Project", "http://example.com", " ");

        assertEquals("/", request.getEndpoint());
        assertFalse(request.hasEndpoint());
    }

    @Test
    void createsDatabaseRequestWithoutHttpUrl() {
        DatabaseCheck database = new DatabaseCheck("pymssql", "appdb", "sql.example", 1433,
                "SELECT 1", 1, "Verify DB", "Check database result");

        GenerationRequest request = new GenerationRequest("EIM-1", "DB Health", database);

        assertTrue(request.isDatabaseCheck());
        assertEquals(database, request.getDatabaseCheck());
        assertEquals("EIM-1", request.getEimId());
        assertEquals("DB Health", request.getProjectName());
        assertEquals("/", request.getEndpoint());
        assertFalse(request.hasEndpoint());
    }

    @Test
    void rejectsMissingRequiredValuesAndInvalidBaseUrl() {
        assertThrows(IllegalArgumentException.class, () -> new GenerationRequest(" ", "Project", "https://example.com", "/"));
        assertThrows(IllegalArgumentException.class, () -> new GenerationRequest("EIM-1", "Project", "ftp://example.com", "/"));
        assertThrows(IllegalArgumentException.class, () -> new GenerationRequest("EIM-1", "Project", "not a url", "/"));
    }
}
