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
 * Runs the interactive shell supporting quoted executables, built-in commands, and output redirection.
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
                // 1. Tokenize
                List<String> rawTokens = Tokenizer.tokenize(line);
                if (rawTokens.isEmpty()) return;

                // 2. Parse (Separate Redirection from Args)
                ParsedCommand parsed = CommandParser.parse(rawTokens);

                // 3. Resolve Command
                String commandName = parsed.getArgs().get(0);
                Command command = registry.getCommand(commandName)
                        .orElseGet(() -> new ExternalCommand(commandName));

                // 4. Build Context
                ExecutionContext context = new ExecutionContext(
                        parsed.getArgs(),
                        parsed.getRedirectionTarget(),
                        environment,
                        pathResolver,
                        registry
                );

                // 5. Execute
                command.execute(context);

            } catch (Exception e) {
                // Catch-all to prevent shell crash
                if (e.getMessage() != null && !e.getMessage().isEmpty()) {
                    System.out.println(e.getMessage());
                }
            }
        }
    }

    // =========================================================================
    // Parsing & Tokenization
    // =========================================================================

    /**
     * Represents a command after parsing logic has separated arguments from redirection directives.
     */
    static final class ParsedCommand {
        private final List<String> args;
        private final Optional<Path> redirectionTarget;

        ParsedCommand(List<String> args, Path redirectionTarget) {
            this.args = Collections.unmodifiableList(args);
            this.redirectionTarget = Optional.ofNullable(redirectionTarget);
        }

        List<String> getArgs() { return args; }
        Optional<Path> getRedirectionTarget() { return redirectionTarget; }
    }

    static final class CommandParser {
        private CommandParser() {}

        static ParsedCommand parse(List<String> tokens) {
            List<String> args = new ArrayList<>();
            Path redirectPath = null;

            for (int i = 0; i < tokens.size(); i++) {
                String token = tokens.get(i);
                // Handle > and 1> identically
                if ((">".equals(token) || "1>".equals(token)) && i + 1 < tokens.size()) {
                    redirectPath = Paths.get(tokens.get(i + 1));
                    i++; // Skip the filename token
                } else {
                    args.add(token);
                }
            }
            return new ParsedCommand(args, redirectPath);
        }
    }

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
    // Domain: Environment & Context
    // =========================================================================

    static final class Environment {
        private static final String PATH_VAR = "PATH";
        private static final String HOME_VAR = "HOME";
        private Path currentDirectory;

        Environment() {
            this.currentDirectory = Paths.get(System.getProperty("user.dir")).toAbsolutePath().normalize();
        }

        Path getCurrentDirectory() { return currentDirectory; }

        void setCurrentDirectory(Path path) {
            this.currentDirectory = path.toAbsolutePath().normalize();
        }

        String getEnv(String key) { return System.getenv(key); }

        List<Path> getPathDirectories() {
            String pathEnv = getEnv(PATH_VAR);
            if (pathEnv == null || pathEnv.isEmpty()) {
                return new ArrayList<>();
            }
            return Arrays.stream(pathEnv.split(Pattern.quote(File.pathSeparator)))
                    .map(Paths::get)
                    .collect(Collectors.toList());
        }

        String getHomeDirectory() { return getEnv(HOME_VAR); }
    }

    static final class PathResolver {
        private final Environment env;

        PathResolver(Environment env) { this.env = env; }

        Optional<Path> findExecutable(String name) {
            if (name.contains(File.separator) || name.contains("/")) {
                Path p = Paths.get(name);
                if (Files.isRegularFile(p) && Files.isExecutable(p)) {
                    return Optional.of(p);
                }
                return Optional.empty();
            }
            return env.getPathDirectories().stream()
                    .map(dir -> dir.resolve(name))
                    .filter(p -> Files.isRegularFile(p) && Files.isExecutable(p))
                    .findFirst();
        }
    }

    /**
     * Context encapsulation to reduce parameter coupling.
     */
    static final class ExecutionContext {
        private final List<String> args;
        private final Optional<Path> redirectionTarget;
        private final Environment env;
        private final PathResolver resolver;
        private final CommandRegistry registry;

        ExecutionContext(List<String> args, Optional<Path> redirectionTarget,
                         Environment env, PathResolver resolver, CommandRegistry registry) {
            this.args = args;
            this.redirectionTarget = redirectionTarget;
            this.env = env;
            this.resolver = resolver;
            this.registry = registry;
        }

        List<String> getArgs() { return args; }
        Optional<Path> getRedirectionTarget() { return redirectionTarget; }
        Environment getEnv() { return env; }
        PathResolver getResolver() { return resolver; }
        CommandRegistry getRegistry() { return registry; }
    }

    // =========================================================================
    // Command Pattern & Logic
    // =========================================================================

    interface Command {
        void execute(ExecutionContext ctx);
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

        boolean isBuiltin(String name) { return builtins.containsKey(name); }
    }

    /**
     * Abstract base for Builtins handling Stream abstraction (Template Method).
     */
    static abstract class BuiltinCommand implements Command {
        @Override
        public void execute(ExecutionContext ctx) {
            try (IOHandler io = IOHandler.create(ctx.getRedirectionTarget())) {
                executeBuiltin(ctx.getArgs(), io.getOut(), ctx);
            } catch (IOException e) {
                System.err.println("Redirection error: " + e.getMessage());
            }
        }

        protected abstract void executeBuiltin(List<String> args, PrintStream out, ExecutionContext ctx);
    }

    /**
     * Handles creation and cleanup of output streams (File vs System.out).
     */
    interface IOHandler extends AutoCloseable {
        PrintStream getOut();

        static IOHandler create(Optional<Path> target) throws IOException {
            if (target.isPresent()) {
                // Redirect to file (Overwrite/Create)
                File file = target.get().toFile();
                // Ensure parent exists? Standard shell usually fails if parent dir doesn't exist,
                // but Java Files.newOutputStream might want control. We stick to basic file creation.
                return new FileIOHandler(new PrintStream(Files.newOutputStream(
                        file.toPath(),
                        StandardOpenOption.CREATE,
                        StandardOpenOption.TRUNCATE_EXISTING,
                        StandardOpenOption.WRITE)));
            } else {
                // Standard Output
                return new StdIOHandler();
            }
        }
    }

    static class FileIOHandler implements IOHandler {
        private final PrintStream stream;
        FileIOHandler(PrintStream stream) { this.stream = stream; }
        @Override public PrintStream getOut() { return stream; }
        @Override public void close() { stream.close(); }
    }

    static class StdIOHandler implements IOHandler {
        @Override public PrintStream getOut() { return System.out; }
        @Override public void close() { /* Do not close System.out */ }
    }

    // =========================================================================
    // External Command
    // =========================================================================

    static final class ExternalCommand implements Command {
        private final String commandName;

        ExternalCommand(String commandName) {
            this.commandName = commandName;
        }

        @Override
        public void execute(ExecutionContext ctx) {
            try {
                Optional<Path> executable = ctx.getResolver().findExecutable(commandName);
                if (!executable.isPresent()) {
                    System.out.println(commandName + ": command not found");
                    return;
                }

                List<String> processArgs = new ArrayList<>(ctx.getArgs());
                // Replace arg[0] with absolute path to be safe
                processArgs.set(0, executable.get().toString());

                ProcessBuilder pb = new ProcessBuilder(processArgs);
                pb.directory(ctx.getEnv().getCurrentDirectory().toFile());

                // Handle Redirection
                if (ctx.getRedirectionTarget().isPresent()) {
                    pb.redirectOutput(ctx.getRedirectionTarget().get().toFile());
                } else {
                    pb.redirectOutput(ProcessBuilder.Redirect.INHERIT);
                }

                // Requirement: Error Output Is Not Redirected -> Always Inherit/System.err
                pb.redirectError(ProcessBuilder.Redirect.INHERIT);

                // Input is usually inherited in shells unless piped
                pb.redirectInput(ProcessBuilder.Redirect.INHERIT);

                Process process = pb.start();
                process.waitFor();
            } catch (IOException | InterruptedException e) {
                System.out.println(commandName + ": command not found");
            }
        }
    }

    // =========================================================================
    // Builtin Commands
    // =========================================================================

    static final class ExitCommand extends BuiltinCommand {
        @Override
        protected void executeBuiltin(List<String> args, PrintStream out, ExecutionContext ctx) {
            int code = 0;
            if (args.size() > 1) {
                try {
                    code = Integer.parseInt(args.get(1));
                } catch (NumberFormatException ignored) {}
            }
            System.exit(code);
        }
    }

    static final class EchoCommand extends BuiltinCommand {
        @Override
        protected void executeBuiltin(List<String> args, PrintStream out, ExecutionContext ctx) {
            if (args.size() > 1) {
                out.println(args.stream().skip(1).collect(Collectors.joining(" ")));
            } else {
                out.println();
            }
        }
    }

    static final class TypeCommand extends BuiltinCommand {
        @Override
        protected void executeBuiltin(List<String> args, PrintStream out, ExecutionContext ctx) {
            if (args.size() < 2) return;
            String target = args.get(1);

            if (ctx.getRegistry().isBuiltin(target)) {
                out.println(target + " is a shell builtin");
            } else {
                Optional<Path> path = ctx.getResolver().findExecutable(target);
                if (path.isPresent()) {
                    out.println(target + " is " + path.get().toAbsolutePath());
                } else {
                    out.println(target + ": not found");
                }
            }
        }
    }

    static final class PwdCommand extends BuiltinCommand {
        @Override
        protected void executeBuiltin(List<String> args, PrintStream out, ExecutionContext ctx) {
            out.println(ctx.getEnv().getCurrentDirectory());
        }
    }

    static final class CdCommand extends BuiltinCommand {
        @Override
        protected void executeBuiltin(List<String> args, PrintStream out, ExecutionContext ctx) {
            if (args.size() < 2) return;

            String targetDir = args.get(1);
            String home = ctx.getEnv().getHomeDirectory();

            if (targetDir.equals("~")) {
                if (home == null) {
                    out.println("cd: HOME not set");
                    return;
                }
                targetDir = home;
            } else if (targetDir.startsWith("~" + File.separator)) {
                if (home == null) {
                    out.println("cd: HOME not set");
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
                out.println("cd: " + args.get(1) + ": No such file or directory");
            }
        }
    }
}
