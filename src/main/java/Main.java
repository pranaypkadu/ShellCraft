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
 * Main Shell Application.
 *
 * Supports:
 * - Built-in commands (cd, pwd, echo, type, exit)
 * - External program execution via PATH
 * - Single Quotes (Literal handling of backslashes)
 * - Double Quotes & Backslash escaping
 */
public class Main {

    public static void main(String[] args) {
        final Shell shell = new Shell();

        // Use standard output for the prompt
        System.out.print("$ ");

        try (BufferedReader reader = new BufferedReader(new InputStreamReader(System.in))) {
            String line;
            while ((line = reader.readLine()) != null) {
                List<String> tokens = null;
                try {
                    // 1. Parsing
                    tokens = Lexer.tokenize(line);

                    // 2. Execution
                    if (!tokens.isEmpty()) {
                        String commandName = tokens.get(0);
                        Command command = shell.resolveCommand(commandName);
                        command.execute(tokens, shell);
                    }
                } catch (IllegalArgumentException e) {
                    System.err.println("Syntax error: " + e.getMessage());
                } catch (Exception e) {
                    // Fixed: Check tokens safely before accessing
                    if (tokens != null && !tokens.isEmpty()) {
                        System.out.println(tokens.get(0) + ": command not found");
                    } else {
                        System.out.println("command not found");
                    }
                }

                System.out.print("$ ");
            }
        } catch (IOException e) {
            System.err.println("Fatal I/O Error: " + e.getMessage());
        }
    }

    // =========================================================================
    // Core Abstractions
    // =========================================================================

    /**
     * Interface representing an executable shell command.
     */
    @FunctionalInterface
    interface Command {
        void execute(List<String> args, Shell shell);
    }

    /**
     * Lexical Analyzer for parsing shell input.
     * Implements a State Machine to handle quotes and escaping.
     *
     * Key Behavior for this stage:
     * - Backslashes INSIDE single quotes are LITERAL (no special escaping)
     * - Backslashes OUTSIDE quotes escape the next character
     */
    static final class Lexer {
        private enum State {
            DEFAULT,
            SINGLE_QUOTE,
            DOUBLE_QUOTE,
            ESCAPE
        }

        private Lexer() {}

        static List<String> tokenize(String input) {
            final List<String> tokens = new ArrayList<>();
            final StringBuilder currentToken = new StringBuilder();

            State state = State.DEFAULT;
            boolean inToken = false;

            for (int i = 0; i < input.length(); i++) {
                char c = input.charAt(i);

                switch (state) {
                    case DEFAULT:
                        if (c == '\\') {
                            state = State.ESCAPE;
                            inToken = true;
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

                    case ESCAPE:
                        // Backslash escapes next char literally (outside quotes)
                        currentToken.append(c);
                        state = State.DEFAULT;
                        break;

                    case SINGLE_QUOTE:
                        // CRITICAL: Backslashes in single quotes are LITERAL
                        if (c == '\'') {
                            state = State.DEFAULT;
                        } else {
                            currentToken.append(c); // Backslash is literal here!
                        }
                        break;

                    case DOUBLE_QUOTE:
                        if (c == '"') {
                            state = State.DEFAULT;
                        } else {
                            currentToken.append(c); // Simplified double quote handling
                        }
                        break;
                }
            }

            if (inToken) {
                tokens.add(currentToken.toString());
            }

            return tokens;
        }
    }

    /**
     * Context manager for the Shell.
     */
    static final class Shell {
        private static final String PATH_VAR = "PATH";
        private static final String PATH_SPLIT = Pattern.quote(File.pathSeparator);

        private final Map<String, Command> builtins = new HashMap<>();
        private Path currentDirectory;

        Shell() {
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

        Command resolveCommand(String name) {
            if (builtins.containsKey(name)) {
                return builtins.get(name);
            }
            return this::executeExternal;
        }

        boolean isBuiltin(String name) {
            return builtins.containsKey(name);
        }

        Path getCurrentDirectory() {
            return currentDirectory;
        }

        void setCurrentDirectory(Path path) {
            this.currentDirectory = path.toAbsolutePath().normalize();
        }

        private void executeExternal(List<String> args, Shell shell) {
            String commandName = args.get(0);

            try {
                Optional<Path> executable = findExecutable(commandName);
                if (!executable.isPresent()) {
                    System.out.println(commandName + ": command not found");
                    return;
                }

                ProcessBuilder pb = new ProcessBuilder(args);
                pb.directory(currentDirectory.toFile());
                pb.inheritIO();

                Process process = pb.start();
                process.waitFor();
            } catch (IOException | InterruptedException e) {
                System.out.println(commandName + ": command not found");
            }
        }

        Optional<Path> findExecutable(String name) {
            if (name.contains(File.separator) || name.contains("/")) {
                Path p = Paths.get(name);
                if (Files.isRegularFile(p) && Files.isExecutable(p)) {
                    return Optional.of(p);
                }
                return Optional.empty();
            }

            String pathEnv = System.getenv(PATH_VAR);
            if (pathEnv == null || pathEnv.isEmpty()) return Optional.empty();

            return Arrays.stream(pathEnv.split(PATH_SPLIT))
                    .map(Paths::get)
                    .map(dir -> dir.resolve(name))
                    .filter(p -> Files.isRegularFile(p) && Files.isExecutable(p))
                    .findFirst();
        }
    }

    // =========================================================================
    // Builtin Commands
    // =========================================================================

    static final class ExitCommand implements Command {
        @Override
        public void execute(List<String> args, Shell shell) {
            int code = 0;
            if (args.size() > 1) {
                try {
                    code = Integer.parseInt(args.get(1));
                } catch (NumberFormatException ignored) {}
            }
            System.exit(code);
        }
    }

    static final class EchoCommand implements Command {
        @Override
        public void execute(List<String> args, Shell shell) {
            if (args.size() > 1) {
                System.out.println(args.stream().skip(1).collect(Collectors.joining(" ")));
            } else {
                System.out.println();
            }
        }
    }

    static final class TypeCommand implements Command {
        @Override
        public void execute(List<String> args, Shell shell) {
            if (args.size() < 2) return;
            String target = args.get(1);

            if (shell.isBuiltin(target)) {
                System.out.println(target + " is a shell builtin");
            } else {
                Optional<Path> path = shell.findExecutable(target);
                if (path.isPresent()) {
                    System.out.println(target + " is " + path.get().toAbsolutePath());
                } else {
                    System.out.println(target + ": not found");
                }
            }
        }
    }

    static final class PwdCommand implements Command {
        @Override
        public void execute(List<String> args, Shell shell) {
            System.out.println(shell.getCurrentDirectory());
        }
    }

    static final class CdCommand implements Command {
        private static final String HOME_VAR = "HOME";

        @Override
        public void execute(List<String> args, Shell shell) {
            if (args.size() < 2) return;

            String targetDir = args.get(1);
            String home = System.getenv(HOME_VAR);

            // Tilde expansion
            if (targetDir.equals("~")) {
                if (home == null) {
                    System.out.println("cd: HOME not set");
                    return;
                }
                targetDir = home;
            } else if (targetDir.startsWith("~" + File.separator)) {
                if (home == null) {
                    System.out.println("cd: HOME not set");
                    return;
                }
                targetDir = home + targetDir.substring(1);
            }

            Path path = Paths.get(targetDir);
            if (!path.isAbsolute()) {
                path = shell.getCurrentDirectory().resolve(path);
            }

            Path resolved = path.normalize();
            if (Files.exists(resolved) && Files.isDirectory(resolved)) {
                shell.setCurrentDirectory(resolved);
            } else {
                System.out.println("cd: " + args.get(1) + ": No such file or directory");
            }
        }
    }
}