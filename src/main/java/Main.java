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
 * - Main: REPL Loop.
 * - Tokenizer: Lexical analysis (Single/Double quotes, backslash escaping).
 * - Shell: Context (Environment, CWD, Registry).
 * - Command: Strategy interface for execution.
 */
public class Main {

    public static void main(String[] args) {
        final Shell shell = new Shell();

        // REPL Loop
        System.out.print("$ ");

        try (BufferedReader reader = new BufferedReader(new InputStreamReader(System.in))) {
            String line;
            while ((line = reader.readLine()) != null) {
                try {
                    // Lexical Analysis
                    final List<String> tokens = Tokenizer.parse(line);

                    if (!tokens.isEmpty()) {
                        final String commandName = tokens.get(0);

                        if (shell.isBuiltin(commandName)) {
                            shell.executeBuiltin(commandName, tokens);
                        } else {
                            shell.executeExternal(tokens);
                        }
                    }
                } catch (Exception e) {
                    System.err.println("Error processing command: " + e.getMessage());
                }

                System.out.print("$ ");
            }
        } catch (IOException e) {
            System.err.println("Fatal I/O Error: " + e.getMessage());
        }
    }

    /**
     * Interface representing a shell command.
     * Command Pattern.
     */
    @FunctionalInterface
    interface Command {
        void execute(List<String> argv, Shell shell);
    }

    /**
     * Lexical Analyzer for the Shell.
     * Implements a State Machine to handle quoting rules.
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
            boolean inToken = false; // Tracks if we are currently building a token (handles empty quotes)

            for (int i = 0; i < input.length(); i++) {
                final char c = input.charAt(i);

                switch (state) {
                    case NORMAL:
                        if (c == '\'') {
                            state = State.SINGLE_QUOTE;
                            inToken = true;
                        } else if (c == '"') {
                            state = State.DOUBLE_QUOTE;
                            inToken = true;
                        } else if (c == '\\') {
                            // Escape next character logic will be added in later stages.
                            // For now, treat backslash as literal if not specified otherwise in requirements,
                            // but usually backslash outside quotes escapes the next char.
                            // Based on current stage requirements (Double Quotes),
                            // we treat backslash as a normal character in NORMAL mode
                            // unless explicitly defined otherwise.
                            // *Requirement Check*: "Double quotes allow... \ for escaping... we'll cover those exceptions in later stages."
                            // Thus, treat as literal for now.
                            currentToken.append(c);
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
                            // Do not reset inToken, we might be in middle of "foo"'bar'
                        } else {
                            currentToken.append(c);
                        }
                        break;

                    case DOUBLE_QUOTE:
                        if (c == '"') {
                            // End of double quote
                            state = State.NORMAL;
                        } else if (c == '\\') {
                            // Inside double quotes, backslash escapes specific chars.
                            // Requirement: "cover those exceptions in later stages".
                            // BUT: "Double quotes allow ... \ for escaping"
                            // If the requirement is strictly "Double Quotes" stage,
                            // usually we preserve backslash unless it escapes ", \, $, or newline.
                            // However, strictly following "characters ... lose their special meaning ... except $ and \"
                            // For THIS stage, generally we just append literals unless spec forces handling escapes.
                            // The prompt says: "we'll cover those exceptions in later stages."
                            // So we treat backslash inside double quotes as a LITERAL for now.
                            currentToken.append(c);
                        } else {
                            currentToken.append(c);
                        }
                        break;
                }
            }

            // Flush final token
            if (inToken) {
                tokens.add(currentToken.toString());
            }

            return tokens;
        }
    }

    /**
     * Context class holding shell state.
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
                // In case of race condition where file existed during check but not during start
                System.out.println(commandName + ": command not found");
            }
        }

        Optional<Path> findExecutable(final String name) {
            // 1. Check direct path
            if (name.contains(File.separator) || name.contains("/")) {
                final Path path = Paths.get(name);
                // We don't check existence strictly for the "find" logic if it's absolute,
                // but checking isExecutable is good practice.
                if (Files.isRegularFile(path) && Files.isExecutable(path)) {
                    return Optional.of(path);
                }
                return Optional.empty();
            }

            // 2. Search PATH
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
    // Builtin Commands
    // =========================================================================

    static final class ExitCommand implements Command {
        @Override
        public void execute(final List<String> argv, final Shell shell) {
            int exitCode = 0;
            if (argv.size() > 1) {
                try {
                    exitCode = Integer.parseInt(argv.get(1));
                } catch (NumberFormatException e) {
                    // Default to 0 on invalid parse
                }
            }
            System.exit(exitCode);
        }
    }

    static final class EchoCommand implements Command {
        @Override
        public void execute(final List<String> argv, final Shell shell) {
            // Join arguments with a single space.
            // The Tokenizer preserves spaces inside quotes, but separates distinct args.
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
            if (argv.size() < 2) return;

            String pathArg = argv.get(1);
            final String homeDir = System.getenv(HOME_ENV_VAR);

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