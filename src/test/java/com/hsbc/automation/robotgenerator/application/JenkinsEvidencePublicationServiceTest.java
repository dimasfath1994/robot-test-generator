package com.hsbc.automation.robotgenerator.application;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.io.IOException;
import java.net.URI;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.HashMap;
import java.util.Map;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.core.env.MapPropertySource;
import org.springframework.core.env.StandardEnvironment;

import com.hsbc.automation.robotgenerator.domain.GenerationRequest;
import com.hsbc.automation.robotgenerator.infrastructure.evidence.EvidenceHttpClient;
import com.hsbc.automation.robotgenerator.infrastructure.evidence.EvidenceScreenshotCapture;

class JenkinsEvidencePublicationServiceTest {
    private RecordingHttpClient http;
    private JenkinsEvidencePublicationService service;

    @BeforeEach
    void setUp() {
        StandardEnvironment environment = new StandardEnvironment();
        Map<String, Object> properties = new HashMap<>();
        properties.put("my.username", "test-user");
        properties.put("my.password", "test-password");
        properties.put("confluence.url", "https://confluence.example/spaces/QA/pages/123/root");
        environment.getPropertySources().addFirst(new MapPropertySource("test", properties));
        http = new RecordingHttpClient();
        EvidenceScreenshotCapture screenshots = new EvidenceScreenshotCapture(http, environment);
        service = new JenkinsEvidencePublicationService(http, screenshots, environment,
                Clock.fixed(Instant.parse("2026-10-10T12:00:00Z"), ZoneOffset.UTC));
    }

    @Test
    void rejectsNonBuildJenkinsUrlBeforeMakingHttpRequests() {
        IOException exception = assertThrows(IOException.class, () -> service.publish(request(),
                "https://jenkins.example/job/app/latest", "https://git.example/team/project"));

        assertEquals("Enter a specific completed Jenkins build URL ending in </build-number/>.", exception.getMessage());
        assertEquals(0, http.getRequests);
    }

    @Test
    void rejectsDirectArtifactWhenJenkinsReturnsMetadataForAnotherBuild() {
        http.consoleText = "{\"number\":41,\"building\":false,\"result\":\"SUCCESS\"}";

        IOException exception = assertThrows(IOException.class, () -> service.publish(request(),
                "https://jenkins.example/job/app/42/artifact/log.html", "https://git.example/team/project"));

        assertEquals("Jenkins returned metadata for a different build.", exception.getMessage());
        assertEquals(1, http.getRequests);
    }

    private GenerationRequest request() {
        return new GenerationRequest("EIM-42", "Portal", "https://example.com", "/health");
    }

    private static class RecordingHttpClient extends EvidenceHttpClient {
        private int getRequests;
        private String consoleText = "";

        private RecordingHttpClient() {
            super(1000, 1000, 1000);
        }

        @Override
        public String get(URI url, String username, String password) {
            getRequests++;
            return consoleText;
        }

        @Override
        public String json(URI url, String method, String body, String username, String password) {
            throw new AssertionError("No Confluence request should be made for rejected input.");
        }

        @Override
        public void upload(URI url, Path file, String filename, String username, String password) {
            throw new AssertionError("No attachment should be uploaded for rejected input.");
        }
    }
}