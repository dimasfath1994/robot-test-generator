package com.hsbc.automation.robotgenerator.application;

import java.io.IOException;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import com.hsbc.automation.robotgenerator.domain.GenerationRequest;
import com.hsbc.automation.robotgenerator.infrastructure.git.RobotGitSyncService;
import com.hsbc.automation.robotgenerator.infrastructure.git.RobotGitSyncService.SyncMode;

@Service
public class H2GenerationBatchService {

    private static final String TABLE = "ROBOT_GENERATION_REQUEST";

    private final String jdbcUrl;
    private final String username;
    private final String password;
    private final String gitRepositoryBaseUrl;
    private final boolean autoSyncEimGit;
    private final RobotProjectGenerationService generationService;
    private final RobotGitSyncService gitSyncService;
    private final JenkinsEvidencePublicationService evidenceService;

    public H2GenerationBatchService(
            @Value("${sync.h2.jdbc-url:jdbc:h2:file:./data/robot-generator}") String jdbcUrl,
            @Value("${sync.h2.username:sa}") String username,
            @Value("${sync.h2.password:}") String password,
            @Value("${auto.sync.eim.git:false}") boolean autoSyncEimGit,
            @Value("${git.repo.url:}") String gitRepositoryBaseUrl,
            RobotProjectGenerationService generationService,
            RobotGitSyncService gitSyncService,
            JenkinsEvidencePublicationService evidenceService) {
        this.jdbcUrl = jdbcUrl;
        this.username = username;
        this.password = password;
        this.autoSyncEimGit = autoSyncEimGit;
        this.gitRepositoryBaseUrl = gitRepositoryBaseUrl;
        this.generationService = generationService;
        this.gitSyncService = gitSyncService;
        this.evidenceService = evidenceService;
    }

    public BatchResult processActiveRequests() throws SQLException {
        List<GenerationRecord> records = loadActiveRequests();
        if (records.isEmpty()) {
            System.out.println("H2 batch: no active generation requests.");
            return new BatchResult(0, 0);
        }

        int completed = 0;
        int failed = 0;
        for (GenerationRecord record : records) {
            try {
                process(record);
                deactivate(record.id);
                completed++;
                System.out.println("H2 batch completed request " + record.id + " (" + record.eimId + ").");
            } catch (Exception exception) {
                failed++;
                String message = exception.getMessage() == null ? exception.getClass().getSimpleName()
                        : exception.getMessage();
                System.err.println("H2 batch failed request " + record.id + " (" + record.eimId + "): " + message);
            }
        }
        System.out.println("H2 batch finished: " + completed + " completed, " + failed + " failed.");
        return new BatchResult(completed, failed);
    }

    private void process(GenerationRecord record) throws IOException {
        GenerationRequest request = new GenerationRequest(
                record.eimId, record.projectName, record.baseUrl, record.endpoint);
        Path generatedProject = generationService.generate(request);
        String repositoryUrl = repositoryUrl(record);
        String projectPath = record.gitProjectPath == null || record.gitProjectPath.trim().isEmpty()
                ? "." : record.gitProjectPath.trim();
        SyncMode mode = syncMode(record.gitSyncMode);

        gitSyncService.sync(generatedProject, repositoryUrl, candidates -> projectPath, () -> mode);
        String pageUrl = evidenceService.publish(request, record.jenkinsBuildUrl, repositoryUrl);
        System.out.println("Confluence evidence: " + pageUrl);
    }

    private String repositoryUrl(GenerationRecord record) throws IOException {
        if (record.gitRepositoryUrl != null && !record.gitRepositoryUrl.trim().isEmpty()) {
            return record.gitRepositoryUrl.trim();
        }
        String baseUrl = gitRepositoryBaseUrl == null ? "" : gitRepositoryBaseUrl.trim();
        while (baseUrl.endsWith("/")) {
            baseUrl = baseUrl.substring(0, baseUrl.length() - 1);
        }
        if (!autoSyncEimGit || baseUrl.isEmpty()) {
            throw new IOException("git_repository_url is required when automatic EIM Git sync is disabled or unconfigured.");
        }
        if (!record.eimId.matches("[A-Za-z0-9_-]+")) {
            throw new IOException("EIM ID must contain only letters, numbers, underscores, or hyphens to derive its Git URL.");
        }
        return baseUrl + "/" + record.eimId;
    }

    private SyncMode syncMode(String value) throws IOException {
        String mode = value == null || value.trim().isEmpty() ? "OVERWRITE" : value.trim();
        try {
            return SyncMode.valueOf(mode.toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException exception) {
            throw new IOException("git_sync_mode must be OVERWRITE or MERGE.", exception);
        }
    }

    private List<GenerationRecord> loadActiveRequests() throws SQLException {
        List<GenerationRecord> records = new ArrayList<>();
        try (Connection connection = connect()) {
            createTable(connection);
            String sql = "SELECT id, eim_id, project_name, base_url, endpoint, git_repository_url, "
                    + "git_project_path, git_sync_mode, jenkins_build_url "
                    + "FROM " + TABLE + " WHERE is_active = TRUE ORDER BY id";
            try (Statement statement = connection.createStatement(); ResultSet results = statement.executeQuery(sql)) {
                while (results.next()) {
                    records.add(new GenerationRecord(
                            results.getLong("id"),
                            results.getString("eim_id"),
                            results.getString("project_name"),
                            results.getString("base_url"),
                            results.getString("endpoint"),
                            results.getString("git_repository_url"),
                            results.getString("git_project_path"),
                            results.getString("git_sync_mode"),
                            results.getString("jenkins_build_url")));
                }
            }
        }
        return records;
    }

    private void deactivate(long id) throws SQLException {
        try (Connection connection = connect();
                PreparedStatement statement = connection.prepareStatement(
                        "UPDATE " + TABLE + " SET is_active = FALSE WHERE id = ? AND is_active = TRUE")) {
            statement.setLong(1, id);
            if (statement.executeUpdate() != 1) {
                throw new SQLException("Request was no longer active after processing.");
            }
        }
    }

    private Connection connect() throws SQLException {
        return DriverManager.getConnection(jdbcUrl, username, password);
    }

    private void createTable(Connection connection) throws SQLException {
        String sql = "CREATE TABLE IF NOT EXISTS " + TABLE + " ("
                + "id BIGINT GENERATED BY DEFAULT AS IDENTITY PRIMARY KEY, "
                + "eim_id VARCHAR(255) NOT NULL, "
                + "project_name VARCHAR(255) NOT NULL, "
                + "base_url VARCHAR(2048) NOT NULL, "
                + "endpoint VARCHAR(2048) DEFAULT '/', "
                + "is_active BOOLEAN DEFAULT TRUE NOT NULL, "
                + "git_repository_url VARCHAR(2048), "
                + "git_project_path VARCHAR(1024) DEFAULT '.', "
                + "git_sync_mode VARCHAR(20) DEFAULT 'OVERWRITE', "
                + "jenkins_build_url VARCHAR(2048) NOT NULL)";
        try (Statement statement = connection.createStatement()) {
            statement.execute(sql);
        }
    }

    private static final class GenerationRecord {
        private final long id;
        private final String eimId;
        private final String projectName;
        private final String baseUrl;
        private final String endpoint;
        private final String gitRepositoryUrl;
        private final String gitProjectPath;
        private final String gitSyncMode;
        private final String jenkinsBuildUrl;

        private GenerationRecord(long id, String eimId, String projectName, String baseUrl, String endpoint,
                String gitRepositoryUrl, String gitProjectPath, String gitSyncMode, String jenkinsBuildUrl) {
            this.id = id;
            this.eimId = eimId;
            this.projectName = projectName;
            this.baseUrl = baseUrl;
            this.endpoint = endpoint;
            this.gitRepositoryUrl = gitRepositoryUrl;
            this.gitProjectPath = gitProjectPath;
            this.gitSyncMode = gitSyncMode;
            this.jenkinsBuildUrl = jenkinsBuildUrl;
        }
    }

    public static final class BatchResult {
        private final int completed;
        private final int failed;

        public BatchResult(int completed, int failed) {
            this.completed = completed;
            this.failed = failed;
        }

        public int getCompleted() {
            return completed;
        }

        public int getFailed() {
            return failed;
        }
    }
}