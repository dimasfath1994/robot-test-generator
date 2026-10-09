package com.hsbc.automation.robotgenerator.application;

import java.io.IOException;
import java.nio.file.Path;
import java.time.Clock;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import com.hsbc.automation.robotgenerator.domain.GenerationRequest;
import com.hsbc.automation.robotgenerator.infrastructure.filesystem.RobotProjectWriter;
import com.hsbc.automation.robotgenerator.infrastructure.template.RobotTemplateRenderer;

@Service
public class RobotProjectGenerationService {

    private static final DateTimeFormatter DATE_FORMAT = DateTimeFormatter.ofPattern("yyyy-MM-dd");

    private final RobotTemplateRenderer templateRenderer;
    private final RobotProjectWriter projectWriter;
    private final Clock clock;

    @Autowired
    public RobotProjectGenerationService(
            RobotTemplateRenderer templateRenderer,
            RobotProjectWriter projectWriter) {
        this(templateRenderer, projectWriter, Clock.systemDefaultZone());
    }

    public RobotProjectGenerationService(
            RobotTemplateRenderer templateRenderer,
            RobotProjectWriter projectWriter,
            Clock clock) {
        this.templateRenderer = templateRenderer;
        this.projectWriter = projectWriter;
        this.clock = clock;
    }

    public Path generate(GenerationRequest request) throws IOException {
        String projectDirectoryName = sanitize(request.getProjectName()) + "-"
                + LocalDate.now(clock).format(DATE_FORMAT);
        return projectWriter.write(
                request.getEimId(),
                projectDirectoryName,
                templateRenderer.renderBaseTest(request),
                templateRenderer.renderVariables(request),
                templateRenderer.renderTestCase(request),
                templateRenderer.renderRequirements()
        );
    }

    private String sanitize(String value) {
        String sanitized = value.trim().replaceAll("[^A-Za-z0-9_-]+", "-");
        sanitized = sanitized.replaceAll("^-+|-$", "");
        if (sanitized.isEmpty()) {
            throw new IllegalArgumentException("Project name must contain a letter or number.");
        }
        return sanitized;
    }
}