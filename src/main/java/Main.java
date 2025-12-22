import java.io.BufferedReader;
import java.io.File;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * Main entry point for the Shell application.
 *
 * Architectural Summary:
 * - **Separation of Concerns**:
 *   - `Tokenizer`: Strictly handles lexical analysis (parsing strings into tokens), managing state for quotes and escaping.
 *   - `Shell`: Manages the runtime environment (CWD, PATH, Registry).
 *   - `Command`: Strategy interface allowing decoupled execution logic for different commands.
 * - **State Machine Parsing**: The Tokenizer uses an explicit Enum-based State Machine combined with an `escaped` flag to handle complex quoting and backslash logic without nesting conditional hell.
 * - **Singleton/Registry Pattern**: The `Shell` acts as a registry for built-ins, allowing O(1) command lookup.
 * - **Java 8 Compliance**: Strictly uses `Optional`, `Stream`, and `try-with-resources`. No `var` or Java 11+ APIs.
 * - **Immutability & Safety**: Uses `final` keywords extensively to prevent accidental reassignment and ensure thread-safety logic where applicable.
 */
public class Main {

    public static void main(String[] args) {
        final Shell shell = new Shell();

        // We use System.out for prompt to ensure it flushes correctly before blocking on read
        System.out.print("$ ");

        try (BufferedReader reader = new BufferedReader(new InputStreamReader(System.in))) {
            String line;
            while ((line = reader.readLine()) != null) {
                try {
                    // 1. Parsing Phase
                    final List<String> tokens = Tokenizer.parse(line);

                    // 2. Execution Phase
                    if (!tokens.isEmpty()) {
                        final String commandName = tokens.get(0);
                        if (shell.isBuiltin(commandName)) {
                            shell.executeBuiltin(commandName, tokens);
                        } else {
                            shell.executeExternal(tokens);
                        }
                    }
                } catch (IllegalArgumentException e) {
                    System.err.println("Syntax error: " + e.getMessage());
                } catch (Exception e) {
                    System.err.println("Error: " + e.getMessage());
                }

                System.out.print("$ ");
            }
        } catch (IOException e) {
            System.err.println("Fatal I/O Error: " + e.getMessage());
        }
    }

    // =========================================================================
    // Core Interfaces & Logic
    // =========================================================================

    /**
     * Strategy Interface for Shell Commands.
     */
    @FunctionalInterface
    interface Command {
        void execute(List<String> argv, Shell shell);
    }

    /**
     * Lexical Analyzer.
     * Handles Single Quotes, Double Quotes, and Backslash Escaping.
     */
    static final class Tokenizer {
        private enum State {
            NORMAL,
            SINGLE_QUOTE,
            DOUBLE_QUOTE
        }

        private Tokenizer() {}

        static List<String> parse(final String input) {
            final List<String> tokens = new ArrayList<>();
            final StringBuilder currentToken = new StringBuilder();

            State state = State.NORMAL;
            boolean escaped = false; // Tracks if the previous char was a backslash (in NORMAL state)
            boolean inToken = false; // Tracks if we are strictly inside a token (handles empty quotes)

            for (int i = 0; i < input.length(); i++) {
                final char c = input.charAt(i);

                if (escaped) {
                    // If we are in escape mode (only possible in NORMAL state based on logic below),
                    // append the character literally and turn off escape mode.
                    currentToken.append(c);
                    escaped = false;
                    inToken = true;
                    continue;
                }

                switch (state) {
                    case NORMAL:
                        if (c == '\\') {
                            // Enable escape mode for the NEXT character
                            escaped = true;
                            // Do NOT append the backslash itself
                        } else if (c == '\'') {
                            state = State.SINGLE_QUOTE;
                            inToken = true;
                        } else if (c == '"') {
                            state = State.DOUBLE_QUOTE;
                            inToken = true;
                        } else if (Character.isWhitespace(c)) {
                            if (inToken) {
                                tokens.add(currentToken.toString());
                                currentToken.setLength(0);
                                inToken = false;
                            }
                        } else {
                            currentToken.append(c);
                            inToken = true;
                        }
                        break;

                    case SINGLE_QUOTE:
                        if (c == '\'') {
                            state = State.NORMAL;
                            // We remain inToken=true because 'foo' is a valid token part
                        } else {
                            currentToken.append(c);
                        }
                        break;

                    case DOUBLE_QUOTE:
                        if (c == '"') {
                            state = State.NORMAL;
                        } else if (c == '\\') {
                            // Note: The prompt specifically asks for Backslash Support *Outside* Quotes.
                            // Standard shell behavior for backslash *inside* double quotes is complex
                            // (escapes $, ", \, ` and newline).
                            // For this stage, checking the "Double Quotes" stage requirements usually
                            // implies treating backslash as literal unless we are specifically implementing
                            // double-quote escaping. We will append literal backslash for now to match
                            // previous stage behavior unless it's a specific escape sequence required later.
                            currentToken.append(c);
                        } else {
                            currentToken.append(c);
                        }
                        break;
                }
            }

            // Handle trailing backslash case (e.g., input ends with \)
            // Standard shell might wait for more input. Here we just ignore it as incomplete.

            if (inToken) {
                tokens.add(currentToken.toString());
            }

            return tokens;
        }
    }

    /**
     * Shell Context.
     * Manages state (Current Working Directory) and Command Registry.
     */
    static final class Shell {
        private static final String PATH_ENV_VAR = "PATH";
        private static final String PATH_SPLIT_REGEX = Pattern.quote(File.pathSeparator);

        private final Map<String, Command> builtins;
        private Path currentDirectory;

        Shell() {
            this.builtins = new HashMap<>();
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

        boolean isBuiltin(final String name) {
            return builtins.containsKey(name);
        }

        void executeBuiltin(final String name, final List<String> argv) {
            final Command cmd = builtins.get(name);
            if (cmd != null) {
                cmd.execute(argv, this);
            }
        }

        void executeExternal(final List<String> argv) {
            final String commandName = argv.get(0);

            try {
                // If it's not found, print error
                if (!findExecutable(commandName).isPresent()) {
                    System.out.println(commandName + ": command not found");
                    return;
                }

                // If found, execute
                final ProcessBuilder pb = new ProcessBuilder(argv);
                pb.inheritIO();
                pb.directory(currentDirectory.toFile());

                final Process process = pb.start();
                process.waitFor();
            } catch (IOException | InterruptedException e) {
                // Should catch race conditions or permission issues
                System.out.println(commandName + ": command not found");
            }
        }

        /**
         * Resolves an executable name to a Path, checking CWD and PATH.
         */
        Optional<Path> findExecutable(final String name) {
            // 1. Check if name is a path (contains separators)
            if (name.contains(File.separator) || name.contains("/")) {
                final Path path = Paths.get(name);
                // For relative paths with separators (e.g., ./script.sh), resolve against CWD
                // But generally ProcessBuilder handles absolute/relative logic.
                // We just need to check if it exists for the "command not found" logic.
                if (Files.isRegularFile(path) && Files.isExecutable(path)) {
                    return Optional.of(path);
                }
                return Optional.empty();
            }

            // 2. Search system PATH
            final String pathEnv = System.getenv(PATH_ENV_VAR);
            if (pathEnv == null || pathEnv.isEmpty()) {
                return Optional.empty();
            }

            return Arrays.stream(pathEnv.split(PATH_SPLIT_REGEX))
                    .map(Paths::get)
                    .map(dir -> dir.resolve(name))
                    .filter(fullPath -> Files.isRegularFile(fullPath) && Files.isExecutable(fullPath))
                    .findFirst();
        }

        Path getCurrentDirectory() {
            return currentDirectory;
        }

        void setCurrentDirectory(final Path newDirectory) {
            this.currentDirectory = newDirectory.toAbsolutePath().normalize();
        }
    }

    // =========================================================================
    // Builtin Command Implementations
    // =========================================================================

    static final class ExitCommand implements Command {
        @Override
        public void execute(final List<String> argv, final Shell shell) {
            int exitCode = 0;
            if (argv.size() > 1) {
                try {
                    exitCode = Integer.parseInt(argv.get(1));
                } catch (NumberFormatException e) {
                    exitCode = 0;
                }
            }
            System.exit(exitCode);
        }
    }

    static final class EchoCommand implements Command {
        @Override
        public void execute(final List<String> argv, final Shell shell) {
            // Echo simply joins arguments with a space.
            // The Tokenizer has already handled the "removal" of backslashes and quotes.
            final String output = argv.stream()
                    .skip(1)
                    .collect(Collectors.joining(" "));
            System.out.println(output);
        }
    }

    static final class TypeCommand implements Command {
        @Override
        public void execute(final List<String> argv, final Shell shell) {
            if (argv.size() < 2) return;

            final String name = argv.get(1);

            if (shell.isBuiltin(name)) {
                System.out.println(name + " is a shell builtin");
                return;
            }

            final Optional<Path> path = shell.findExecutable(name);
            if (path.isPresent()) {
                System.out.println(name + " is " + path.get().toAbsolutePath());
            } else {
                System.out.println(name + ": not found");
            }
        }
    }

    static final class PwdCommand implements Command {
        @Override
        public void execute(final List<String> argv, final Shell shell) {
            System.out.println(shell.getCurrentDirectory().toString());
        }
    }

    static final class CdCommand implements Command {
        private static final String HOME_ENV_VAR = "HOME";
        private static final String TILDE = "~";

        @Override
        public void execute(final List<String> argv, final Shell shell) {
            if (argv.size() < 2) return; // 'cd' with no args technically goes HOME in bash, but basic implementation often does nothing.

            String pathArg = argv.get(1);
            final String homeDir = System.getenv(HOME_ENV_VAR);

            // Handle Tilde Expansion
            if (pathArg.equals(TILDE)) {
                if (homeDir == null) {
                    System.out.println("cd: HOME not set");
                    return;
                }
                pathArg = homeDir;
            } else if (pathArg.startsWith(TILDE + File.separator)) {
                if (homeDir == null) {
                    System.out.println("cd: HOME not set");
                    return;
                }
                pathArg = homeDir + pathArg.substring(1);
            }

            Path path = Paths.get(pathArg);

            // Handle Relative Paths
            if (!path.isAbsolute()) {
                path = shell.getCurrentDirectory().resolve(path);
            }

            final Path resolvedPath = path.normalize();

            if (Files.exists(resolvedPath) && Files.isDirectory(resolvedPath)) {
                shell.setCurrentDirectory(resolvedPath);
            } else {
                System.out.println("cd: " + argv.get(1) + ": No such file or directory");
            }
        }
    }
}
