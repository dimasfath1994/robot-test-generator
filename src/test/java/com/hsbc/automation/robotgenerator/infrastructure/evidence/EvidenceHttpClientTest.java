package com.hsbc.automation.robotgenerator.infrastructure.evidence;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URI;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.core.env.MapPropertySource;
import org.springframework.core.env.StandardEnvironment;

class EvidenceHttpClientTest {
    @TempDir
    Path temporaryDirectory;

    @Test
    void rejectsNonPositiveTimeouts() {
        IllegalArgumentException exception = assertThrows(IllegalArgumentException.class,
                () -> new EvidenceHttpClient(0, 1000, 1000));

        assertEquals("Evidence HTTP timeouts must be positive milliseconds.", exception.getMessage());
    }

    @Test
    void rejectsUnsafeAttachmentFilenameBeforeReadingOrSendingFile() {
        EvidenceHttpClient client = new EvidenceHttpClient(1000, 1000, 1000);

        IOException exception = assertThrows(IOException.class, () -> client.upload(
                URI.create("https://confluence.example/attachment"), temporaryDirectory.resolve("missing.png"),
                "../evidence.png", "user", "password"));

        assertEquals("Invalid evidence attachment filename.", exception.getMessage());
    }

    @Test
    void sendsBasicAuthorizationAndReturnsSuccessfulResponse() throws Exception {
        StubConnection connection = new StubConnection("https://jenkins.example/api", 200, "ok", null);
        StubHttpClient client = client(connection);

        assertEquals("ok", client.get(URI.create("https://jenkins.example/api"), "robot", "secret"));

        assertEquals("Basic cm9ib3Q6c2VjcmV0", connection.requestProperties.get("Authorization"));
        assertEquals("GET", connection.requestMethod);
        assertTrue(connection.disconnected);
    }

    @Test
    void followsSameOriginGetRedirectAndBlocksCrossOriginRedirect() throws Exception {
        StubConnection redirect = new StubConnection(
                "https://jenkins.example/start", 302, "", "/api/build");
        StubConnection response = new StubConnection("https://jenkins.example/api/build", 200, "build", null);
        StubHttpClient client = client(redirect, response);

        assertEquals("build", client.get(URI.create("https://jenkins.example/start"), "robot", "secret"));
        assertEquals(2, client.connections.size());
        assertTrue(redirect.disconnected);
        assertEquals("Basic cm9ib3Q6c2VjcmV0", response.requestProperties.get("Authorization"));

        StubConnection unsafeRedirect = new StubConnection(
                "https://jenkins.example/start", 302, "", "https://attacker.example/collect");
        StubHttpClient blockedClient = client(unsafeRedirect);
        IOException exception = assertThrows(IOException.class, () -> blockedClient.get(
                URI.create("https://jenkins.example/start"), "robot", "secret"));

        assertTrue(exception.getMessage().contains("Cross-origin or insecure redirect blocked"));
        assertEquals(1, blockedClient.connections.size());
    }

    @Test
    void rejectsRedirectForWriteRequestAndReportsHttpErrors() throws Exception {
        StubHttpClient redirectClient = client(new StubConnection(
                "https://confluence.example/rest/api/content", 302, "", "/login"));
        IOException redirect = assertThrows(IOException.class, () -> redirectClient.json(
                URI.create("https://confluence.example/rest/api/content"), "POST", "{}", "user", "password"));
        assertTrue(redirect.getMessage().contains("Redirects for write operations"));

        StubHttpClient errorClient = client(new StubConnection(
                "https://jenkins.example/api", 401, "unauthorized", null));
        IOException error = assertThrows(IOException.class, () -> errorClient.get(
                URI.create("https://jenkins.example/api"), "user", "password"));
        assertTrue(error.getMessage().contains("HTTP 401"));
    }

    @Test
    void usesConfluencePatAndSeleniumGridTimeoutForTheirRequests() throws Exception {
        Map<String, Object> properties = new HashMap<>();
        properties.put("confluence.url", "https://confluence.example/wiki/spaces/QA/pages/12/root");
        properties.put("my.pat.token.confluence", "test-pat");
        StandardEnvironment environment = new StandardEnvironment();
        environment.getPropertySources().addFirst(new MapPropertySource("test", properties));
        StubConnection confluence = new StubConnection(
                "https://confluence.example/wiki/rest/api/content/12", 200, "{}", null);
        StubHttpClient client = new StubHttpClient(environment, confluence);

        client.get(URI.create("https://confluence.example/wiki/rest/api/content/12"), "user", "password");

        assertEquals("Bearer test-pat", confluence.requestProperties.get("Authorization"));

        StubConnection grid = new StubConnection("https://grid.example/session", 200, "{}", null);
        StubHttpClient gridClient = client(grid);
        gridClient.webdriver(URI.create("https://grid.example/session"), "GET", null);

        assertEquals(3000, grid.readTimeout);
        assertFalse(grid.requestProperties.containsKey("Authorization"));
    }

    @Test
    void uploadsMultipartAttachmentWithAtlassianTokenHeader() throws Exception {
        Path file = temporaryDirectory.resolve("log.html");
        Files.write(file, "robot log".getBytes(StandardCharsets.UTF_8));
        StubConnection connection = new StubConnection("https://confluence.example/attachment", 200, "ok", null);
        StubHttpClient client = client(connection);

        client.upload(URI.create("https://confluence.example/attachment"), file,
                "log.html", "user", "password");

        String body = new String(connection.requestBody.toByteArray(), StandardCharsets.UTF_8);
        assertEquals("no-check", connection.requestProperties.get("X-Atlassian-Token"));
        assertTrue(connection.requestProperties.get("Content-Type").startsWith("multipart/form-data; boundary=robot-evidence-"));
        assertTrue(body.contains("filename=\"log.html\""));
        assertTrue(body.contains("Content-Type: text/html; charset=UTF-8"));
        assertTrue(body.contains("robot log"));
    }

    private StubHttpClient client(StubConnection... connections) {
        return new StubHttpClient(null, connections);
    }

    private static class StubHttpClient extends EvidenceHttpClient {
        private final List<StubConnection> connections = new ArrayList<>();
        private int nextConnection;

        private StubHttpClient(StubConnection... connections) {
            this(null, connections);
        }

        private StubHttpClient(StandardEnvironment environment, StubConnection... connections) {
            super(1000, 2000, 3000, environment);
            this.connections.addAll(java.util.Arrays.asList(connections));
        }

        @Override
        protected HttpURLConnection openConnection(URI url) throws IOException {
            if (nextConnection >= connections.size()) {
                throw new IOException("No stub connection configured for " + url);
            }
            return connections.get(nextConnection++);
        }
    }

    private static class StubConnection extends HttpURLConnection {
        private final int status;
        private final String response;
        private final String location;
        private final Map<String, String> requestProperties = new HashMap<>();
        private final ByteArrayOutputStream requestBody = new ByteArrayOutputStream();
        private String requestMethod;
        private int readTimeout;
        private boolean disconnected;

        private StubConnection(String url, int status, String response, String location) throws Exception {
            super(new URL(url));
            this.status = status;
            this.response = response;
            this.location = location;
        }

        @Override
        public void setRequestMethod(String method) {
            requestMethod = method;
        }

        @Override
        public void setRequestProperty(String key, String value) {
            requestProperties.put(key, value);
        }

        @Override
        public void setReadTimeout(int timeout) {
            readTimeout = timeout;
        }

        @Override
        public OutputStream getOutputStream() {
            return requestBody;
        }

        @Override
        public int getResponseCode() {
            return status;
        }

        @Override
        public String getHeaderField(String name) {
            return "Location".equalsIgnoreCase(name) ? location : null;
        }

        @Override
        public InputStream getInputStream() throws IOException {
            if (status < 200 || status >= 300) {
                throw new IOException("HTTP " + status);
            }
            return new ByteArrayInputStream(response.getBytes(StandardCharsets.UTF_8));
        }

        @Override
        public void disconnect() {
            disconnected = true;
        }

        @Override
        public boolean usingProxy() {
            return false;
        }

        @Override
        public void connect() {
        }
    }
}