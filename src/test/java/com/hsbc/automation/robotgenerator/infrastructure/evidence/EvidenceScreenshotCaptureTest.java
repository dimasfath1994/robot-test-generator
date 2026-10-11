package com.hsbc.automation.robotgenerator.infrastructure.evidence;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.net.URI;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Base64;
import java.util.List;
import java.util.HashMap;
import java.util.Map;

import javax.imageio.ImageIO;

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

    @Test
    void fallsBackToLocalCaptureWhenGridIsNotReady() throws Exception {
        Map<String, Object> properties = new HashMap<>();
        properties.put("evidence.webdriver.url", "https://grid.example/wd/hub");
        RecordingHttpClient http = new RecordingHttpClient();
        http.response = "{\"value\":{\"ready\":false}}";
        CapturingScreenshotCapture capture = new CapturingScreenshotCapture(http, environment(properties));

        capture.capture("<html>report</html>", temporaryDirectory.resolve("capture.png"));

        assertEquals("<html>report</html>", capture.localHtml);
        assertEquals("edge", capture.localBrowser);
        assertEquals("https://grid.example/wd/hub/status", http.lastUrl.toString());
        assertTrue(capture.localCaptureCalled);
    }

    @Test
    void createsGridSessionRendersReportCapturesPngAndDeletesSession() throws Exception {
        Map<String, Object> properties = new HashMap<>();
        properties.put("evidence.webdriver.url", "https://grid.example/wd/hub");
        properties.put("evidence.webdriver.browser", "chrome");
        ScriptedHttpClient http = new ScriptedHttpClient();
        http.responses.add("{\"value\":{\"ready\":true}}");
        http.responses.add("{\"value\":{\"sessionId\":\"session-42\"}}");
        http.responses.add("{\"value\":{}}");
        http.responses.add("{\"value\":{}}");
        http.responses.add("{\"value\":{}}");
        http.responses.add("{\"value\":{}}");
        http.responses.add("{\"value\":true}");
        http.responses.add("{\"value\":\"" + tinyPngBase64() + "\"}");
        http.responses.add("{\"value\":null}");
        EvidenceScreenshotCapture capture = new EvidenceScreenshotCapture(http, environment(properties));
        Path screenshot = temporaryDirectory.resolve("grid-capture.png");

        capture.capture("<html><body>Robot Framework</body></html>", screenshot);

        assertEquals(Arrays.asList("GET", "POST", "POST", "POST", "POST", "POST", "POST", "GET", "DELETE"), http.methods);
        assertTrue(http.requestBodies.get(1).contains("chrome"));
        assertTrue(http.requestBodies.get(5).contains("Robot Framework"));
        assertEquals("https://grid.example/wd/hub/session/session-42", http.urls.get(8).toString());
        assertTrue(ImageIO.read(screenshot.toFile()) instanceof BufferedImage);
    }

    private StandardEnvironment environment(Map<String, Object> properties) {
        StandardEnvironment environment = new StandardEnvironment();
        environment.getPropertySources().addFirst(new MapPropertySource("test", properties));
        return environment;
    }

    private static class RecordingHttpClient extends EvidenceHttpClient {
        private int requests;
        private String response = "";
        private URI lastUrl;

        private RecordingHttpClient() {
            super(1000, 1000, 1000);
        }

        @Override
        public String webdriver(URI url, String method, String body) {
            requests++;
            lastUrl = url;
            return response;
        }
    }

    private static class ScriptedHttpClient extends EvidenceHttpClient {
        private final List<String> responses = new ArrayList<>();
        private final List<String> methods = new ArrayList<>();
        private final List<URI> urls = new ArrayList<>();
        private final List<String> requestBodies = new ArrayList<>();

        private ScriptedHttpClient() {
            super(1000, 1000, 1000);
        }

        @Override
        public String webdriver(URI url, String method, String body) {
            urls.add(url);
            methods.add(method);
            requestBodies.add(body == null ? "" : body);
            return responses.remove(0);
        }
    }

    private String tinyPngBase64() throws IOException {
        BufferedImage image = new BufferedImage(2, 2, BufferedImage.TYPE_INT_RGB);
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        ImageIO.write(image, "png", bytes);
        return Base64.getEncoder().encodeToString(bytes.toByteArray());
    }

    private static class CapturingScreenshotCapture extends EvidenceScreenshotCapture {
        private boolean localCaptureCalled;
        private String localHtml;
        private String localBrowser;

        private CapturingScreenshotCapture(EvidenceHttpClient http, StandardEnvironment environment) {
            super(http, environment);
        }

        @Override
        protected void captureLocally(String html, Path screenshot, String browser) {
            localCaptureCalled = true;
            localHtml = html;
            localBrowser = browser;
        }
    }
}