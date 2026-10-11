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
import java.time.Clock;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import com.hsbc.automation.robotgenerator.domain.GenerationRequest;
import com.hsbc.automation.robotgenerator.domain.DatabaseCheck;
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
    private H2JenkinsBuildMonitor buildMonitor;
    private long pollIntervalMs = 30000;
    private long timeoutMs = 7200000;
    Clock clock = Clock.systemUTC();
    
    @Autowired 
    public void configureWorker(H2JenkinsBuildMonitor buildMonitor,
                              @Value("${sync.h2.poll-interval-ms:30000}") long pollIntervalMs,
                              @Value("${sync.h2.jenkins-timeout-ms:7200000}") long timeoutMs) {
                                if (pollIntervalMs < 1 || timeoutMs < 1) {
                                    throw new IllegalArgumentException("H2 polling interval and Jenkins timeout must be positive");
                                }
                                this.buildMonitor = buildMonitor;
                                this.pollIntervalMs = pollIntervalMs;
                                this.timeoutMs = timeoutMs;
                              }

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

    public synchronized BatchResult processActiveRequests() throws SQLException {
        List<GenerationRecord> records = loadActiveRequests();
        if (records.isEmpty()) {
            return new BatchResult(0, 0);
        }

        int completed = 0;
        int failed = 0;
        for (GenerationRecord record : records) {
            try {
                if (!process(record)) {
                    continue;
                }
                deactivate(record.id);
                completed++;
                System.out.println("H2 batch completed request " + record.id + " (" + record.eimId + ").");
            } catch (Exception exception) {
                failed++;
                String message = exception.getMessage() == null ? exception.getClass().getSimpleName()
                        : exception.getMessage();
                try (Connection connection = connect(); PreparedStatement statement = connection.prepareStatement(
                    "UPDATE" + TABLE + " SET last_error = ?, next_check_at = ? WHERE id = ?")) {
                        statement.setString(1, message.substring(0, Math.min(message.length(), 4000)));
                        statement.setLong(2, clock.millis() + pollIntervalMs);
                        statement.setLong(3, record.id);
                        statement.executeUpdate();
                    }
                System.err.println("H2 batch failed request " + record.id + " (" + record.eimId + "): " + message);
            }
        }
        System.out.println("H2 batch finished: " + completed + " completed, " + failed + " failed.");
        return new BatchResult(completed, failed);
    }

    private boolean process(GenerationRecord record) throws IOException, SQLException {
        GenerationRequest request = record.isDb
        ? new GenerationRequest(record.eimId, record.projectName,
            new DatabaseCheck(record.dbModule, record.dbName, record.dbHost, record.dbPort,
                record.dbQuery, record.expectedRowCount, record.dbTestName, record.dbDescription,
                record.dbCredentialMode, record.dbUsername, record.dbPassword))
            : new GenerationRequest(record.eimId, record.projectName, record.baseUrl, record.endpoint);
        String repositoryUrl = record.resolvedRepositoryUrl;
        if ("PENDING".equals(record.processStatus)) {
            repositoryUrl = repositoryUrl(record);
            String projectPath = record.gitProjectPath == null || record.gitProjectPath.trim().isEmpty()
                    ? "." : record.gitProjectPath.trim();
            SyncMode mode = syncMode(record.gitSyncMode);
            Path generatedProject = generationService.generate(request);
            gitSyncService.sync(generatedProject, repositoryUrl, candidates -> projectPath, () -> mode);
            try (Connection connection = connect(); PreparedStatement statement = connection.prepareStatement(
                    "UPDATE" + TABLE + " SET process_status = 'WAITING_JENKINS', resolved_git_url = ? WHERE id = ?")) {
                        statement.setString(1, repositoryUrl);
                        statement.setLong(2, record.id);
                        statement.executeUpdate();
            }
            System.out.println("H2 request " + record.id + " waiting for Jenkins; set JENKINS_BUILD_URL after running the company web workflow.");

        }
        String buildUrl = record.trackedBuildUrl == null ? record.jenkinsBuildUrl : record.trackedBuildUrl;
        if (buildUrl == null || buildUrl.trim().isEmpty()) {
            return false;
        }
        if (!"PUBLISHING".equals(record.processStatus)) {
            long started = record.waitStartedAt == 0 ? clock.millis() : record.waitStartedAt;
            if (clock.millis() - started >= timeoutMs) {
                updateWaiting(record.id, "TIMED_OUT", started, buildUrl);
                return false;
            }
            if (buildMonitor == null) {
                throw new IOException("H2 Jenkins build monitor is not configured");
            }
            updateWaiting(record.id, "RUNNING", started, buildUrl);
            if (!buildMonitor.isComplete(buildUrl)) {
                return false;
            }
            updateWaiting(record.id, "PUBLISHING", started, buildUrl);
        }
        String pageUrl = evidenceService.publish(request, buildUrl, repositoryUrl);
        System.out.println("Confluence evidence: " + pageUrl);
        return true;
    }

    private void updateWaiting(long id, String status, long started, String buildUrl) throws SQLException {
        try (Connection connection = connect(); PreparedStatement statement = connection.prepareStatement(
                    "UPDATE" + TABLE + " SET process_status = ?, wait_started_at = ?, tracked_build_url = ?, "
                    + "next_check_at = ?, last_error = ? WHERE id = ?")) {
                        statement.setString(1, status);
                        statement.setLong(2, started);
                        statement.setString(3, buildUrl);
                        statement.setLong(4, clock.millis() + pollIntervalMs);
                        statement.setString(5, "TIMED_OUT".equals(status) ? "Jenkins wait exceeded configured timeout." : null);
                        statement.setLong(6, id);
                        statement.executeUpdate();
                    }
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
                    + "git_project_path, git_sync_mode, jenkins_build_url, process_status, resolved_git_url, "
                    + "wait_started_at, tracked_build_url, is_db, db_module_name, db_name, db_host, db_port, "
                    + "db_query, expected_row_count, db_test_case_name, db_test_case_description, "
                    + "db_credential_mode, db_username, db_password "
                    + "FROM " + TABLE + " WHERE is_active = TRUE AND process_status IN ('PENDING', 'WAITING JENKINS', 'RUNNING', 'PUBLISHING') "
                    + "AND next_check_at <= " + clock.millis() + " ORDER BY id";
            try (Statement statement = connection.createStatement(); ResultSet results = statement.executeQuery(sql)) {
                while (results.next()) {
                    records.add(new GenerationRecord(results));
                }
            }
        }
        return records;
    }

    private void deactivate(long id) throws SQLException {
        try (Connection connection = connect();
                PreparedStatement statement = connection.prepareStatement(
                        "UPDATE " + TABLE + " SET is_active = FALSE, process_status = 'COMPLETED', last_error = NULL WHERE id = ? AND is_active = TRUE")) {
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
                + "jenkins_build_url VARCHAR(2048))";
        try (Statement statement = connection.createStatement()) {
            statement.execute(sql);
            statement.execute("ALTER TABLE " + TABLE + " ALTER COLUMN jenkins_build_url DROP NOT NULL");
            statement.execute("ALTER TABLE " + TABLE + " ALTER COLUMN IF NOT EXISTS process_status VARCHAR(32) DEFAULT 'PENDING' NOT NULL");
            statement.execute("ALTER TABLE " + TABLE + " ALTER COLUMN IF NOT EXISTS resolved_git_url VARCHAR(2048)");
            statement.execute("ALTER TABLE " + TABLE + " ALTER COLUMN IF NOT EXISTS wait_started_at BIGINT DEFAULT 0 NOT NULL");
            statement.execute("ALTER TABLE " + TABLE + " ALTER COLUMN IF NOT EXISTS tracked_build_url VARCHAR(2048)");
            statement.execute("ALTER TABLE " + TABLE + " ALTER COLUMN IF NOT EXISTS next_check_at BIGINT DEFAULT 0 NOT NULL");
            statement.execute("ALTER TABLE " + TABLE + " ALTER COLUMN IF NOT EXISTS last_error VARCHAR(4000)");
            statement.execute("ALTER TABLE " + TABLE + " ALTER COLUMN base_url DROP NOT NULL");
            statement.execute("ALTER TABLE " + TABLE + " ALTER COLUMN IF NOT EXISTS is_db BOOLEAN DEFAULT FALSE NOT NULL");
            statement.execute("ALTER TABLE " + TABLE + " ALTER COLUMN IF NOT EXISTS db_module_name VARCHAR(100) DEFAULT 'pymssql'");
            statement.execute("ALTER TABLE " + TABLE + " ALTER COLUMN IF NOT EXISTS db_name VARCHAR(255)");
            statement.execute("ALTER TABLE " + TABLE + " ALTER COLUMN IF NOT EXISTS db_host VARCHAR(255)");
            statement.execute("ALTER TABLE " + TABLE + " ALTER COLUMN IF NOT EXISTS db_port INTEGER");
            statement.execute("ALTER TABLE " + TABLE + " ALTER COLUMN IF NOT EXISTS db_query CLOB");
            statement.execute("ALTER TABLE " + TABLE + " ALTER COLUMN IF NOT EXISTS expected_row_count INTEGER DEFAULT 1 NOT NULL");
            statement.execute("ALTER TABLE " + TABLE + " ALTER COLUMN IF NOT EXISTS db_test_case_name VARCHAR(255) DEFAULT 'Verify Database Query'");
            statement.execute("ALTER TABLE " + TABLE + " ALTER COLUMN IF NOT EXISTS db_test_case_description VARCHAR(1024) DEFAULT 'Verify database query row count'");
            statement.execute("ALTER TABLE " + TABLE + " ALTER COLUMN IF NOT EXISTS db_credential_mode VARCHAR(16) DEFAULT 'ENV' NOT NULL");
            statement.execute("ALTER TABLE " + TABLE + " ALTER COLUMN IF NOT EXISTS db_username VARCHAR(1024)");
            statement.execute("ALTER TABLE " + TABLE + " ALTER COLUMN IF NOT EXISTS db_password VARCHAR(2048)");
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
        private final String processStatus;
        private final String resolvedRepositoryUrl;
        private final long waitStartedAt;
        private final String trackedBuildUrl;
        private final boolean isDb;
        private final String dbModule;
        private final String dbName;
        private final String dbHost;
        private final int dbPort;
        private final String dbQuery;
        private final int expectedRowCount;
        private final String dbTestName;
        private final String dbDescription;
        private final String dbCredentialMode;
        private final String dbUsername;
        private final String dbPassword;

        private GenerationRecord(ResultSet result) throws SQLException {
            this.id = result.getLong("id");
            this.eimId = result.getString("eim_id");
            this.projectName = result.getString("project_name");
            this.baseUrl = result.getString("base_url");
            this.endpoint = result.getString("endpoint");
            this.gitRepositoryUrl = result.getString("git_repository_url");
            this.gitProjectPath = result.getString("git_project_path");
            this.gitSyncMode = result.getString("git_sync_mode");
            this.jenkinsBuildUrl = result.getString("jenkins_build_url");
            this.processStatus = result.getString("process_status");
            this.resolvedRepositoryUrl = result.getString("resolved_git_url");
            this.waitStartedAt = result.getLong("wait_started_at");
            this.trackedBuildUrl = result.getString("tracked_build_url");
            this.isDb = result.getBoolean("is_db");
            this.dbModule = result.getString("db_module_name");
            this.dbName = result.getString("db_name");
            this.dbHost = result.getString("db_host");
            this.dbPort = result.getInt("db_port");
            this.dbQuery = result.getString("db_query");
            this.expectedRowCount = result.getInt("expected_row_count");
            this.dbTestName = result.getString("db_test_case_name");
            this.dbDescription = result.getString("db_test_case_description");
            this.dbCredentialMode = result.getString("db_credential_mode");
            this.dbUsername = result.getString("db_username");
            this.dbPassword = result.getString("db_password");
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