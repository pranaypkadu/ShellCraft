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
 * Architecture:
 * - Main: Handles the REPL (Read-Eval-Print Loop).
 * - CommandParser: Handles lexical analysis (Quoting, Spacing).
 * - Shell: Context object holding state (Environment, CWD, Registry).
 * - Command: Interface for the Command Pattern.
 */
public class Main {

    public static void main(String[] args) {
        final Shell shell = new Shell();

        // Print the prompt initially
        System.out.print("$ ");

        try (BufferedReader reader = new BufferedReader(new InputStreamReader(System.in))) {
            String line;
            while ((line = reader.readLine()) != null) {
                // Parse input using the custom parser to handle quotes
                final List<String> tokens = CommandParser.parse(line);

                if (!tokens.isEmpty()) {
                    final String commandName = tokens.get(0);

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
     * Implementation of the Command Design Pattern.
     */
    @FunctionalInterface
    interface Command {
        void execute(List<String> argv, Shell shell);
    }

    /**
     * Stateless utility for lexical analysis.
     * Handles single quotes and whitespace preservation.
     */
    static final class CommandParser {
        private CommandParser() {}

        static List<String> parse(final String input) {
            final List<String> tokens = new ArrayList<>();
            final StringBuilder currentToken = new StringBuilder();

            boolean inSingleQuotes = false;
            boolean inToken = false; // Tracks if we are currently building a token

            for (int i = 0; i < input.length(); i++) {
                final char c = input.charAt(i);

                if (inSingleQuote(inSingleQuotes, c)) {
                    // Toggle state, consume the quote char
                    inSingleQuotes = !inSingleQuotes;
                    // Mark that we are inside a token (handles empty quotes case like '')
                    inToken = true;
                } else if (inSingleQuotes) {
                    // Inside quotes: preserve everything literally
                    currentToken.append(c);
                    inToken = true;
                } else {
                    // Outside quotes
                    if (Character.isWhitespace(c)) {
                        if (inToken) {
                            tokens.add(currentToken.toString());
                            currentToken.setLength(0); // Clear buffer
                            inToken = false;
                        }
                    } else {
                        currentToken.append(c);
                        inToken = true;
                    }
                }
            }

            // Flush the last token if exists
            if (inToken) {
                tokens.add(currentToken.toString());
            }

            return tokens;
        }

        private static boolean inSingleQuote(boolean currentState, char c) {
            return c == '\'';
        }
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

            // ProcessBuilder handles the complex logic of passing arguments with spaces
            // to the OS, as long as we provide them as a correct List<String>.
            try {
                // Check if command exists before trying to run it
                if (!findExecutable(commandName).isPresent()) {
                    System.out.println(commandName + ": command not found");
                    return;
                }

                final ProcessBuilder pb = new ProcessBuilder(argv);
                pb.inheritIO();
                pb.directory(currentDirectory.toFile());

                final Process process = pb.start();
                process.waitFor();
            } catch (IOException | InterruptedException e) {
                // Fallback catch, though checks above should prevent this for missing files
                System.out.println(commandName + ": command not found");
            }
        }

        Optional<Path> findExecutable(final String name) {
            // 1. Check if it's a direct path (absolute or relative with separators)
            if (name.contains(File.separator) || name.contains("/")) {
                final Path path = Paths.get(name);
                if (Files.isRegularFile(path) && Files.isExecutable(path)) {
                    return Optional.of(path);
                }
                return Optional.empty();
            }

            // 2. Search in PATH
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
                    // Ignore invalid input, exit with 0 as per typical shell behavior
                }
            }
            System.exit(exitCode);
        }
    }

    static final class EchoCommand implements Command {
        @Override
        public void execute(final List<String> argv, final Shell shell) {
            // Join arguments with a single space.
            // Note: The Tokenizer has already preserved spaces *inside* quotes
            // and stripped the quotes themselves.
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
            if (argv.size() < 2) {
                return; // Behaves like no-op in this specific spec, though usually goes home
            }

            String pathArg = argv.get(1);
            final String homeDir = System.getenv(HOME_ENV_VAR);

            // 1. Handle Tilde Expansion
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

            // 2. Handle Relative Paths
            if (!path.isAbsolute()) {
                path = shell.getCurrentDirectory().resolve(path);
            }

            // 3. Normalize
            final Path resolvedPath = path.normalize();

            // 4. Verify and Switch
            if (Files.exists(resolvedPath) && Files.isDirectory(resolvedPath)) {
                shell.setCurrentDirectory(resolvedPath);
            } else {
                System.out.println("cd: " + argv.get(1) + ": No such file or directory");
            }
        }
    }
}