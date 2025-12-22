import java.io.BufferedReader;
import java.io.File;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.StringTokenizer;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * Main entry point for the Shell application.
 * Handles the REPL (Read-Eval-Print Loop) cycle.
 */
public class Main {

    public static void main(String[] args) {
        Shell shell = new Shell();

        // Print the prompt initially
        System.out.print("$ ");

        try (BufferedReader reader = new BufferedReader(new InputStreamReader(System.in))) {
            String line;
            while ((line = reader.readLine()) != null) {
                // Handle empty lines (just reprint prompt)
                if (line.trim().isEmpty()) {
                    System.out.print("$ ");
                    continue;
                }

                List<String> tokens = Tokenizer.tokenize(line);
                if (!tokens.isEmpty()) {
                    String commandName = tokens.get(0);

                    if (shell.isBuiltin(commandName)) {
                        shell.executeBuiltin(commandName, tokens);
                    } else {
                        shell.executeExternal(tokens);
                    }
                }

                System.out.print("$ ");
            }
        } catch (IOException e) {
            System.err.println("Error reading input: " + e.getMessage());
        }
    }

    /**
     * Interface representing a shell command.
     * Follows the Command Design Pattern.
     */
    interface Command {
        void execute(List<String> argv, Shell shell);
    }

    /**
     * Context class holding the state of the shell (CWD, environment)
     * and the registry of builtin commands.
     */
    static final class Shell {
        private static final String PATH_ENV_VAR = "PATH";
        private static final String PATH_SPLIT_REGEX = Pattern.quote(File.pathSeparator);

        private final Map<String, Command> builtins;
        private Path currentDirectory;

        Shell() {
            this.builtins = new HashMap<>();
            // Initialize CWD to the process's starting directory
            this.currentDirectory = Paths.get(System.getProperty("user.dir")).toAbsolutePath().normalize();
            registerBuiltins();
        }

        private void registerBuiltins() {
            builtins.put("exit", new ExitCommand());
            builtins.put("echo", new EchoCommand());
            builtins.put("type", new TypeCommand());
            builtins.put("pwd", new PwdCommand());
            builtins.put("cd", new CdCommand());
        }

        boolean isBuiltin(String name) {
            return builtins.containsKey(name);
        }

        void executeBuiltin(String name, List<String> argv) {
            Command cmd = builtins.get(name);
            if (cmd != null) {
                cmd.execute(argv, this);
            }
        }

        void executeExternal(List<String> argv) {
            String commandName = argv.get(0);
            Optional<Path> executablePath = findExecutable(commandName);

            if (!executablePath.isPresent()) {
                System.out.println(commandName + ": command not found");
                return;
            }

            try {
                ProcessBuilder pb = new ProcessBuilder(argv);
                // Inherit IO ensures the external process prints directly to stdout/stderr
                pb.inheritIO();
                // Ensure the external process starts in the shell's current directory
                pb.directory(currentDirectory.toFile());

                Process process = pb.start();
                process.waitFor();
            } catch (IOException | InterruptedException e) {
                System.out.println(commandName + ": command not found");
            }
        }

        Optional<Path> findExecutable(String name) {
            // 1. Check if it's a direct path (absolute or relative with separators)
            if (name.contains(File.separator) || name.contains("/")) {
                Path path = Paths.get(name);
                // If relative, resolve against CWD to check existence strictly,
                // though usually direct execution relies on system resolution.
                // For 'type' command accuracy, we resolve it.
                if (!path.isAbsolute()) {
                    path = currentDirectory.resolve(path);
                }

                if (Files.isRegularFile(path) && Files.isExecutable(path)) {
                    return Optional.of(path);
                }
                return Optional.empty();
            }

            // 2. Search in PATH
            String pathEnv = System.getenv(PATH_ENV_VAR);
            if (pathEnv == null || pathEnv.isEmpty()) {
                return Optional.empty();
            }

            String[] directories = pathEnv.split(PATH_SPLIT_REGEX);
            for (String dir : directories) {
                try {
                    Path folder = Paths.get(dir);
                    Path fullPath = folder.resolve(name);
                    if (Files.isRegularFile(fullPath) && Files.isExecutable(fullPath)) {
                        return Optional.of(fullPath);
                    }
                } catch (InvalidPathException ignored) {
                    // Skip invalid paths in PATH env
                }
            }

            return Optional.empty();
        }

        Path getCurrentDirectory() {
            return currentDirectory;
        }

        void setCurrentDirectory(Path newDirectory) {
            this.currentDirectory = newDirectory.toAbsolutePath().normalize();
        }
    }

    /**
     * Utility class for parsing command lines.
     */
    static final class Tokenizer {
        private Tokenizer() {}

        static List<String> tokenize(String line) {
            List<String> tokens = new ArrayList<>();
            StringTokenizer st = new StringTokenizer(line);
            while (st.hasMoreTokens()) {
                tokens.add(st.nextToken());
            }
            return tokens;
        }
    }

    // =========================================================================
    // Builtin Command Implementations
    // =========================================================================

    /**
     * Handles 'exit'.
     */
    static final class ExitCommand implements Command {
        @Override
        public void execute(List<String> argv, Shell shell) {
            int exitCode = 0;
            if (argv.size() > 1) {
                try {
                    exitCode = Integer.parseInt(argv.get(1));
                } catch (NumberFormatException e) {
                    // Mimic bash: if non-numeric argument, it exits with 255 or similar,
                    // but for this task, 0 is the safe default or keep existing.
                }
            }
            System.exit(exitCode);
        }
    }

    /**
     * Handles 'echo'.
     */
    static final class EchoCommand implements Command {
        @Override
        public void execute(List<String> argv, Shell shell) {
            String output = argv.stream()
                    .skip(1)
                    .collect(Collectors.joining(" "));
            System.out.println(output);
        }
    }

    /**
     * Handles 'type'.
     */
    static final class TypeCommand implements Command {
        @Override
        public void execute(List<String> argv, Shell shell) {
            if (argv.size() < 2) return;

            String name = argv.get(1);

            if (shell.isBuiltin(name)) {
                System.out.println(name + " is a shell builtin");
                return;
            }

            Optional<Path> path = shell.findExecutable(name);
            if (path.isPresent()) {
                System.out.println(name + " is " + path.get().toAbsolutePath());
            } else {
                System.out.println(name + ": not found");
            }
        }
    }

    /**
     * Handles 'pwd'.
     */
    static final class PwdCommand implements Command {
        @Override
        public void execute(List<String> argv, Shell shell) {
            System.out.println(shell.getCurrentDirectory().toString());
        }
    }

    /**
     * Handles 'cd'.
     * Supports absolute paths and relative paths (./, ../, dirname).
     */
    static final class CdCommand implements Command {
        @Override
        public void execute(List<String> argv, Shell shell) {
            // "cd" with no arguments usually implies home, but not required by this specific stage.
            if (argv.size() < 2) {
                return;
            }

            String pathArg = argv.get(1);
            Path newPath;

            // 1. Handle Home alias (optional but standard)
            if (pathArg.equals("~")) {
                newPath = Paths.get(System.getProperty("user.home"));
            }
            // 2. Handle Absolute Paths
            else if (pathArg.startsWith("/")) {
                newPath = Paths.get(pathArg);
            }
            // 3. Handle Relative Paths
            else {
                // Combine current shell CWD with the requested relative path
                newPath = shell.getCurrentDirectory().resolve(pathArg);
            }

            // Resolve redundancies like "." and ".."
            // E.g., /usr/local/../bin becomes /usr/bin
            newPath = newPath.normalize();

            if (Files.exists(newPath) && Files.isDirectory(newPath)) {
                shell.setCurrentDirectory(newPath);
            } else {
                System.out.println("cd: " + pathArg + ": No such file or directory");
            }
        }
    }
}