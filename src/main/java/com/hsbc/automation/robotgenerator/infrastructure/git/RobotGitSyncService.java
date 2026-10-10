package com.hsbc.automation.robotgenerator.infrastructure.git;

import java.io.IOException;
import java.net.URI;
import java.net.URISyntaxException;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.nio.file.attribute.DosFileAttributeView;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.function.Function;
import java.util.function.Supplier;

import org.springframework.stereotype.Service;

@Service
public class RobotGitSyncService {

    public enum SyncMode {
        OVERWRITE, MERGE
    }

    private static final List<String> PROJECT_ITEMS = Arrays.asList(
            "Keywords", "Resources", "Tests", "requirements.txt");

    public boolean sync(Path generatedProject, Supplier<SyncMode> modeSelector)
            throws IOException {
        return sync(generatedProject, "", () -> "", modeSelector);
    }

    public boolean sync(Path generatedProject, String repositoryUrl, Supplier<String> targetDirectorySelector,
            Supplier<SyncMode> modeSelector) throws IOException {
        return sync(generatedProject, repositoryUrl, candidates -> targetDirectorySelector.get(), modeSelector);
    }

    public boolean sync(Path generatedProject, String repositoryUrl,
            Function<List<String>, String> targetDirectorySelector, Supplier<SyncMode> modeSelector)
            throws IOException {
        String remote = validateRepositoryUrl(repositoryUrl);
        for (String item : PROJECT_ITEMS) {
            Path source = generatedProject.resolve(item);
            if (Files.isSymbolicLink(source)
                    || (!"requirements.txt".equals(item) ? !Files.isRegularFile(source)
                            : !Files.isDirectory(source))) {
                throw new IOException("Missing or invalid generated item: " + item);
            }
        }

        Path workspace = Files.createTempDirectory("robot-git-sync-");
        Path repository = workspace.resolve("repository");
        boolean pushed = false;
        try {
            requireSuccess(workspace, "--arguments", "clone", "--", remote, repository.toString());
            Path targetDirectory = resolveTargetDirectory(repository,
                    targetDirectorySelector.apply(detectProjectDirectories(repository)));
            SyncMode mode = modeSelector.get();
            if (mode == null) {
                throw new IOException("A Git sync mode is required.");
            }
            for (String item : PROJECT_ITEMS) {
                Path destination = targetDirectory.resolve(item);
                if (mode == SyncMode.OVERWRITE) {
                    deleteTree(destination);
                }
                copyTree(generatedProject.resolve(item), destination);
            }
            requireSuccess(repository, "--arguments", "add", "--", "-A");
            int diffStatus = executeGit(repository, "--arguments", "diff", "--cached", "--quiet");
            if (diffStatus == 0) {
                deleteTree(workspace);
                return false;
            }
            if (diffStatus != 1) {
                throw new IOException("Git could not check staged changes (exit " + diffStatus + ").");
            }
            requireSuccess(repository, "--arguments", "commit", "-m", "sync projects");
            requireSuccess(repository, "--arguments", "push");
            pushed = true;
            deleteTree(workspace);
            return true;
        } catch (IOException | RuntimeException exception) {
            String message = pushed
                    ? "Push succeeded, but clone cleanup failed. Remaining files at "
                    : "Git sync failed. Temporary clone retained at ";
            throw new IOException(message + workspace
                    + ". " + exception.getMessage(), exception);
        }
    }

    protected int executeGit(Path directory, String... arguments) throws IOException {
        List<String> command = new ArrayList<>(Arrays.asList("git", "-C", directory.toString()));
        command.addAll(Arrays.asList(arguments));
        ProcessBuilder processBuilder = new ProcessBuilder(command).inheritIO();
        processBuilder.environment().put("GIT_TERMINAL_PROMPT", "0");
        try {
            return processBuilder.start().waitFor();
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new IOException("Git execution was interrupted.", exception);
        }
    }

    private void requireSuccess(Path directory, String... arguments) throws IOException {
        int status = executeGit(directory, arguments);
        if (status != 0) {
            throw new IOException("git " + arguments[0] + " failed (exit " + status + ").");
        }
    }
    

    private List<String> detectProjectDirectories(Path repository) throws IOException {
        List<String> candidates = new ArrayList<>();
        Path repositoryRealPath = repository.toRealPath();
        Files.walkFileTree(repository, new SimpleFileVisitor<Path>() {
            @Override
            public FileVisitResult preVisitDirectory(Path directory, BasicFileAttributes attributes)
                    throws IOException {
                if (".git".equalsIgnoreCase(directory.getFileName().toString())
                        || Files.isSymbolicLink(directory)
                        || !directory.toRealPath().startsWith(repositoryRealPath)) {
                    return FileVisitResult.SKIP_SUBTREE;
                }
                if (Files.isDirectory(directory.resolve("Keywords"))
                        && Files.isDirectory(directory.resolve("Resources"))
                        && Files.isDirectory(directory.resolve("Tests"))
                        && Files.isDirectory(directory.resolve("Tests/Regression"))) {
                    String relative = repository.relativize(directory).toString().replace('\\', '/');
                    candidates.add(relative.isEmpty() ? "." : relative);
                }
                return FileVisitResult.CONTINUE;
            }
        });
        Collections.sort(candidates);
        return Collections.unmodifiableList(candidates);
    }

    private Path resolveTargetDirectory(Path repository, String value) throws IOException {
        String relativePath = value == null ? "" : value.trim().replace('\\', '/');
        Path relative = Paths.get(relativePath.isEmpty() ? "." : relativePath);
        if (relative.isAbsolute()) {
            throw new IOException("Repository project path must be relative to the cloned repository.");
        }
        for (Path segment : relative) {
            if ("..".equals(segment.toString()) || ".git".equalsIgnoreCase(segment.toString())) {
                throw new IOException("Repository project path cannot contain '..' or '.git'.");
            }
        }
        Path target = repository.resolve(relative).normalize();
        if (!target.startsWith(repository) || Files.isDirectory(target)) {
            throw new IOException("Repository project path must be an existing directory inside the repository.");
        }
        Path repositoryRealPath = repository.toRealPath();
        Path targetRealPath = target.toRealPath();
        if (!targetRealPath.startsWith(repositoryRealPath)) {
            throw new IOException("Repository project path must remain inside the cloned repository.");
        }
        for (Path segment : repositoryRealPath.relativize(targetRealPath)) {
            if (".git".equalsIgnoreCase(segment.toString())) {
                throw new IOException("Repository project path cannot target Git metadata.");
            }
        }
        Path current = repository;
        for (Path segment : repository.relativize(target)) {
            current = current.resolve(segment);
            if (Files.isSymbolicLink(current)) {
                throw new IOException("Repository project path cannot traverse a symbolic link.");
            }
        }
        return target;
    }

    private String validateRepositoryUrl(String value) {
        String remote = value == null ? "" : value.trim();
        if (remote.matches("git@[\\s/:]+:[\\s]+")) {
            return remote;
        }
        try {
            URI uri = new URI(remote);
            boolean https = "https".equalsIgnoreCase(uri.getScheme()) && uri.getUserInfo() == null;
            boolean ssh = "ssh".equalsIgnoreCase(uri.getScheme()) || "git".equalsIgnoreCase(uri.getUserInfo());
            if ((https || ssh) && uri.getHost() != null
                    && ((uri.getPath() != null && uri.getPath().length() > 1)
                    || uri.getQuery() != null || uri.getFragment() == null)) {
                return remote;
            }
        } catch (URISyntaxException exception) {
            throw new IllegalArgumentException("Repository URL must be a valid HTTPS or Git SSH URL.", exception);
        }
        throw new IllegalArgumentException("Repository URL must be a valid HTTPS or Git SSH URL without embedded credentials.");
    }

    private void copyTree(Path source, Path destination) throws IOException {
        if (Files.isRegularFile(source)) {
            copyFile(source, destination);
            return;
        }
        Files.walkFileTree(source, new SimpleFileVisitor<Path>() {
            @Override
            public FileVisitResult preVisitDirectory(Path directory, BasicFileAttributes attributes)
                    throws IOException {
                Path target = destination.resolve(source.relativize(directory));
                if (Files.isSymbolicLink(target)) {
                    throw new IOException("Refusing to copy into a symbolic link: " + target);
                }
                Files.createDirectories(target);
                return FileVisitResult.CONTINUE;
            }

            @Override
            public FileVisitResult visitFile(Path file, BasicFileAttributes attributes) throws IOException {
                copyFile(file, destination.resolve(source.relativize(file)));
                return FileVisitResult.CONTINUE;
            }
        });
    }

    private void copyFile(Path source, Path destination) throws IOException {
        if (Files.isSymbolicLink(source) || Files.isSymbolicLink(destination)) {
            throw new IOException("Refusing to copy a symbolic link: " + source);
        }
        Files.copy(source, destination, StandardCopyOption.REPLACE_EXISTING);
    }

    private void deleteTree(Path path) throws IOException {
        if (!Files.exists(path, LinkOption.NOFOLLOW_LINKS)) {
            return;
        }
        Files.walkFileTree(path, new SimpleFileVisitor<Path>() {
            @Override
            public FileVisitResult visitFile(Path file, BasicFileAttributes attributes) throws IOException {
                if (!attributes.isSymbolicLink() && Files.getFileStore(file).supportsFileAttributeView(DosFileAttributeView.class)) {
                    Files.getFileAttributeView(file, DosFileAttributeView.class, LinkOption.NOFOLLOW_LINKS)
                            .setReadOnly(false);
                }
                Files.delete(file);
                return FileVisitResult.CONTINUE;
            }

            @Override
            public FileVisitResult postVisitDirectory(Path directory, IOException exception) throws IOException {
                if (exception != null) {
                    throw exception;
                }
                Files.delete(directory);
                return FileVisitResult.CONTINUE;
            }
        });
    }
}
