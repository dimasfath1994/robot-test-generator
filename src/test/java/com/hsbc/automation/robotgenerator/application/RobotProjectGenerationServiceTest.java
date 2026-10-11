package com.hsbc.automation.robotgenerator.application;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.hsbc.automation.robotgenerator.domain.GenerationRequest;
import com.hsbc.automation.robotgenerator.domain.DatabaseCheck;
import com.hsbc.automation.robotgenerator.infrastructure.filesystem.RobotProjectWriter;
import com.hsbc.automation.robotgenerator.infrastructure.template.RobotTemplateRenderer;

class RobotProjectGenerationServiceTest {
    @TempDir
    Path temporaryDirectory;

    @Test
    void generatesProjectWithSanitizedNameAndClockDate() throws Exception {
        RobotProjectGenerationService service = new RobotProjectGenerationService(
                new RobotTemplateRenderer(), new RobotProjectWriter(temporaryDirectory.toString()),
                Clock.fixed(Instant.parse("2026-10-10T12:00:00Z"), ZoneOffset.UTC));

        Path project = service.generate(new GenerationRequest(
                "EIM-42", "  Portal / Health Check!  ", "https://example.com", "/health"));
        List<String> variables = Files.readAllLines(project.resolve("Resources/locators.robot"));

        assertEquals(temporaryDirectory.resolve("EIM-42/Portal-Health-Check-2026-10-10"), project);
        assertEquals("${BASE_URL}    https://example.com", variables.get(1));
        assertEquals("${ENDPOINT}    /health", variables.get(2));
        assertTrue(Files.exists(project.resolve("Tests/Regression/TS01_TC01.robot")));
    }

    @Test
    void rejectsProjectNameWithoutLettersOrNumbersBeforeWriting() {
        RobotProjectGenerationService service = new RobotProjectGenerationService(
                new RobotTemplateRenderer(), new RobotProjectWriter(temporaryDirectory.toString()),
                Clock.fixed(Instant.parse("2026-10-10T12:00:00Z"), ZoneOffset.UTC));

        assertThrows(IllegalArgumentException.class, () -> service.generate(
                new GenerationRequest("EIM-42", "--- !!! ---", "https://example.com", "/")));
    }

        @Test
        void generatesDatabaseProjectWithDatabaseDependenciesAndCredentialsFromEnvironment() throws Exception {
                RobotProjectGenerationService service = new RobotProjectGenerationService(
                                new RobotTemplateRenderer(), new RobotProjectWriter(temporaryDirectory.toString()),
                                Clock.fixed(Instant.parse("2026-10-10T12:00:00Z"), ZoneOffset.UTC));
                DatabaseCheck database = new DatabaseCheck("pymssql", "appdb", "sql.example", 1433,
                                "SELECT 1", 1, "Verify DB", "Check database result");

                Path project = service.generate(new GenerationRequest("EIM-42", "Database Health", database));
                String variables = new String(Files.readAllBytes(project.resolve("Resources/locators.robot")), StandardCharsets.UTF_8);
                String requirements = new String(Files.readAllBytes(project.resolve("requirements.txt")), StandardCharsets.UTF_8);
                String testCase = new String(Files.readAllBytes(project.resolve("Tests/Regression/TS01_TC01.robot")), StandardCharsets.UTF_8);

                assertTrue(variables.contains("${DB_USERNAME}    %{DB_USERNAME}"));
                assertTrue(variables.contains("${DB_PASSWORD}    %{DB_PASSWORD}"));
                assertTrue(requirements.contains("robotframework-databaselibrary"));
                assertTrue(requirements.contains("pymssql"));
                assertTrue(testCase.contains("*** Test Cases ***"));
                assertTrue(testCase.contains("${TC01_TEST_CASE_NAME}"));
        }
}