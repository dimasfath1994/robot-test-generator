package com.hsbc.automation.robotgenerator.application;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.time.Clock;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.core.env.StandardEnvironment;

import com.hsbc.automation.robotgenerator.domain.GenerationRequest;
import com.hsbc.automation.robotgenerator.infrastructure.evidence.EvidenceHttpClient;
import com.hsbc.automation.robotgenerator.infrastructure.evidence.EvidenceScreenshotCapture;
import com.hsbc.automation.robotgenerator.infrastructure.filesystem.RobotProjectWriter;
import com.hsbc.automation.robotgenerator.infrastructure.git.RobotGitSyncService;
import com.hsbc.automation.robotgenerator.infrastructure.git.RobotGitSyncService.SyncMode;
import com.hsbc.automation.robotgenerator.infrastructure.template.RobotTemplateRenderer;

class H2GenerationBatchServiceTest {
    @TempDir
    Path temporaryDirectory;

    private String jdbcUrl;
    private RecordingGenerationService generation;
    private RecordingGitSyncService git;
    private RecordingEvidenceService evidence;
    private H2GenerationBatchService batch;

    @BeforeEach
    void setUp() throws Exception {
        jdbcUrl = "jdbc:h2:mem:batch" + System.nanoTime() + ";DB_CLOSE_DELAY=-1";
        generation = new RecordingGenerationService(temporaryDirectory);
        git = new RecordingGitSyncService();
        evidence = new RecordingEvidenceService();
        batch = new H2GenerationBatchService(jdbcUrl, "sa", "", true,
                "https://git.example/team/projects", generation, git, evidence);
        batch.processActiveRequests();
    }

    @Test
    void processesOnlyActiveRowsThroughGitAndEvidenceThenDeactivatesThem() throws Exception {
        insert("EIM-42", "Portal", true, null, ".", "MERGE", "https://jenkins.example/job/portal/42/");
        insert("EIM-43", "Inactive", false, null, ".", "OVERWRITE", "https://jenkins.example/job/portal/43/");

        H2GenerationBatchService.BatchResult result = batch.processActiveRequests();

        assertEquals(1, result.getCompleted());
        assertEquals(0, result.getFailed());
        assertEquals(Collections.singletonList("EIM-42"), generation.eimIds);
        assertEquals("https://git.example/team/projects/EIM-42", git.repositoryUrl);
        assertEquals(".", git.projectPath);
        assertEquals(SyncMode.MERGE, git.syncMode);
        assertEquals("https://jenkins.example/job/portal/42/", evidence.buildUrl);
        assertEquals("https://git.example/team/projects/EIM-42", evidence.repositoryUrl);
        assertFalse(isActive("EIM-42"));
        assertFalse(isActive("EIM-43"));
    }

    @Test
    void leavesFailedRowsActiveAndContinuesProcessingOtherActiveRows() throws Exception {
        insert("EIM-FAIL", "Broken", true, null, ".", "OVERWRITE", "https://jenkins.example/job/app/1/");
        insert("EIM-OK", "Working", true, null, ".", "OVERWRITE", "https://jenkins.example/job/app/2/");
        git.failForProject = "EIM-FAIL";

        H2GenerationBatchService.BatchResult result = batch.processActiveRequests();

        assertEquals(1, result.getCompleted());
        assertEquals(1, result.getFailed());
        assertEquals(2, generation.eimIds.size());
        assertTrue(isActive("EIM-FAIL"));
        assertFalse(isActive("EIM-OK"));
        assertEquals(1, evidence.calls);
    }

    @Test
    void createsPersistentDatabaseFileAtConfiguredPath() throws Exception {
        Path databaseFile = temporaryDirectory.resolve("local-db").resolve("robot-generator");
        H2GenerationBatchService fileBatch = new H2GenerationBatchService(
                "jdbc:h2:file:" + databaseFile.toAbsolutePath(), "sa", "", false, "",
                generation, git, evidence);

        fileBatch.processActiveRequests();

        assertTrue(Files.exists(Paths.get(databaseFile.toString() + ".mv.db")));
    }

    private void insert(String eimId, String projectName, boolean active, String repositoryUrl,
            String projectPath, String syncMode, String buildUrl) throws Exception {
        String sql = "INSERT INTO ROBOT_GENERATION_REQUEST "
                + "(eim_id, project_name, base_url, endpoint, is_active, git_repository_url, "
                + "git_project_path, git_sync_mode, jenkins_build_url) VALUES (?, ?, ?, '/', ?, ?, ?, ?, ?)";
        try (Connection connection = DriverManager.getConnection(jdbcUrl, "sa", "");
                PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, eimId);
            statement.setString(2, projectName);
            statement.setString(3, "https://application.example");
            statement.setBoolean(4, active);
            statement.setString(5, repositoryUrl);
            statement.setString(6, projectPath);
            statement.setString(7, syncMode);
            statement.setString(8, buildUrl);
            statement.executeUpdate();
        }
    }

    private boolean isActive(String eimId) throws Exception {
        try (Connection connection = DriverManager.getConnection(jdbcUrl, "sa", "");
                PreparedStatement statement = connection.prepareStatement(
                        "SELECT is_active FROM ROBOT_GENERATION_REQUEST WHERE eim_id = ?")) {
            statement.setString(1, eimId);
            try (ResultSet result = statement.executeQuery()) {
                result.next();
                return result.getBoolean(1);
            }
        }
    }

    private static class RecordingGenerationService extends RobotProjectGenerationService {
        private final Path output;
        private final List<String> eimIds = new ArrayList<>();

        private RecordingGenerationService(Path output) {
            super(new RobotTemplateRenderer(), new RobotProjectWriter(output.toString()), Clock.systemUTC());
            this.output = output;
        }

        @Override
        public Path generate(GenerationRequest request) {
            eimIds.add(request.getEimId());
            return output.resolve(request.getEimId());
        }
    }

    private static class RecordingGitSyncService extends RobotGitSyncService {
        private String repositoryUrl;
        private String projectPath;
        private SyncMode syncMode;
        private String failForProject;

        @Override
        public boolean sync(Path generatedProject, String repositoryUrl,
                java.util.function.Function<List<String>, String> targetDirectorySelector,
                java.util.function.Supplier<SyncMode> modeSelector) throws IOException {
            this.repositoryUrl = repositoryUrl;
            this.projectPath = targetDirectorySelector.apply(Collections.<String>emptyList());
            this.syncMode = modeSelector.get();
            if (generatedProject.getFileName().toString().equals(failForProject)) {
                throw new IOException("simulated Git failure");
            }
            return true;
        }
    }

    private static class RecordingEvidenceService extends JenkinsEvidencePublicationService {
        private int calls;
        private String buildUrl;
        private String repositoryUrl;

        private RecordingEvidenceService() {
            this(new StandardEnvironment());
        }

        private RecordingEvidenceService(StandardEnvironment environment) {
            super(new EvidenceHttpClient(1000, 1000, 1000),
                    new EvidenceScreenshotCapture(new EvidenceHttpClient(1000, 1000, 1000), environment),
                    environment, Clock.systemUTC());
        }

        @Override
        public String publish(GenerationRequest request, String buildUrl, String repositoryUrl) {
            calls++;
            this.buildUrl = buildUrl;
            this.repositoryUrl = repositoryUrl;
            return "https://confluence.example/pages/1";
        }
    }
}