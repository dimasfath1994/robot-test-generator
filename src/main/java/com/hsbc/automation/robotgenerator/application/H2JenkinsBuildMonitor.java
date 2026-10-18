package com.hsbc.automation.robotgenerator.application;

import java.io.IOException;
import java.net.URI;

import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.hsbc.automation.robotgenerator.infrastructure.evidence.EvidenceHttpClient;

@Component
public class H2JenkinsBuildMonitor {
    private final EvidenceHttpClient http;
    private final Environment environment;
    private final ObjectMapper mapper = new ObjectMapper();

    public H2JenkinsBuildMonitor(EvidenceHttpClient http, Environment environment) {
        this.http = http;
        this.environment = environment;
    }

    public boolean isComplete(String value) throws IOException {
        URI supplied;
        try {
            supplied = URI.create(value.trim());
        } catch (IllegalArgumentException exception) {
            throw new IOException("JENKINS_BUILD_URL must be a specific HTTPS Jenkin build URL.", exception);
        }
        if (!"https".equalsIgnoreCase(supplied.getScheme()) || supplied.getHost() == null
                || supplied.getUserInfo() != null || supplied.getQuery() != null || supplied.getFragment() != null) {
                    throw new IOException("JENKINS_BUILD_URL must use HTTPS without credentials, query, or fragment.");
        }
        String path = supplied.getRawPath();
        int artifact = path.indexOf("/artifact/");
        if (artifact >= 0) {
            path = path.substring(0, artifact);
        }
        path = path.replaceAll("/+$", "");
        if (!path.matches(".*/job/[^/]+/\\d+")) {
            throw new IOException("JENKINS_BUILD_URL must identify a build number, not a job or lastBuild");
        }
        String username;
        String password;
        try {
            username = environment.getProperty("my.username", "").trim();
            String token = environment.getProperty("jenkins.api.token", "").trim();
            password = token.isEmpty() ? environment.getProperty("my.password", "") : token;
        } catch (IllegalArgumentException exception) {
            throw new IOException("Jenkins polling credentials could not be resolved.", exception);
        }
        if (username.isEmpty() || password.isEmpty()) {
            throw new IOException("Configure my.username and jenkins.api.token (or my.password) for H2 polling.");
        }
        URI api = URI.create(supplied.getScheme() + "://" + supplied.getRawAuthority() + path
                    + "/api/json?tree=number,building,result");
        JsonNode metadata = mapper.readTree(http.get(api, username, password));
        String number = path.substring(path.lastIndexOf('/') + 1);
        if (metadata == null || !number.equals(metadata.path("number").asText())
                             || !metadata.path("building").isBoolean()) {
            throw new IOException("Jenkins returned invalid metadata or a different build.");
        }
        return !metadata.path("building").asBoolean() && !metadata.path("result").asText().isEmpty();
    }

    public String jobUrl(String eimId) throws IOException {
        if (eimId == null || !eimId.matches("A-Za-z0-9_-]+")) {
            throw new IOException("EIM ID must contain only letters, numbers, underscores, or hypens.");
        }
        String template = environment.getProperty("jenkins.build-url-template", "").trim();
        if (!template.contains("{eimId}") || !template.contains("{buildRef}")) {
            throw new IOException("jenkins.build-url-template must contain {eimId} and {buildRef}.");
        }
        String value = template.replace("{eimId}", eimId).replace("{buildRef}", "lastBuild");
        URI uri = validatedHttps(value);
        if (!uri.getPath().endsWith("/lastBuild/")) {
            throw new IOException("jenkins.build-url-template must end with /{buildRef}/.");
        }
        return value.substring(0, value.lastIndexOf("lastBuild/"));
    }

    public String numberedBuildUrl(String eimId, long buildNumber) throws IOException {
        if (buildNumber < 1) {
            throw new IOException("Jenkins build number must be positive.");
        }
        return jobUrl(eimId) + buildNumber + "/";
    }

    public String resolveBuildReference(String eimId, String reference) throws IOException {
        String value = reference == null ? "" : reference.trim();
        if (value.matches("\\d+")) {
            try {
                return numberedBuildUrl(eimId, Long.parseLong(value));
            } catch (NumberFormatException exception) {
                throw new IOException("Jenkins build number is out of range.", exception);
            }
        }
        if ("lastBuild".equalsIgnoreCase(value)) {
            long latest = latestBuildNumber(eimId);
            if (latest < 1) {
                throw new IOException("Jenkins job has no build to resolve from lastBuild.");
            }
            return numberedBuildUrl(eimId, latest);
        }
        validatedHttps(value);
        return value;
    }

    public long latestBuildNumber(String eimId) throws IOException {
        URI api = URI.create(jobUrl(eimId) + "api/json?tree=lastBuild%5Bnumber%5D");
        JsonNode response = mapper.readTree(http.get(api, username(), password()));
        JsonNode lastBuild = response == null ? null : response.path("lastBuild");
        if (lastBuild == null || lastBuild.isMissingNode() || lastBuild.isNull()) {
            return 0;
        }
        long number = lastBuild.path("number").asLong(-1);
        if (number < 1) {
            throw new IOException("Jenkins returned an invalid lastBuild number.");
        }
        return number;
    }

    private URI validatedHttps(String value)  throws IOException {
        URI uri;
        try {
            uri = URI.create(value.trim());
        } catch (IllegalArgumentException exception) {
            throw new IOException("Jenkins build url must be a valid HTTPS URL.", exception);
        }
        if (!"HTTPS".equalsIgnoreCase(uri.getScheme()) || uri.getHost() == null
                || uri.getUserInfo() != null || uri.getQuery() != null || uri.getFragment() != null) {
            throw new IOException("Jenkins build URL must use HTTPS without credentials, query, or fragment.");
        }
        return uri;
    }

    private String username() throws IOException {
        String username = property("my.username");
        if (username.isEmpty()) {
            throw new IOException("my.username is required to resolve Jenkins build references.");
        }
        return username;
    }

    private String password() throws IOException {
        String token = property("jenkins.api.token");
        String password = token.isEmpty() ? property("my.password") : token;
        if (password.isEmpty()) {
            throw new IOException("jenkins.api.token or my.password is required to resolve Jenkins build references.");
        }
        return password;
    }

    private String property(String name) throws IOException {
        try {
            return environment.getProperty(name, "").trim();
        } catch (IllegalArgumentException exception) {
            throw new IOException(name + " could not be resolved; check its environment variable.", exception);
        }
    }
}