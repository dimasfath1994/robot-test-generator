package com.hsbc.automation.robotgenerator.domain;

import java.net.URI;
import java.net.URISyntaxException;

public final class GenerationRequest {

    private final String eimId;
    private final String projectName;
    private final String baseUrl;
    private final String endpoint;

    public GenerationRequest(String eimId, String projectName, String baseUrl, String endpoint) {
        this.eimId = requireText(eimId, "EIM ID");
        this.projectName = requireText(projectName, "Project name");
        this.baseUrl = normalizeBaseUrl(baseUrl);
        this.endpoint = normalizeEndpoint(endpoint);
    }

    public String getEimId() {
        return eimId;
    }

    public String getProjectName() {
        return projectName;
    }

    public String getBaseUrl() {
        return baseUrl;
    }

    public String getEndpoint() {
        return endpoint;
    }

    public boolean hasEndpoint() {
        return !"/".equals(endpoint);
    }

    private static String requireText(String value, String label) {
        if (value == null || value.trim().isEmpty()) {
            throw new IllegalArgumentException(label + " is required.");
        }
        return value.trim();
    }

    private static String normalizeBaseUrl(String value) {
        String normalized = requireText(value, "URL");
        while (normalized.endsWith("/")) {
            normalized = normalized.substring(0, normalized.length() - 1);
        }
        try {
            URI uri = new URI(normalized);
            if (uri.getScheme() == null || uri.getHost() == null
                    || (!"http".equalsIgnoreCase(uri.getScheme())
                    && !"https".equalsIgnoreCase(uri.getScheme()))) {
                throw new IllegalArgumentException("URL must be a valid HTTP or HTTPS URL.");
            }
        } catch (URISyntaxException exception) {
            throw new IllegalArgumentException("URL must be a valid HTTP or HTTPS URL.", exception);
        }
        return normalized;
    }

    private static String normalizeEndpoint(String value) {
        if (value == null || value.trim().isEmpty()) {
            return "/";
        }
        String normalized = value.trim();
        return normalized.startsWith("/") ? normalized : "/" + normalized;
    }
}