package com.hsbc.automation.robotgenerator.domain;

import java.net.URI;
import java.net.URISyntaxException;

public final class GenerationRequest {

    private final String eimId;
    private final String projectName;
    private final String baseUrl;
    private final String endpoint;
    private final DatabaseCheck databaseCheck;
    private String crNumber = "CHG1234567";
    private String confluenceTitleMode = "DATE";

    public GenerationRequest withChangeRequest(String crNumber, String titleMode) {
        GenerationRequest copy = isDatabaseCheck()
                ? new GenerationRequest(eimId, projectName, databaseCheck)
                : new GenerationRequest(eimId, projectName, baseUrl, endpoint);
        copy.crNumber = crNumber == null || crNumber.trim().isEmpty() ? "CHG1234567" : crNumber.trim();
        copy.confluenceTitleMode = titleMode == null || titleMode.trim().isEmpty()
                ? "DATE" : titleMode.trim().toUpperCase(java.util.Locale.ROOT);
        if (!"DATE".equals(copy.confluenceTitleMode) && !"CHANGE_REQUEST".equals(copy.confluenceTitleMode)) {
            throw new IllegalArgumentException("Confluence title mode must be DATE or CHANGE_REQUEST");
        }
        return copy;
    }

    public String getCrNumber() { return crNumber; }
    public String getConfluenceTitleMode() { return confluenceTitleMode; }

    public GenerationRequest(String eimId, String projectName, String baseUrl, String endpoint) {
        this.eimId = requireText(eimId, "EIM ID");
        this.projectName = requireText(projectName, "Project name");
        this.baseUrl = normalizeBaseUrl(baseUrl);
        this.endpoint = normalizeEndpoint(endpoint);
        this.databaseCheck = null;
    }

    public GenerationRequest(String eimId, String projectName, DatabaseCheck databaseCheck) {
        this.eimId = requireText(eimId, "EIM ID");
        this.projectName = requireText(projectName, "Project name");
        if (databaseCheck == null) {
            throw new IllegalArgumentException("Database check is required.");
        }
        this.databaseCheck = databaseCheck;
        this.baseUrl = null;
        this.endpoint = "/";
    }

    public boolean isDatabaseCheck() { return databaseCheck != null; }
    public DatabaseCheck getDatabaseCheck() { return  databaseCheck; }

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