package com.hsbc.automation.robotgenerator.domain;

public final class DatabaseCheck {
    private final String module;
    private final String name;
    private final String host;
    private final int port;
    private final String query;
    private final int expectedRowCount;
    private final String testName;
    private final String description;
    private final String credentialMode;
    private final String username;
    private final String password;

    public DatabaseCheck(String module, String name, String host, int port, String query,
            int expectedRowCount, String testName, String description) {
        this(module, name, host, port, query, expectedRowCount, testName, description, "ENV", null, null);
    }

    public DatabaseCheck(String module, String name, String host, int port, String query,
            int expectedRowCount, String testName, String description,
            String credentialMode, String username, String password) {
        this.module = text(module, "DB module");
        if (!"pymssql".equals(this.module)) {
            throw new IllegalArgumentException("DB module must be pymssql for this database template.");
        }
        this.name = text(name, "DB name");
        this.host = text(host, "DB host");
        if (port < 1 || port > 65535 || expectedRowCount < 0) {
            throw new IllegalArgumentException("DB port must be 1-65535 and expected row count must be non-negative");
        }
        this.port = port;
        this.query = text(query, "DB query");
        this.expectedRowCount = expectedRowCount;
        this.testName = text(testName, "Test case name");
        this.description = text(description, "Test case description");
        this.credentialMode = credentialMode == null || credentialMode.trim().isEmpty()
                ? "ENV" : credentialMode.trim().toUpperCase(java.util.Locale.ROOT);
        if ("ENV".equals(this.credentialMode)) {
            this.username = environmentName(username, "DB_USERNAME");
            this.password = environmentName(password, "DB_PASSWORD");
        } else {
            this.username = literal(username, "DB_USERNAME");
            this.password = literal(password, "DB_PASSWORD");
        }
    }

    private static String environmentName(String value, String defaultName) {
        String name = value == null || value.trim().isEmpty() ? defaultName : value.trim();
        if (!name.matches("[A-Za-z_][A-Za-z0-9_]*")) {
            throw new IllegalArgumentException("DB environment reference must be a variable name.");
        }
        return name;
    }

    private static String literal(String value, String label) {
        if (value == null || value.trim().isEmpty() || value.indexOf('\n') >= 0
                || value.indexOf('\r') >= 0 || value.indexOf('\t') >= 0) {
            throw new IllegalArgumentException(label + " must be non-empty single-line text.");
        }
        return value;
    }

    private static String text(String value, String label) {
        if (value == null || value.trim().isEmpty() || value.indexOf('\n') >= 0
                || value.indexOf('\r') >= 0 || value.indexOf('\t') >= 0) {
            throw new IllegalArgumentException(label + " must be non-empty single-line text.");
        }
        return value.trim();
    }
    
    public String getModule() { return module; }
    public String getName() { return name; }
    public String getHost() { return host; }
    public int getPort() { return port; }
    public String getQuery() { return query; }
    public int getExpectedRowCount() { return expectedRowCount; }
    public String getTestName() { return testName; }
    public String getDescription() { return description; }
    public boolean usesEnvironmentCredentials() { return "ENV".equals(credentialMode); }
    public String getUsername() { return username; }
    public String getPassword() { return password; }
}
