package com.hsbc.automation.robotgenerator.infrastructure.evidence;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
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
    private final int connectTimeoutMs, readTimeoutMs, gridReadTimeoutMs;
    private final Environment environment;
    public EvidenceHttpClient() { this(15000, 120000, 180000, null); }
    public EvidenceHttpClient(int connectTimeoutMs, int readTimeoutMs, int gridReadTimeoutMs) { this(connectTimeoutMs, readTimeoutMs, gridReadTimeoutMs, null); }
    @Autowired
    public EvidenceHttpClient(@Value("${evidence.http.connect-timeout-ms:15000}") int connectTimeoutMs,
            @Value("${evidence.http.read-timeout-ms:120000}") int readTimeoutMs,
            @Value("${evidence.webdriver.read-timeout-ms:180000}") int gridReadTimeoutMs, Environment environment) {
        if (connectTimeoutMs <= 0 || readTimeoutMs <= 0 || gridReadTimeoutMs <= 0) throw new IllegalArgumentException("Evidence HTTP timeouts must be positive milliseconds.");
        this.connectTimeoutMs = connectTimeoutMs; this.readTimeoutMs = readTimeoutMs; this.gridReadTimeoutMs = gridReadTimeoutMs; this.environment = environment;
    }
    public String get(URI url, String username, String password) throws IOException { return request(url, "GET", null, null, false, username, password, true); }
    public String json(URI url, String method, String body, String username, String password) throws IOException {
        return request(url, method, body.getBytes(StandardCharsets.UTF_8), "application/json; charset=UTF-8", false, username, password, true);
    }
    public String webdriver(URI url, String method, String body) throws IOException {
        return request(url, method, body == null ? null : body.getBytes(StandardCharsets.UTF_8), "application/json; charset=UTF-8", false, "", "", false);
    }
    public void upload(URI url, Path file, String filename, String username, String password) throws IOException {
        if (!filename.matches("[A-Za-z0-9_.-]+")) throw new IOException("Invalid evidence attachment filename.");
        String boundary = "robot-evidence-" + UUID.randomUUID();
        ByteArrayOutputStream data = new ByteArrayOutputStream();
        data.write(("--" + boundary + "\r\nContent-Disposition: form-data; name=\"file\"; filename=\"" + filename + "\"\r\nContent-Type: " + (filename.endsWith(".html") ? "text/html; charset=UTF-8" : "image/png") + "\r\n\r\n").getBytes(StandardCharsets.UTF_8));
        Files.copy(file, data);
        data.write(("\r\n--" + boundary + "--\r\n").getBytes(StandardCharsets.UTF_8));
        request(url, "POST", data.toByteArray(), "multipart/form-data; boundary=" + boundary, true, username, password, true);
    }
    private String request(URI url, String method, byte[] body, String contentType, boolean attachment,
            String username, String password, boolean authenticated) throws IOException {
        boolean https = "https".equalsIgnoreCase(url.getScheme());
        if (((https && authenticated) || !"http".equalsIgnoreCase(url.getScheme())) && (url.getHost() == null || url.getUserInfo() != null)) throw new IOException("Evidence API URLs must use HTTPS without embedded credentials.");
        HttpURLConnection connection = openConnection(url);
        int timeout = url.getPath() != null && url.getPath().contains("/session") ? gridReadTimeoutMs : readTimeoutMs;
        try {
            connection.setConnectTimeout(connectTimeoutMs); connection.setReadTimeout(timeout); connection.setInstanceFollowRedirects(false); connection.setRequestMethod(method);
            connection.setRequestProperty("Accept", "application/json, text/plain");
            if (authenticated) connection.setRequestProperty("Authorization", authorization(username, password));
            if (attachment) connection.setRequestProperty("X-Atlassian-Token", "no-check");
            if (body != null) { connection.setRequestProperty("Content-Type", contentType); connection.setDoOutput(true); connection.setFixedLengthStreamingMode(body.length); try (OutputStream output = connection.getOutputStream()) { output.write(body); } }
            int status = connection.getResponseCode();
            if (status < 200 || status >= 300) throw new IOException("Evidence API returned HTTP " + status + " from " + url.getHost() + ". Check API credentials, permissions, or SSO requirements.");
            try (InputStream input = connection.getInputStream(); ByteArrayOutputStream output = new ByteArrayOutputStream()) {
                byte[] buffer = new byte[4096]; int length;
                while ((length = input.read(buffer)) != -1) { if (output.size() + length > MAX_RESPONSE_BYTES) throw new IOException("Evidence API response exceeds 32 MB."); output.write(buffer, 0, length); }
                return new String(output.toByteArray(), StandardCharsets.UTF_8);
            }
        } catch (java.net.SocketTimeoutException exception) {
            throw new IOException("Timed out during evidence HTTP " + method + " to " + url.getHost() + ".", exception);
        } finally { connection.disconnect(); }
    }
    private String authorization(String username, String password) throws IOException {
        if (environment != null) {
            String token;
            try { token = environment.getProperty("my.pat.token.confluence", "").trim(); }
            catch (IllegalArgumentException exception) { throw new IOException("my.pat.token.confluence could not be resolved; check its environment variable."); }
            if (!token.isEmpty()) return "Bearer " + token;
        }
        return "Basic " + Base64.getEncoder().encodeToString((username + ":" + password).getBytes(StandardCharsets.UTF_8));
    }
    protected HttpURLConnection openConnection(URI url) throws IOException { return (HttpURLConnection) url.toURL().openConnection(); }
}
