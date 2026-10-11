package com.hsbc.automation.robotgenerator.infrastructure.console;

import java.io.IOException;
import java.nio.file.Path;
import java.util.List;
import java.util.Scanner;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.CommandLineRunner;
import org.springframework.stereotype.Component;

import com.hsbc.automation.robotgenerator.application.RobotProjectGenerationService;
import com.hsbc.automation.robotgenerator.application.JenkinsEvidencePublicationService;
import com.hsbc.automation.robotgenerator.application.H2GenerationBatchService;
import com.hsbc.automation.robotgenerator.domain.GenerationRequest;
import com.hsbc.automation.robotgenerator.domain.DatabaseCheck;
import com.hsbc.automation.robotgenerator.infrastructure.git.RobotGitSyncService;
import com.hsbc.automation.robotgenerator.infrastructure.git.RobotGitSyncService.SyncMode;

@Component
public class ConsoleGenerationRunner implements CommandLineRunner {

    private final RobotProjectGenerationService generationService;
    private final RobotGitSyncService gitSyncService;
    private final boolean autoSyncEimGit;
    private final String gitRepoUrl;
    private final JenkinsEvidencePublicationService evidenceService;
    private final boolean syncH2Database;
    private final H2GenerationBatchService h2BatchService;

    public ConsoleGenerationRunner(
            RobotProjectGenerationService generationService,
            RobotGitSyncService gitSyncService) {
        this(generationService, gitSyncService, false, "");
    }

    public ConsoleGenerationRunner(
            RobotProjectGenerationService generationService,
            RobotGitSyncService gitSyncService,
            boolean autoSyncEimGit,
            String gitRepoUrl) {
        this(generationService, gitSyncService, autoSyncEimGit, gitRepoUrl, null, false, null);
    }

    public ConsoleGenerationRunner(
            RobotProjectGenerationService generationService,
            RobotGitSyncService gitSyncService,
            boolean autoSyncEimGit,
            String gitRepoUrl,
            JenkinsEvidencePublicationService evidenceService) {
        this(generationService, gitSyncService, autoSyncEimGit, gitRepoUrl, evidenceService, false, null);
    }

    @Autowired
    public ConsoleGenerationRunner(
            RobotProjectGenerationService generationService,
            RobotGitSyncService gitSyncService,
            @Value("${auto.sync.eim.git:false}") boolean autoSyncEimGit,
            @Value("${git.repo.url:}") String gitRepoUrl,
            JenkinsEvidencePublicationService evidenceService,
            @Value("${sync.h2.db:false}") boolean syncH2Database,
            H2GenerationBatchService h2BatchService) {
        this.generationService = generationService;
        this.gitSyncService = gitSyncService;
        this.autoSyncEimGit = autoSyncEimGit;
        this.gitRepoUrl = gitRepoUrl;
        this.evidenceService = evidenceService;
        this.syncH2Database = syncH2Database;
        this.h2BatchService = h2BatchService;
    }

    @Override
    public void run(String... args) throws Exception {
        if (syncH2Database) {
            h2BatchService.processActiveRequests();
            return;
        }

        Scanner scanner = new Scanner(System.in);
        System.out.println("Robot Framework Test Generator");
        System.out.println("----------------------------------------");

        String eimId = prompt(scanner, "EIM ID");
        String projectName = prompt(scanner, "Project name");

        GenerationRequest request;
        try {
            if (selectDatabaseCheck(scanner)) {
                String module = prompt(scanner, "DB module (Enter for pymssql)").trim();
                DatabaseCheck database = new DatabaseCheck(module.isEmpty() ? "pymssql" : module,
                    prompt(scanner, "DB name"), prompt(scanner, "DB host"),
                    Integer.parseInt(prompt(scanner, "DB port")), prompt(scanner, "SQL query"),
                    Integer.parseInt(prompt(scanner, "Expected row count")),
                    prompt(scanner, "Test case name"), prompt(scanner, "Test case description"));
                String credentialMode = prompt(scanner, "DB credentials: ENV or LITERAL (Enter for ENV)").trim();
                if (!credentialMode.isEmpty()) {
                    String dbUsername;
                    String dbPassword;
                    if ("LITERAL".equalsIgnoreCase(credentialMode)) {
                        System.out.println("Warning: literal DB credentials will be written to generated scripts and pushed to Git.");
                        dbUsername = prompt(scanner, "DB username");
                        dbPassword = readDatabasePassword();
                    } else if ("ENV".equalsIgnoreCase(credentialMode)) {
                        dbUsername = prompt(scanner, "Username environment name (Enter for DB_USERNAME)");
                        dbPassword = prompt(scanner, "Password environment name (Enter for DB_PASSWORD)");
                    } else {
                        throw new IllegalArgumentException("DB credential mode must be ENV or LITERAL.");
                    }
                    database = new DatabaseCheck(database.getModule(), database.getName(), database.getHost(),
                                    database.getPort(), database.getQuery(), database.getExpectedRowCount(),
                                    database.getTestName(), database.getDescription(), credentialMode, dbUsername, dbPassword);
                }    
                request = new GenerationRequest(eimId, projectName, database);
            } else {
                 String baseUrl = prompt(scanner, "URL");
                 String endpoint = prompt(scanner, "Endpoint (optional, press Enter for root)");
                 request = new GenerationRequest(eimId, projectName, baseUrl, endpoint);
            } 
        } catch (IllegalArgumentException exception) {
            System.out.println("Invalid input: " + exception.getMessage());
            return;
        }

        Path generatedProject = generationService.generate(request);
        System.out.println("Generated successfully: " + generatedProject);
        String repositoryUrl = null;
        if (confirmGitSync(scanner)) {
            try {
                repositoryUrl = autoSyncEimGit
                        ? eimRepositoryUrl(request.getEimId())
                        : prompt(scanner, "GitHub repository URL");
                boolean changed = gitSyncService.sync(generatedProject, repositoryUrl,
                        candidates -> selectRepositoryProjectPath(scanner, candidates), () -> selectSyncMode(scanner));
                System.out.println(changed
                        ? "Git sync completed. Temporary clone deleted."
                        : "No changes to sync. Temporary clone deleted.");
            } catch (IOException | IllegalArgumentException exception) {
                System.out.println("Git sync failed: " + exception.getMessage());
            }
        }
        if (confirmEvidence(scanner)) {
            try {
                String buildUrl = prompt(scanner, "Completed Jenkins build URL");
                if (repositoryUrl == null) {
                    repositoryUrl = autoSyncEimGit
                            ? eimRepositoryUrl(request.getEimId())
                            : prompt(scanner, "GitHub repository URL for evidence");
                }
                if (evidenceService == null) {
                    throw new IllegalArgumentException("Evidence publication service is not configured.");
                }
                String pageUrl = evidenceService.publish(request, buildUrl, repositoryUrl);
                System.out.println("Evidence uploaded successfully: " + pageUrl);
            } catch (IOException | IllegalArgumentException exception) {
                System.out.println("Evidence upload failed: " + exception.getMessage());
            }
        }
    }

    private boolean confirmEvidence(Scanner scanner) {
        while (true) {
            System.out.print("generate evidence via jenkins and upload to confluence? y/n: ");
            if (!scanner.hasNextLine()) {
                return false;
            }
            String answer = scanner.nextLine().trim();
            if ("y".equalsIgnoreCase(answer)) {
                return true;
            }
            if ("n".equalsIgnoreCase(answer)) {
                return false;
            }
            System.out.println("Please enter y or n.");
        }
    }

    private String eimRepositoryUrl(String eimId) {
        String baseUrl = gitRepoUrl != null ? gitRepoUrl.trim() : "";
        while (baseUrl.endsWith("/")) {
            baseUrl = baseUrl.substring(0, baseUrl.length() - 1);
        }
        if (baseUrl.isEmpty()) {
            throw new IllegalArgumentException("git.repo.url is required when auto.sync.eim.git=true.");
        }
        if (eimId.matches("[^A-Za-z0-9_-]+") || "..".equals(eimId) || ".".equals(eimId)) {
            throw new IllegalArgumentException("EIM ID must be a single repository name for automatic Git sync.");
        }
        return baseUrl + "/" + eimId;
    }

    private boolean confirmGitSync(Scanner scanner) {
        while (true) {
            System.out.print("sync to git? y/n: ");
            if (!scanner.hasNextLine()) {
                return false;
            }
            String answer = scanner.nextLine().trim();
            if ("y".equalsIgnoreCase(answer)) {
                return true;
            }
            if ("n".equalsIgnoreCase(answer)) {
                return false;
            }
            System.out.println("Please enter y or n.");
        }
    }

    private String selectRepositoryProjectPath(Scanner scanner, List<String> candidates) {
        if (candidates.size() == 1) {
            String detected = candidates.get(0);
            System.out.println("Detected repository project path: " + detected);
            return detected;
        }
        if (candidates.size() > 1) {
            System.out.println("Multiple Robot projects detected:");
            for (int index = 0; index < candidates.size(); index++) {
                System.out.println((index + 1) + ". " + candidates.get(index));
            }
            while (true) {
                System.out.print("Select project (1-" + candidates.size() + ", 0=manual path): ");
                if (!scanner.hasNextLine()) {
                    throw new IllegalArgumentException("Git sync cancelled: no project selected.");
                }
                String selection = scanner.nextLine().trim();
                if ("0".equals(selection)) {
                    break;
                }
                for (int index = 0; index < candidates.size(); index++) {
                    if (String.valueOf(index + 1).equals(selection)) {
                        return candidates.get(index);
                    }
                }
                System.out.println("Please select a listed project number, or 0 for a manual path.");
            }
        } else {
            System.out.println("No existing Robot project structure detected.");
        }
        System.out.print("Repository project path (Enter for repository root): ");
        if (!scanner.hasNextLine()) {
            throw new IllegalArgumentException("Git sync cancelled: no repository project path supplied.");
        }
        return scanner.nextLine().trim();
    }

    private SyncMode selectSyncMode(Scanner scanner) {
        while (true) {
            System.out.print("Sync mode (1-auto sync/overwrite, 2=merge): ");
            if (!scanner.hasNextLine()) {
                throw new IllegalArgumentException("Git sync cancelled: no mode selected.");
            }
            String answer = scanner.nextLine().trim();
            if ("1".equals(answer)) {
                return SyncMode.OVERWRITE;
            }
            if ("2".equals(answer)) {
                return SyncMode.MERGE;
            }
            System.out.println("Please enter 1 or 2.");
        }
    }

    private String prompt(Scanner scanner, String label) {
        System.out.print(label + ": ");
        return scanner.nextLine();
    }

    private boolean selectDatabaseCheck(Scanner scanner) {
        while (true) {
            String answer = prompt(scanner, "Database query check? y/n (Enter for HTTP)").trim();
            if ("y".equalsIgnoreCase(answer)) {
                return true;
            }
            if (answer.isEmpty() || "n".equalsIgnoreCase(answer)) {
                return false;
            }
            System.out.println("Enter y for database query or n for HTTP.");
        }
    }

    protected String readDatabasePassword() {
        java.io.Console console = System.console();
        if (console == null) {
            String password = System.getenv("DB_PASSWORD");
            if (password == null || password.isEmpty()) {
                throw new IllegalArgumentException("No hidden console available. Set DB_PASSWORD directly in your terminal before starting, or use ENV mode.");
            }
            return password;
        }
        char[] password = console.readPassword("DB password: ");
        if (password == null) {
            throw new IllegalArgumentException("DB password input cancelled");
        }
        try {
            return new String(password);
        } finally {
            java.util.Arrays.fill(password, '\0');
        }
    }
}