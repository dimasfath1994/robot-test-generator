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
            throw new IOException("Configure my.username and jenkins.api.token (or my.password) for H2 polling");
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
}
