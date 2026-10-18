package com.hsbc.automation.robotgenerator.infrastructure.evidence;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.SocketTimeoutException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Base64;
import java.util.UUID;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;

@Component
public class EvidenceHttpClient {

    private static final int MAX_RESPONSE_BYTES = 32 * 1024 * 1024;
    private static final int MAX_REDIRECTS = 5;
    private final int connectTimeoutMs;
    private final int readTimeoutMs;
    private final int gridReadTimeoutMs;
    private final Environment environment;

    public EvidenceHttpClient() {
        this(15000, 120000, 180000);
    }

    public EvidenceHttpClient(int connectTimeoutMs, int readTimeoutMs, int gridReadTimeoutMs) {
        this(connectTimeoutMs, readTimeoutMs, gridReadTimeoutMs, null);
    }

    @Autowired
    public EvidenceHttpClient(
            @Value("${evidence.http.connect-timeout-ms:15000}") int connectTimeoutMs,
            @Value("${evidence.http.read-timeout-ms:120000}") int readTimeoutMs,
            @Value("${evidence.webdriver.read-timeout-ms:180000}") int gridReadTimeoutMs,
            Environment environment) {
        if (connectTimeoutMs <= 0 || readTimeoutMs <= 0 || gridReadTimeoutMs <= 0) {
            throw new IllegalArgumentException("Evidence HTTP timeouts must be positive milliseconds.");
        }
        this.connectTimeoutMs = connectTimeoutMs;
        this.readTimeoutMs = readTimeoutMs;
        this.gridReadTimeoutMs = gridReadTimeoutMs;
        this.environment = environment;
    }

    public String get(URI url, String username, String password) throws IOException {
        return request(url, "GET", null, null, false, username, password, true);
    }

    public String json(URI url, String method, String body, String username, String password) throws IOException {
        return request(url, method, body.getBytes(StandardCharsets.UTF_8), "application/json; charset=UTF-8", false, username, password, true);
    }

    public String webdriver(URI url, String method, String body) throws IOException {
        return request(url, method, body == null ? null : body.getBytes(StandardCharsets.UTF_8), "application/json; charset=UTF-8", false, "", "", false);
    }

    public void upload(URI url, Path screenshot, String filename, String username, String password)
            throws IOException {
        if (!filename.matches("[A-Za-z0-9._-]+")) {
            throw new IOException("Invalid evidence attachment filename.");
        }
        String boundary = "robot-evidence-" + UUID.randomUUID();
        ByteArrayOutputStream multipart = new ByteArrayOutputStream();
        multipart.write(("--" + boundary + "\r\nContent-Disposition: form-data; name=\"file\"; filename=\""
                + filename + "\"\r\nContent-Type: "
                + (filename.endsWith(".html") ? "text/html; charset=UTF-8" : "image/png")
                + "\r\n\r\n").getBytes(StandardCharsets.UTF_8));
        Files.copy(screenshot, multipart);
        multipart.write(("\r\n--" + boundary + "--\r\n").getBytes(StandardCharsets.UTF_8));
        request(url, "POST", multipart.toByteArray(), "multipart/form-data; boundary=" + boundary,
                true, username, password, true);
    }

    private String request(URI url, String method, byte[] body, String contentType, boolean attachment,
            String username, String password, boolean authenticated) throws IOException {
        return request(url, method, body, contentType, attachment, username, password, authenticated, 0);
    }

    private String request(URI url, String method, byte[] body, String contentType, boolean attachment,
            String username, String password, boolean authenticated, int redirects) throws IOException {
        boolean https = "https".equalsIgnoreCase(url.getScheme());
        if ((!https && (authenticated || !"http".equalsIgnoreCase(url.getScheme())))
            || url.getHost() == null || url.getUserInfo() != null) {
                throw new IOException("Evidence API URLs must use HTTPS without embedded credentials.");
        }
        HttpURLConnection connection = openConnection(url);
        int effectiveReadTimeoutMs = authenticated ? readTimeoutMs : gridReadTimeoutMs;
        String phase = "waiting for response";
        try {
            connection.setConnectTimeout(connectTimeoutMs);
            connection.setReadTimeout(effectiveReadTimeoutMs);
            connection.setInstanceFollowRedirects(false);
            connection.setRequestMethod(method);
            connection.setRequestProperty("Accept", "application/json, text/plain");
            if (authenticated) {
                connection.setRequestProperty("Authorization", authorization(url, username, password));
            }
            if (attachment) {
                connection.setRequestProperty("X-Atlassian-Token", "no-check");
            }
            if (body != null) {
                connection.setRequestProperty("Content-Type", contentType);
                connection.setDoOutput(true);
                connection.setFixedLengthStreamingMode(body.length);
                phase = "sending request body";
                try (OutputStream output = connection.getOutputStream()) {
                    output.write(body);
                }
            }
            phase = "waiting for response";
            int status = connection.getResponseCode();
            if (status == 301 || status == 302 || status == 303 || status == 307 || status == 308) {
                if (!authenticated || !"GET".equals(method)) {
                    throw new IOException("Evidence API returned HTTP " + status
                            + ". Redirects for write operations or Selenium Grid are not followed.");
                }
                if (redirects >= MAX_REDIRECTS) {
                    throw new IOException("Evidence API exceeded the safe GET redirect limit.");
                }
                String location = connection.getHeaderField("Location");
                URI target;
                try {
                    if (location == null || location.trim().isEmpty()) {
                        throw new IllegalArgumentException();
                    }
                    target = url.resolve(location);
                } catch (IllegalArgumentException exception) {
                    throw new IOException("Evidence API returned HTTP " + status + " with an invalid redirect location.");
                }
                if (!"https".equalsIgnoreCase(target.getScheme()) || target.getHost() == null
                        || target.getUserInfo() != null || !target.getHost().equalsIgnoreCase(url.getHost())
                        || effectivePort(url) != effectivePort(target)) {
                    throw new IOException("Evidence API returned HTTP " + status
                            + ". Cross-origin or insecure redirect blocked; credentials were not forwarded.");
                }
                String path = target.getPath() == null ? "" : target.getPath();
                if (path.matches("(?i).*(?:^|/)(?:login|signin|sign_in|sso|saml|oauth|authorize|auth)(?:/|\\.|$).*")) {
                    throw new IOException("Evidence API returned HTTP " + status
                            + " to a login/SSO page. Basic credentials cannot complete interactive SSO.");
                }
                connection.disconnect();
                return request(target, method, body, contentType, attachment, username, password, authenticated, redirects + 1);
            }
            if (status < 200 || status >= 300) {
                throw new IOException("Evidence API returned HTTP " + status + " from " + url.getHost()
                        + ". Check API credentials, permissions, or SSO requirements.");
            }
            phase = "reading response body";
            try (InputStream input = connection.getInputStream()) {
                ByteArrayOutputStream output = new ByteArrayOutputStream();
                byte[] buffer = new byte[4096];
                int length;
                while ((length = input.read(buffer)) != -1) {
                    if (output.size() + length > MAX_RESPONSE_BYTES) {
                        throw new IOException("Evidence API response exceeds 32 MB.");
                    }
                    output.write(buffer, 0, length);
                }
                return new String(output.toByteArray(), StandardCharsets.UTF_8);
            }
        } catch (java.net.SocketTimeoutException exception) {
            String writeWarning = "GET".equals(method) ? ""
                    : " The write/session result may be unknown; check the server before retrying.";
            throw new IOException("Timed out during " + operation(url, authenticated) + " (" + method
                    + " " + url.getHost() + ", " + phase + "; connect timeout=" + connectTimeoutMs
                    + "ms, read timeout=" + effectiveReadTimeoutMs + "ms). Check network/VPN and service capacity."
                    + writeWarning, exception);
        } finally {
            connection.disconnect();
        }
    }

    private String authorization(URI url, String username, String password) throws IOException {
        if (environment != null && isConfluenceApi(url)) {
            String token;
            try {
                token = environment.getProperty("my.pat.token.confluence", "").trim();
            } catch (IllegalArgumentException exception) {
                throw new IOException("my.pat.token.confluence could not be resolved; check its environment variable.");
            }
            if (!token.isEmpty()) {
                if (token.contains("\r") || token.contains("\n")) {
                    throw new IOException("Confluence PAT must not contain line breaks.");
                }
                return "Bearer " + token;
            }
        }
        return "Basic " + Base64.getEncoder().encodeToString(
                (username + ":" + password).getBytes(StandardCharsets.UTF_8));
    }

    private boolean isConfluenceApi(URI url) throws IOException {
        String configured = environment.getProperty("confluence.url", "").trim();
        if (configured.isEmpty()) {
            return false;
        }
        URI root;
        try {
            root = URI.create(configured);
        } catch (IllegalArgumentException exception) {
            throw new IOException("confluence.url must be a valid HTTPS page URL.");
        }
        if (!"https".equalsIgnoreCase(root.getScheme()) || root.getHost() == null || root.getUserInfo() != null
                || !url.getHost().equalsIgnoreCase(root.getHost()) || effectivePort(url) != effectivePort(root)) {
            return false;
        }
        String rootPath = root.getPath() == null ? "" : root.getPath();
        int marker = rootPath.indexOf("/spaces/");
        if (marker < 0) {
            marker = rootPath.indexOf("/pages/");
        }
        if (marker < 0) {
            return false;
        }
        String prefix = rootPath.substring(0, marker) + "/rest/api/content";
        String path = url.normalize().getPath();
        return prefix.equals(path) || path.startsWith(prefix + "/");
    }

    private String operation(URI url, boolean authenticated) {
        String path = url.getPath() == null ? "" : url.getPath();
        if (!authenticated) {
            if (path.endsWith("/session")) {
                return "Selenium Grid session creation";
            }
            if (path.endsWith("/screenshot")) {
                return "Selenium Grid screenshot capture";
            }
            if (path.endsWith("/execute/async")) {
                return "Selenium Grid Robot log rendering";
            }
            return "Selenium Grid command/cleanup";
        }
        if (path.endsWith("/api/json")) {
            return "Jenkins build/artifact metadata retrieval";
        }
        if (path.endsWith("/consoleText")) {
            return "Jenkins console log retrieval";
        }
        if (path.endsWith("/log.html")) {
            return "Robot log HTML download";
        }
        if (path.contains("/rest/api/content")) {
            return path.contains("/child/attachment") ? "Confluence attachment upload/lookup"
                    : "Confluence page lookup/create/update";
        }
        return "evidence HTTP request";
    }

    private int effectivePort(URI url) {
        return url.getPort() == -1 ? 443 : url.getPort();
    }

    protected HttpURLConnection openConnection(URI url) throws IOException {
        return (HttpURLConnection) url.toURL().openConnection();
    }
}