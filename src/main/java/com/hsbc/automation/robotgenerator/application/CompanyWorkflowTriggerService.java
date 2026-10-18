package com.hsbc.automation.robotgenerator.application;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URI;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.Clock;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.hsbc.automation.payload.CompanyPayloadMapping;

@Component 
public class CompanyWorkflowTriggerService {
    private final boolean enabled;
    private final String apiUrl;
    private final String amtoken;
    private final Environment environment;
    private final ObjectMapper mapper = new ObjectMapper();

    @Autowired
    public CompanyWorkflowTriggerService(
        @Value("${sync.with.company.flow:false}") boolean enabled,
        @Value("${company.flow.api-url:}") String apiUrl,
        @Value("${company.flow.amtoken:}") String amtoken,
        Environment environment) {
            this.enabled = enabled;
            this.apiUrl = apiUrl;
            this.amtoken = amtoken;
            this.environment = environment;
    }

    public void triggerAsync(String eimId, String repositoryUrl, String gitProjectPath) {
        triggerAsync(eimId, repositoryUrl, gitProjectPath, null);
    }

    public void triggerAsync(String eimId, String repositoryUrl, String gitProjectPath, String itidspProjectId) {
        triggerAsync(eimId, repositoryUrl, gitProjectPath, itidspProjectId, null);
    }


    public void triggerAsync(String eimId, String repositoryUrl, String gitProjectPath, String itidspProjectId,
        String crNumber) {
        if (!enabled) {
            return;
        }
        Thread thread = new Thread(() -> {
            try {
                ApiResponse response = post(eimId, repositoryUrl, gitProjectPath, itidspProjectId, crNumber);
                System.out.println("Company workflow API response: HTTP " + response.status);
                if (response.status >= 400) {
                    System.err.println("Company workflow API error response: "
                            + (response.body.isEmpty() ? "<empty>" : response.body));
                }
            } catch (Exception exception) {
                String message = exception.getMessage() == null
                        ? exception.getClass().getSimpleName() : exception.getMessage();
                System.err.println("Company workflow API call failed: " + message);
            }
        }, "company-workflow-trigger");
        thread.start();
    }

    public boolean isEnabled() {
        return enabled;
    }

    ApiResponse post(String eimId, String repositoryUrl, String gitProjectPath) throws IOException {
        return post(eimId, repositoryUrl, gitProjectPath, null);
    }

    ApiResponse post(String eimId, String repositoryUrl, String gitProjectPath, String itidspProjectId) 
        throws IOException {
        return post(eimId, repositoryUrl, gitProjectPath, itidspProjectId, null);
    }

    ApiResponse post(String eimId, String repositoryUrl, String gitProjectPath, String itidspProjectId,
        String crNumber) throws IOException {
        String username = environment.getProperty("my.username", "").trim();
        if (username.isEmpty()) {
            throw new IOException("my.username is required for the company workflow API.");
        }
        URI endpoint = URI.create(apiUrl.trim());
        if (endpoint.getHost() == null || endpoint.getUserInfo() != null
                || !("https".equalsIgnoreCase(endpoint.getScheme())
                    || "http".equalsIgnoreCase(endpoint.getScheme()))) {
            throw new IOException("company.flow.api-url must be an HTTP or HTTPS URL without embedded credentials.");
        }
        if (amtoken.trim().isEmpty()) {
            throw new IOException("company.flow.amtoken is required when company flow is enabled.");
        }

        ObjectNode context = mapper.createObjectNode();
        context.put("eimId", eimId);
        context.put("engagementId", "ACE-CTO-" + eimId + "-001");
        context.put("username", username);
        context.put("repositoryUrl", gitRepositoryUrl(repositoryUrl));
        context.put("testSuitePath", testSuitePath(gitProjectPath));
        context.put("itidspProjectId", itidspProjectId == null ? "" : itidspProjectId.trim());
        context.put("crNumber", crNumber == null || crNumber.trim().isEmpty() ? "CHG1234567" : crNumber.trim());
        JsonNode payload = renderPayload(context);
        byte[] body = mapper.writeValueAsBytes(payload);
        HttpURLConnection connection = (HttpURLConnection) new URL(endpoint.toString()).openConnection();
        try {
            connection.setConnectTimeout(15000);
            connection.setReadTimeout(30000);
            connection.setRequestMethod("POST");
            connection.setRequestProperty("Content-Type", "application/json; charset=UTF-8");
            connection.setRequestProperty("Accept", "application/json");
            connection.setRequestProperty("amtoken", amtoken.trim());
            connection.setRequestProperty("Cookie", "AMToken=" + amtoken.trim() + "; AMToken=" + amtoken.trim()
                    + "; psid=" + username + "; amlbcookie=01");
            connection.setRequestProperty("eimid", eimId);
            connection.setRequestProperty("psid", username);
            connection.setRequestProperty("token_type", "SESSION_TOKEN");
            connection.setDoOutput(true);
            connection.setFixedLengthStreamingMode(body.length);
            try (java.io.OutputStream output = connection.getOutputStream()) {
                output.write(body);
            }
            int status = connection.getResponseCode();
            String responseBody = status >= 400 ? readErrorBody(connection.getErrorStream()) : "";
            responseBody = responseBody.replace(amtoken.trim(), "[REDACTED]")
                    .replace(username, "[REDACTED]");
            return new ApiResponse(status, responseBody);
        } finally {
            connection.disconnect();
        }
    }

    private JsonNode renderPayload(ObjectNode context) throws IOException {
        String configuredPath = environment.getProperty("company.flow.payload-config", "config/company-payload.json").trim();
        Path path = configuredPath.startsWith("file:") ? Paths.get(URI.create(configuredPath))
            : Paths.get(configuredPath.isEmpty() ? "config/company-payload.json" : configuredPath);
        String json;
        if (Files.exists(path)) {
           if (Files.size(path) > 262144) {
                throw new IOException("Company payload mapping exceeds 256 KB.");
           }
           json = new String(Files.readAllBytes(path), StandardCharsets.UTF_8);
        } else {
            try (InputStream input = CompanyWorkflowTriggerService.class.getResourceAsStream("/company-payload-default.json")) {
                if (input == null) {
                    throw new IOException("Bundled company payload mapping is missing.");
                }
                ByteArrayOutputStream output = new ByteArrayOutputStream();
                byte[] buffer = new byte[4096];
                int count;
                while ((count = input.read(buffer)) != -1) {
                    output.write(buffer, 0, count);
                }
                json = new String(output.toByteArray(), StandardCharsets.UTF_8);
            }
        }
        CompanyPayloadMapping mapping = new CompanyPayloadMapping();
        try {
            return mapping.render(mapping.parse(json), context, Clock.systemUTC());
        } catch (IOException | RuntimeException exception) {
            throw new IOException("Company payload mapping is invalid; check Payload Configuration.");
        }
    }

    private String readErrorBody(InputStream input) throws IOException {
        if (input == null) {
            return "";
        }
        try (InputStream response = input; ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            byte[] buffer = new byte[1024];
            int total = 0;
            int read;
            while (total < 4096 && (read = response.read(buffer, 0, Math.min(buffer.length, 4096 - total))) >= 0) {
                output.write(buffer, 0, read);
                total += read;
            }
            String body = new String(output.toByteArray(), java.nio.charset.StandardCharsets.UTF_8);
            return total == 4096 ? body + " [truncated]" : body;
        }
    }

    static final class ApiResponse {
        final int status;
        final String body;

        ApiResponse(int status, String body) {
            this.status = status;
            this.body = body;
        }
    }

    private String gitRepositoryUrl(String repositoryUrl) throws IOException {
        String value = repositoryUrl == null ? "" : repositoryUrl.trim();
        while (value.endsWith("/")) {
            value = value.substring(0, value.length() - 1);
        }
        if (value.isEmpty()) {
            throw new IOException("A Git repository URL is required for the company workflow API.");
        }
        return value.endsWith(".git") ? value : value + ".git";
    }

    private String testSuitePath(String gitProjectPath) {
        String value = gitProjectPath == null ? "" : gitProjectPath.trim().replace('\\', '/');
        while (value.endsWith("/") && !value.isEmpty()) {
            value = value.substring(0, value.length() - 1);
        }
        if (value.isEmpty()) {
            value = ".";
        }
        return value + "/Tests/Regression/TS01_TC01.robot";
    }   
}