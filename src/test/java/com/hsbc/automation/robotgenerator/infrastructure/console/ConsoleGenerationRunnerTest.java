package com.hsbc.automation.robotgenerator.infrastructure.console;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Clock;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.hsbc.automation.robotgenerator.application.RobotProjectGenerationService;
import com.hsbc.automation.robotgenerator.domain.GenerationRequest;
import com.hsbc.automation.robotgenerator.infrastructure.filesystem.RobotProjectWriter;
import com.hsbc.automation.robotgenerator.infrastructure.git.RobotGitSyncService;
import com.hsbc.automation.robotgenerator.infrastructure.template.RobotTemplateRenderer;

class ConsoleGenerationRunnerTest {
    @TempDir
    Path temporaryDirectory;

    @Test
    void doesNotGenerateProjectWhenConsoleInputIsInvalid() throws Exception {
        AtomicInteger generationCalls = new AtomicInteger();
        RobotProjectGenerationService generationService = new RobotProjectGenerationService(
                new RobotTemplateRenderer(), new RobotProjectWriter(temporaryDirectory.toString()), Clock.systemUTC()) {
            @Override
            public Path generate(GenerationRequest request) {
                generationCalls.incrementAndGet();
                return temporaryDirectory;
            }
        };
        ConsoleGenerationRunner runner = new ConsoleGenerationRunner(generationService, new RobotGitSyncService());
        InputStream originalInput = System.in;
        System.setIn(new ByteArrayInputStream("EIM-42\nPortal\nnot a url\n/\n".getBytes(StandardCharsets.UTF_8)));
        try {
            runner.run();
        } finally {
            System.setIn(originalInput);
        }

        assertEquals(0, generationCalls.get());
    }
}