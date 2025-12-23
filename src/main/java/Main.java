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
 * Main Entry Point.
 *
 * Runs the interactive shell supporting quoted executables and built-in commands.
 */
public class Main {

    public static void main(String[] args) {
        // Dependency Injection / Composition Root
        final Environment environment = new Environment();
        final PathResolver pathResolver = new PathResolver(environment);
        final CommandRegistry registry = new CommandRegistry();
        final Shell shell = new Shell(environment, registry, pathResolver);

        shell.run();
    }

    // =========================================================================
    // Core Logic: Shell Orchestrator
    // =========================================================================

    /**
     * Orchestrates the Read-Eval-Print Loop (REPL).
     */
    static final class Shell {
        private final Environment environment;
        private final CommandRegistry registry;
        private final PathResolver pathResolver;

        Shell(Environment environment, CommandRegistry registry, PathResolver pathResolver) {
            this.environment = environment;
            this.registry = registry;
            this.pathResolver = pathResolver;
        }

        void run() {
            System.out.print("$ ");
            try (BufferedReader reader = new BufferedReader(new InputStreamReader(System.in))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    handleLine(line);
                    System.out.print("$ ");
                }
            } catch (IOException e) {
                System.err.println("Fatal I/O Error: " + e.getMessage());
            }
        }

        private void handleLine(String line) {
            try {
                // 1. Parse
                List<String> tokens = Tokenizer.tokenize(line);
                if (tokens.isEmpty()) return;

                // 2. Resolve
                String commandName = tokens.get(0);
                Command command = registry.getCommand(commandName)
                        .orElseGet(() -> new ExternalCommand(commandName, pathResolver));

                // 3. Execute
                command.execute(tokens, environment, pathResolver, registry);

            } catch (Exception e) {
                // Catch-all to prevent shell crash on malformed input or execution failures
                if (e.getMessage() != null && !e.getMessage().isEmpty()) {
                    System.out.println(e.getMessage());
                }
            }
        }
    }

    // =========================================================================
    // Domain: Environment & State
    // =========================================================================

    /**
     * Manages shell state (CWD) and environment variables.
     */
    static final class Environment {
        private static final String PATH_VAR = "PATH";
        private static final String HOME_VAR = "HOME";
        private Path currentDirectory;

        Environment() {
            this.currentDirectory = Paths.get(System.getProperty("user.dir")).toAbsolutePath().normalize();
        }

        Path getCurrentDirectory() {
            return currentDirectory;
        }

        void setCurrentDirectory(Path path) {
            this.currentDirectory = path.toAbsolutePath().normalize();
        }

        String getEnv(String key) {
            return System.getenv(key);
        }

        List<Path> getPathDirectories() {
            String pathEnv = getEnv(PATH_VAR);
            if (pathEnv == null || pathEnv.isEmpty()) {
                return new ArrayList<>();
            }
            return Arrays.stream(pathEnv.split(Pattern.quote(File.pathSeparator)))
                    .map(Paths::get)
                    .collect(Collectors.toList());
        }

        String getHomeDirectory() {
            return getEnv(HOME_VAR);
        }
    }

    /**
     * Logic for locating executable files on the file system.
     */
    static final class PathResolver {
        private final Environment env;

        PathResolver(Environment env) {
            this.env = env;
        }

        Optional<Path> findExecutable(String name) {
            // Case 1: Explicit path (relative or absolute)
            if (name.contains(File.separator) || name.contains("/")) {
                Path p = Paths.get(name);
                if (Files.isRegularFile(p) && Files.isExecutable(p)) {
                    return Optional.of(p);
                }
                return Optional.empty();
            }

            // Case 2: Search in PATH
            return env.getPathDirectories().stream()
                    .map(dir -> dir.resolve(name))
                    .filter(p -> Files.isRegularFile(p) && Files.isExecutable(p))
                    .findFirst();
        }
    }

    // =========================================================================
    // Parsing: Tokenizer (FSM)
    // =========================================================================

    static final class Tokenizer {
        private enum State {
            DEFAULT, SINGLE_QUOTE, DOUBLE_QUOTE, DOUBLE_QUOTE_ESCAPE, ESCAPE
        }

        private Tokenizer() {}

        static List<String> tokenize(String input) {
            final List<String> tokens = new ArrayList<>();
            final StringBuilder currentToken = new StringBuilder();

            State state = State.DEFAULT;
            boolean inToken = false;

            for (char c : input.toCharArray()) {
                switch (state) {
                    case DEFAULT:
                        if (Character.isWhitespace(c)) {
                            if (inToken) {
                                tokens.add(currentToken.toString());
                                currentToken.setLength(0);
                                inToken = false;
                            }
                        } else if (c == '\\') {
                            state = State.ESCAPE;
                            inToken = true;
                        } else if (c == '\'') {
                            state = State.SINGLE_QUOTE;
                            inToken = true;
                        } else if (c == '"') {
                            state = State.DOUBLE_QUOTE;
                            inToken = true;
                        } else {
                            currentToken.append(c);
                            inToken = true;
                        }
                        break;
                    case ESCAPE:
                        currentToken.append(c);
                        state = State.DEFAULT;
                        break;
                    case SINGLE_QUOTE:
                        if (c == '\'') {
                            state = State.DEFAULT;
                        } else {
                            currentToken.append(c);
                        }
                        break;
                    case DOUBLE_QUOTE:
                        if (c == '"') {
                            state = State.DEFAULT;
                        } else if (c == '\\') {
                            state = State.DOUBLE_QUOTE_ESCAPE;
                        } else {
                            currentToken.append(c);
                        }
                        break;
                    case DOUBLE_QUOTE_ESCAPE:
                        if (c == '\\' || c == '"') {
                            currentToken.append(c);
                        } else {
                            currentToken.append('\\');
                            currentToken.append(c);
                        }
                        state = State.DOUBLE_QUOTE;
                        break;
                }
            }

            if (inToken) {
                tokens.add(currentToken.toString());
            }
            return tokens;
        }
    }

    // =========================================================================
    // Command Pattern & Strategy
    // =========================================================================

    @FunctionalInterface
    interface Command {
        void execute(List<String> args, Environment env, PathResolver resolver, CommandRegistry registry);
    }

    static final class CommandRegistry {
        private final Map<String, Command> builtins = new HashMap<>();

        CommandRegistry() {
            builtins.put("exit", new ExitCommand());
            builtins.put("echo", new EchoCommand());
            builtins.put("type", new TypeCommand());
            builtins.put("pwd", new PwdCommand());
            builtins.put("cd", new CdCommand());
        }

        Optional<Command> getCommand(String name) {
            return Optional.ofNullable(builtins.get(name));
        }

        boolean isBuiltin(String name) {
            return builtins.containsKey(name);
        }
    }

    // =========================================================================
    // Command Implementations
    // =========================================================================

    /**
     * Strategy for executing external binaries.
     */
    static final class ExternalCommand implements Command {
        private final String commandName;
        private final PathResolver pathResolver;

        ExternalCommand(String commandName, PathResolver pathResolver) {
            this.commandName = commandName;
            this.pathResolver = pathResolver;
        }

        @Override
        public void execute(List<String> args, Environment env, PathResolver resolver, CommandRegistry registry) {
            try {
                Optional<Path> executable = pathResolver.findExecutable(commandName);
                if (!executable.isPresent()) {
                    System.out.println(commandName + ": command not found");
                    return;
                }

                // Explicitly use the resolved path to ensure we run what we found
                // Create a defensive copy of args to avoid modifying the list passed in (though ProcessBuilder copies anyway)
                List<String> processArgs = new ArrayList<>(args);

                // IMPORTANT: Replace the command name (arg 0) with the absolute path
                // to ensure ProcessBuilder doesn't do a secondary lookup that might fail or be ambiguous.
                processArgs.set(0, executable.get().toString());

                ProcessBuilder pb = new ProcessBuilder(processArgs);
                pb.directory(env.getCurrentDirectory().toFile());
                pb.inheritIO();

                Process process = pb.start();
                process.waitFor();
            } catch (IOException | InterruptedException e) {
                System.out.println(commandName + ": command not found");
            }
        }
    }

    static final class ExitCommand implements Command {
        @Override
        public void execute(List<String> args, Environment env, PathResolver resolver, CommandRegistry registry) {
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
        public void execute(List<String> args, Environment env, PathResolver resolver, CommandRegistry registry) {
            if (args.size() > 1) {
                System.out.println(args.stream().skip(1).collect(Collectors.joining(" ")));
            } else {
                System.out.println();
            }
        }
    }

    static final class TypeCommand implements Command {
        @Override
        public void execute(List<String> args, Environment env, PathResolver resolver, CommandRegistry registry) {
            if (args.size() < 2) return;
            String target = args.get(1);

            if (registry.isBuiltin(target)) {
                System.out.println(target + " is a shell builtin");
            } else {
                Optional<Path> path = resolver.findExecutable(target);
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
        public void execute(List<String> args, Environment env, PathResolver resolver, CommandRegistry registry) {
            System.out.println(env.getCurrentDirectory());
        }
    }

    static final class CdCommand implements Command {
        @Override
        public void execute(List<String> args, Environment env, PathResolver resolver, CommandRegistry registry) {
            if (args.size() < 2) return;

            String targetDir = args.get(1);
            String home = env.getHomeDirectory();

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
                path = env.getCurrentDirectory().resolve(path);
            }

            Path resolved = path.normalize();
            if (Files.exists(resolved) && Files.isDirectory(resolved)) {
                env.setCurrentDirectory(resolved);
            } else {
                System.out.println("cd: " + args.get(1) + ": No such file or directory");
            }
        }
    }
}