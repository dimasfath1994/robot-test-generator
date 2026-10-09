package com.hsbc.automation.robotgenerator.infrastructure.git;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.io.IOException;
import java.nio.file.Path;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class RobotGitSyncServiceTest {
    @TempDir
    Path temporaryDirectory;

    @Test
    void rejectsUnsupportedRepositoryUrlBeforeRunningGit() {
        RobotGitSyncService service = new RobotGitSyncService();

        IllegalArgumentException exception = assertThrows(IllegalArgumentException.class,
                () -> service.sync(temporaryDirectory, "ftp://git.example/team/project", () -> "",
                        () -> RobotGitSyncService.SyncMode.OVERWRITE));

        assertEquals("Repository URL must be a valid HTTPS or Git SSH URL without embedded credentials.", exception.getMessage());
    }

    @Test
    void rejectsMissingGeneratedProjectItemsBeforeCloning() {
        RobotGitSyncService service = new RobotGitSyncService();

        IOException exception = assertThrows(IOException.class,
                () -> service.sync(temporaryDirectory, "https://git.example/team/project", () -> "",
                        () -> RobotGitSyncService.SyncMode.OVERWRITE));

        assertEquals("Missing or invalid generated item: Keywords", exception.getMessage());
    }
}