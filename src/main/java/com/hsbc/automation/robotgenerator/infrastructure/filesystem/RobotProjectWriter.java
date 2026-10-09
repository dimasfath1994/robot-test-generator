package com.hsbc.automation.robotgenerator.infrastructure.filesystem;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

@Component
public class RobotProjectWriter {
    private final Path outputDirectory;
    public RobotProjectWriter(@Value("${generator.output-directory:generated}") String outputDirectory) {
        this.outputDirectory = Paths.get(outputDirectory).toAbsolutePath().normalize();
    }
    public Path write(String eimId, String projectDirectoryName, String baseTest, String variables,
            String testCase, String requirements) throws IOException {
        Path projectDirectory = outputDirectory.resolve(sanitizeSegment(eimId)).resolve(projectDirectoryName).normalize();
        if (!projectDirectory.startsWith(outputDirectory)) throw new IllegalArgumentException("Invalid output path.");
        Path keywordsDirectory = projectDirectory.resolve("Keywords");
        Path resourcesDirectory = projectDirectory.resolve("Resources");
        Path testsDirectory = projectDirectory.resolve("Tests").resolve("Regression");
        Files.createDirectories(keywordsDirectory);
        Files.createDirectories(resourcesDirectory);
        Files.createDirectories(testsDirectory);
        writeFile(keywordsDirectory.resolve("BaseTest.robot"), baseTest);
        writeFile(resourcesDirectory.resolve("locators.robot"), variables);
        writeFile(testsDirectory.resolve("TS01_TC01.robot"), testCase);
        writeFile(projectDirectory.resolve("requirements.txt"), requirements);
        return projectDirectory;
    }
    private String sanitizeSegment(String value) {
        String sanitized = value.trim().replaceAll("[^A-Za-z0-9_-]+", "-");
        if (sanitized.isEmpty() || ".".equals(sanitized) || "..".equals(sanitized)) throw new IllegalArgumentException("EIM ID is invalid.");
        return sanitized;
    }
    private void writeFile(Path path, String content) throws IOException {
        Files.write(path, content.getBytes(StandardCharsets.UTF_8));
    }
}
