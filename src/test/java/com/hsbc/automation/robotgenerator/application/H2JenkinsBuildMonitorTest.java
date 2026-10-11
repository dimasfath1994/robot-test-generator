package com.hsbc.automation.robotgenerator.application;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.net.URI;
import java.util.HashMap;
import java.util.Map;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.core.env.MapPropertySource;
import org.springframework.core.env.StandardEnvironment;

import com.hsbc.automation.robotgenerator.infrastructure.evidence.EvidenceHttpClient;

class H2JenkinsBuildMonitorTest {
    private RecordingHttpClient http;
    private H2JenkinsBuildMonitor monitor;

    @BeforeEach
    void setUp() {
        StandardEnvironment environment = new StandardEnvironment();
        Map<String, Object> properties = new HashMap<>();
        properties.put("my.username", "jenkins-user");
        properties.put("my.password", "jenkins-password");
        environment.getPropertySources().addFirst(new MapPropertySource("test", properties));
        http = new RecordingHttpClient();
        monitor = new H2JenkinsBuildMonitor(http, environment);
    }

    @Test
    void returnsFalseWhileBuildIsRunningAndUsesBuildMetadataUrl() throws Exception {
        http.response = "{\"number\":42,\"building\":true,\"result\":null}";

        assertFalse(monitor.isComplete("https://jenkins.example/job/app/42/artifact/log.html"));

        assertEquals("https://jenkins.example/job/app/42/api/json?tree=number,building,result", http.url.toString());
        assertEquals("jenkins-user", http.username);
        assertEquals("jenkins-password", http.password);
    }

    @Test
    void returnsTrueAfterBuildCompletesWithResult() throws Exception {
        http.response = "{\"number\":42,\"building\":false,\"result\":\"SUCCESS\"}";

        assertTrue(monitor.isComplete("https://jenkins.example/job/app/42/"));
    }

    @Test
    void rejectsInvalidBuildUrlsAndMismatchedMetadataBeforeTreatingBuildComplete() {
        assertThrows(IOException.class, () -> monitor.isComplete("http://jenkins.example/job/app/42"));
        assertThrows(IOException.class, () -> monitor.isComplete("https://jenkins.example/job/app/lastBuild"));

        http.response = "{\"number\":41,\"building\":false,\"result\":\"SUCCESS\"}";
        assertThrows(IOException.class, () -> monitor.isComplete("https://jenkins.example/job/app/42"));
    }

    private static class RecordingHttpClient extends EvidenceHttpClient {
        private String response = "";
        private URI url;
        private String username;
        private String password;

        private RecordingHttpClient() {
            super(1000, 1000, 1000);
        }

        @Override
        public String get(URI url, String username, String password) {
            this.url = url;
            this.username = username;
            this.password = password;
            return response;
        }
    }
}