package com.hsbc.automation.robotgenerator.infrastructure.filesystem;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class RobotProjectWriterTest {
    @TempDir
    Path temporaryDirectory;

    @Test
    void writesRobotProjectFilesToEimAndProjectFolders() throws Exception {
        RobotProjectWriter writer = new RobotProjectWriter(temporaryDirectory.toString());

        Path project = writer.write("EIM-42", "Portal-2026-10-10", "base robot", "variables robot",
                "test robot", "robotframework\n");

        assertEquals(temporaryDirectory.resolve("EIM-42/Portal-2026-10-10"), project);
        assertEquals("base robot", new String(Files.readAllBytes(project.resolve("Keywords/BaseTest.robot")), StandardCharsets.UTF_8));
        assertEquals("variables robot", new String(Files.readAllBytes(project.resolve("Resources/locators.robot")), StandardCharsets.UTF_8));
        assertEquals("test robot", new String(Files.readAllBytes(project.resolve("Tests/Regression/TS01_TC01.robot")), StandardCharsets.UTF_8));
        assertTrue(Files.exists(project.resolve("requirements.txt")));
    }
}
