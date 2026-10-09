package com.hsbc.automation.robotgenerator.infrastructure.evidence;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.io.IOException;
import java.net.URI;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.core.env.MapPropertySource;
import org.springframework.core.env.StandardEnvironment;

class EvidenceScreenshotCaptureTest {
    @TempDir
    Path temporaryDirectory;

    @Test
    void requiresWebdriverGridUrlBeforeSendingRequests() {
        StandardEnvironment environment = environment(new HashMap<String, Object>());
        RecordingHttpClient http = new RecordingHttpClient();
        EvidenceScreenshotCapture capture = new EvidenceScreenshotCapture(http, environment);

        IOException exception = assertThrows(IOException.class,
                () -> capture.capture("<html></html>", temporaryDirectory.resolve("capture.png")));

        assertEquals("evidence.webdriver.url is required for screenshot capture (Selenium Grid URL).", exception.getMessage());
        assertEquals(0, http.requests);
    }

    @Test
    void rejectsUnsupportedBrowserBeforeSendingRequests() {
        Map<String, Object> properties = new HashMap<>();
        properties.put("evidence.webdriver.url", "https://grid.example");
        properties.put("evidence.webdriver.browser", "firefox");
        RecordingHttpClient http = new RecordingHttpClient();
        EvidenceScreenshotCapture capture = new EvidenceScreenshotCapture(http, environment(properties));

        IOException exception = assertThrows(IOException.class,
                () -> capture.capture("<html></html>", temporaryDirectory.resolve("capture.png")));

        assertEquals("evidence.webdriver.browser must be either 'edge' or 'chrome'.", exception.getMessage());
        assertEquals(0, http.requests);
    }

    private StandardEnvironment environment(Map<String, Object> properties) {
        StandardEnvironment environment = new StandardEnvironment();
        environment.getPropertySources().addFirst(new MapPropertySource("test", properties));
        return environment;
    }

    private static class RecordingHttpClient extends EvidenceHttpClient {
        private int requests;

        private RecordingHttpClient() {
            super(1000, 1000, 1000);
        }

        @Override
        public String webdriver(URI url, String method, String body) {
            requests++;
            throw new AssertionError("No WebDriver request should be made for invalid configuration.");
        }
    }
}