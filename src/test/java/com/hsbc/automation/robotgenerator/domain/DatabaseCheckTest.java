package com.hsbc.automation.robotgenerator.domain;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class DatabaseCheckTest {
    @Test
    void trimsConnectionValuesAndDefaultsEnvironmentCredentialNames() {
        DatabaseCheck database = new DatabaseCheck(" pymssql ", " appdb ", " sql.example ", 1433,
                "SELECT 1", 0, " Verify query ", " Check query results ");

        assertEquals("pymssql", database.getModule());
        assertEquals("appdb", database.getName());
        assertEquals("sql.example", database.getHost());
        assertEquals(1433, database.getPort());
        assertEquals("SELECT 1", database.getQuery());
        assertEquals(0, database.getExpectedRowCount());
        assertEquals("Verify query", database.getTestName());
        assertEquals("Check query results", database.getDescription());
        assertTrue(database.usesEnvironmentCredentials());
        assertEquals("DB_USERNAME", database.getUsername());
        assertEquals("DB_PASSWORD", database.getPassword());
    }

    @Test
    void acceptsExplicitEnvironmentNamesAndLiteralCredentials() {
        DatabaseCheck environmentCredentials = new DatabaseCheck("pymssql", "appdb", "sql.example", 1433,
                "SELECT 1", 1, "Verify", "Description", " env ", "APP_DB_USER", "APP_DB_PASSWORD");
        DatabaseCheck literalCredentials = new DatabaseCheck("pymssql", "appdb", "sql.example", 1433,
                "SELECT 1", 1, "Verify", "Description", "literal", "db-user", "db-password");

        assertTrue(environmentCredentials.usesEnvironmentCredentials());
        assertEquals("APP_DB_USER", environmentCredentials.getUsername());
        assertEquals("APP_DB_PASSWORD", environmentCredentials.getPassword());
        assertFalse(literalCredentials.usesEnvironmentCredentials());
        assertEquals("db-user", literalCredentials.getUsername());
        assertEquals("db-password", literalCredentials.getPassword());
    }

    @Test
    void rejectsInvalidConnectionValuesAndCredentialReferences() {
        assertThrows(IllegalArgumentException.class, () -> new DatabaseCheck(
                "mysql", "appdb", "sql.example", 1433, "SELECT 1", 1, "Verify", "Description"));
        assertThrows(IllegalArgumentException.class, () -> new DatabaseCheck(
                "pymssql", "appdb", "sql.example", 0, "SELECT 1", 1, "Verify", "Description"));
        assertThrows(IllegalArgumentException.class, () -> new DatabaseCheck(
                "pymssql", "appdb", "sql.example", 1433, "SELECT 1", -1, "Verify", "Description"));
        assertThrows(IllegalArgumentException.class, () -> new DatabaseCheck(
                "pymssql", "appdb", "sql.example", 1433, "SELECT 1", 1, "Verify", "Description",
                "ENV", "db-user", "DB_PASSWORD"));
        assertThrows(IllegalArgumentException.class, () -> new DatabaseCheck(
                "pymssql", "appdb", "sql.example", 1433, "SELECT 1", 1, "Verify", "Description",
                "LITERAL", "db-user", "line1\nline2"));
    }
}