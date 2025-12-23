import java.io.BufferedReader;
import java.io.File;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.io.PrintStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * Main Entry Point.
 *
 * Runs the interactive shell supporting quoted executables, built-in commands,
 * and standard output redirection (> and 1>).
 */
public class Main {

    public static void main(String[] args) {
        // Dependency Injection / Composition Root
        final Environment environment = new Environment();
        final PathResolver pathResolver = new PathResolver(environment);
        final CommandRegistry registry = new CommandRegistry();
        final CommandParser parser = new CommandParser();

        final Shell shell = new Shell(environment, registry, pathResolver, parser);
        shell.run();
    }

    // =========================================================================
    // Core Logic: Shell Orchestrator
    // =========================================================================

    static final class Shell {
        private final Environment environment;
        private final CommandRegistry registry;
        private final PathResolver pathResolver;
        private final CommandParser parser;

        Shell(Environment environment, CommandRegistry registry, PathResolver pathResolver, CommandParser parser) {
            this.environment = environment;
            this.registry = registry;
            this.pathResolver = pathResolver;
            this.parser = parser;
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
                // 1. Tokenize (Lexical Analysis)
                List<String> rawTokens = Tokenizer.tokenize(line);
                if (rawTokens.isEmpty()) return;

                // 2. Parse (Grammar & Redirection extraction)
                ParsedCommand parsed = parser.parse(rawTokens);

                // 3. Resolve Command Strategy
                String commandName = parsed.getCommandName();
                Command command = registry.getCommand(commandName)
                        .orElseGet(() -> new ExternalCommand(commandName));

                // 4. Build Context
                CommandContext context = new CommandContext(
                        parsed.getArguments(),
                        parsed.getRedirectOutput(),
                        environment,
                        pathResolver,
                        registry
                );

                // 5. Execute
                command.execute(context);

            } catch (Exception e) {
                if (e.getMessage() != null && !e.getMessage().isEmpty()) {
                    System.out.println(e.getMessage());
                }
            }
        }
    }

    // =========================================================================
    // Domain: Environment & State
    // =========================================================================

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

    static final class PathResolver {
        private final Environment env;

        PathResolver(Environment env) {
            this.env = env;
        }

        Optional<Path> findExecutable(String name) {
            // Case 1: Explicit path
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
    // Parsing: Tokenizer & Command Parser
    // =========================================================================

    /**
     * Handles splitting raw strings into tokens, respecting quotes.
     */
    static final class Tokenizer {
        private enum State { DEFAULT, SINGLE_QUOTE, DOUBLE_QUOTE, DOUBLE_QUOTE_ESCAPE, ESCAPE }

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
                        if (c == '\'') state = State.DEFAULT;
                        else currentToken.append(c);
                        break;
                    case DOUBLE_QUOTE:
                        if (c == '"') state = State.DEFAULT;
                        else if (c == '\\') state = State.DOUBLE_QUOTE_ESCAPE;
                        else currentToken.append(c);
                        break;
                    case DOUBLE_QUOTE_ESCAPE:
                        if (c == '\\' || c == '"') currentToken.append(c);
                        else { currentToken.append('\\'); currentToken.append(c); }
                        state = State.DOUBLE_QUOTE;
                        break;
                }
            }
            if (inToken) tokens.add(currentToken.toString());
            return tokens;
        }
    }

    /**
     * Intermediate representation of a parsed command line.
     */
    static final class ParsedCommand {
        private final String commandName;
        private final List<String> arguments;
        private final Path redirectOutput; // null if no redirection

        ParsedCommand(String commandName, List<String> arguments, Path redirectOutput) {
            this.commandName = commandName;
            this.arguments = arguments;
            this.redirectOutput = redirectOutput;
        }

        String getCommandName() { return commandName; }
        List<String> getArguments() { return arguments; }
        Optional<Path> getRedirectOutput() { return Optional.ofNullable(redirectOutput); }
    }

    /**
     * Parses the token stream to identify redirection operators (> or 1>).
     */
    static final class CommandParser {

        ParsedCommand parse(List<String> rawTokens) {
            if (rawTokens.isEmpty()) {
                throw new IllegalArgumentException("Tokens cannot be empty");
            }

            List<String> cleanArgs = new ArrayList<>();
            Path redirectPath = null;
            String commandName = rawTokens.get(0);

            // Add command name to args list (standard convention)
            cleanArgs.add(commandName);

            for (int i = 1; i < rawTokens.size(); i++) {
                String token = rawTokens.get(i);

                // Check for redirection operators
                if (">".equals(token) || "1>".equals(token)) {
                    if (i + 1 < rawTokens.size()) {
                        redirectPath = Paths.get(rawTokens.get(i + 1));
                        i++; // Skip the filename token
                    }
                } else {
                    cleanArgs.add(token);
                }
            }

            return new ParsedCommand(commandName, Collections.unmodifiableList(cleanArgs), redirectPath);
        }
    }

    // =========================================================================
    // Command Pattern & Execution Context
    // =========================================================================

    static final class CommandContext {
        private final List<String> args;
        private final Optional<Path> redirectOutput;
        private final Environment env;
        private final PathResolver resolver;
        private final CommandRegistry registry;

        CommandContext(List<String> args, Optional<Path> redirectOutput, Environment env, PathResolver resolver, CommandRegistry registry) {
            this.args = args;
            this.redirectOutput = redirectOutput;
            this.env = env;
            this.resolver = resolver;
            this.registry = registry;
        }

        List<String> getArgs() { return args; }
        Optional<Path> getRedirectOutput() { return redirectOutput; }
        Environment getEnv() { return env; }
        PathResolver getResolver() { return resolver; }
        CommandRegistry getRegistry() { return registry; }
    }

    @FunctionalInterface
    interface Command {
        void execute(CommandContext ctx);
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
     * Base class for built-ins. Handles System.out redirection transparently.
     * Template Method Pattern.
     */
    static abstract class AbstractBuiltinCommand implements Command {
        @Override
        public void execute(CommandContext ctx) {
            PrintStream originalOut = System.out;
            PrintStream fileOut = null;

            try {
                // If redirection is requested, swap System.out
                if (ctx.getRedirectOutput().isPresent()) {
                    Path target = ctx.getRedirectOutput().get();
                    // Create/Truncate
                    OutputStream fos = Files.newOutputStream(target,
                            StandardOpenOption.CREATE,
                            StandardOpenOption.TRUNCATE_EXISTING,
                            StandardOpenOption.WRITE);

                    fileOut = new PrintStream(fos);
                    System.setOut(fileOut);
                }

                // Delegate to specific command logic
                run(ctx);

            } catch (IOException e) {
                System.err.println("Redirection error: " + e.getMessage());
            } finally {
                // Restore System.out
                if (fileOut != null) {
                    System.setOut(originalOut);
                    fileOut.close();
                }
            }
        }

        protected abstract void run(CommandContext ctx);
    }

    static final class ExternalCommand implements Command {
        private final String commandName;

        ExternalCommand(String commandName) {
            this.commandName = commandName;
        }

        @Override
        public void execute(CommandContext ctx) {
            try {
                Optional<Path> executable = ctx.getResolver().findExecutable(commandName);
                if (!executable.isPresent()) {
                    System.out.println(commandName + ": command not found");
                    return;
                }

                List<String> processArgs = new ArrayList<>(ctx.getArgs());
                // Use absolute path to avoid ambiguity
                processArgs.set(0, executable.get().toString());

                ProcessBuilder pb = new ProcessBuilder(processArgs);
                pb.directory(ctx.getEnv().getCurrentDirectory().toFile());

                // Default: Inherit everything (stdin, stdout, stderr)
                pb.inheritIO();

                // Override stdout if redirection is requested
                if (ctx.getRedirectOutput().isPresent()) {
                    pb.redirectOutput(ctx.getRedirectOutput().get().toFile());
                    // Stderr remains inherited (printed to terminal) as required
                }

                Process process = pb.start();
                process.waitFor();
            } catch (IOException | InterruptedException e) {
                System.out.println(commandName + ": command not found");
            }
        }
    }

    static final class ExitCommand extends AbstractBuiltinCommand {
        @Override
        protected void run(CommandContext ctx) {
            int code = 0;
            if (ctx.getArgs().size() > 1) {
                try {
                    code = Integer.parseInt(ctx.getArgs().get(1));
                } catch (NumberFormatException ignored) {}
            }
            // If exiting, we don't worry about restoring System.out
            System.exit(code);
        }
    }

    static final class EchoCommand extends AbstractBuiltinCommand {
        @Override
        protected void run(CommandContext ctx) {
            if (ctx.getArgs().size() > 1) {
                System.out.println(ctx.getArgs().stream().skip(1).collect(Collectors.joining(" ")));
            } else {
                System.out.println();
            }
        }
    }

    static final class TypeCommand extends AbstractBuiltinCommand {
        @Override
        protected void run(CommandContext ctx) {
            List<String> args = ctx.getArgs();
            if (args.size() < 2) return;
            String target = args.get(1);

            if (ctx.getRegistry().isBuiltin(target)) {
                System.out.println(target + " is a shell builtin");
            } else {
                Optional<Path> path = ctx.getResolver().findExecutable(target);
                if (path.isPresent()) {
                    System.out.println(target + " is " + path.get().toAbsolutePath());
                } else {
                    System.out.println(target + ": not found");
                }
            }
        }
    }

    static final class PwdCommand extends AbstractBuiltinCommand {
        @Override
        protected void run(CommandContext ctx) {
            System.out.println(ctx.getEnv().getCurrentDirectory());
        }
    }

    static final class CdCommand extends AbstractBuiltinCommand {
        @Override
        protected void run(CommandContext ctx) {
            List<String> args = ctx.getArgs();
            if (args.size() < 2) return;

            String targetDir = args.get(1);
            String home = ctx.getEnv().getHomeDirectory();

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
                path = ctx.getEnv().getCurrentDirectory().resolve(path);
            }

            Path resolved = path.normalize();
            if (Files.exists(resolved) && Files.isDirectory(resolved)) {
                ctx.getEnv().setCurrentDirectory(resolved);
            } else {
                System.out.println("cd: " + args.get(1) + ": No such file or directory");
            }
        }
    }
}