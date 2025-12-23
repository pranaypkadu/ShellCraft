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
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Main Entry Point.
 *
 * Interactive mini-shell supporting:
 * - Tokenization with quotes/escapes
 * - Builtins: exit, echo, type, pwd, cd
 * - External command execution via PATH resolution
 * - Stdout redirection via ">" and "1>" (last operator+filename pair only)
 */
public class Main {

    public static void main(String[] args) {
        // Composition Root
        Environment environment = new Environment();
        PathResolver pathResolver = new PathResolver(environment);
        BuiltinRegistry builtins = new BuiltinRegistry();
        CommandFactory commandFactory = new DefaultCommandFactory(builtins, pathResolver);

        Shell shell = new Shell(
                new BufferedReader(new InputStreamReader(System.in)),
                environment,
                commandFactory
        );
        shell.run();
    }

    // =========================================================================
    // Shell Orchestrator (REPL)
    // =========================================================================

    static final class Shell {
        private static final String PROMPT = "$ ";

        private final BufferedReader reader;
        private final Environment environment;
        private final CommandFactory commandFactory;

        Shell(BufferedReader reader, Environment environment, CommandFactory commandFactory) {
            this.reader = reader;
            this.environment = environment;
            this.commandFactory = commandFactory;
        }

        void run() {
            System.out.print(PROMPT);
            try {
                String line;
                while ((line = reader.readLine()) != null) {
                    handleLine(line);
                    System.out.print(PROMPT);
                }
            } catch (IOException e) {
                System.err.println("Fatal I/O Error: + " + e.getMessage());
            }
        }

        private void handleLine(String line) {
            try {
                List<String> tokens = Tokenizer.tokenize(line);
                if (tokens.isEmpty()) return;

                ParsedCommand parsed = RedirectionParser.parse(tokens);
                if (parsed.args.isEmpty()) return; // keep shell resilient on malformed input

                ShellCommand cmd = commandFactory.create(parsed.args.get(0), parsed.args);
                ExecutionContext ctx = new ExecutionContext(environment, parsed.args, parsed.stdoutRedirect);

                cmd.execute(ctx);
            } catch (Exception e) {
                // Catch-all: shell must not crash.
                String msg = e.getMessage();
                if (msg != null && !msg.isEmpty()) {
                    System.out.println(msg);
                }
            }
        }
    }

    // =========================================================================
    // Tokenization (Lexer)
    // =========================================================================

    static final class Tokenizer {

        private enum State {
            DEFAULT,
            ESCAPE,
            SINGLE_QUOTE,
            DOUBLE_QUOTE,
            DOUBLE_QUOTE_ESCAPE
        }

        private Tokenizer() {
        }

        static List<String> tokenize(String input) {
            List<String> tokens = new ArrayList<>();
            StringBuilder current = new StringBuilder();

            State state = State.DEFAULT;
            boolean inToken = false;

            for (int i = 0, n = input.length(); i < n; i++) {
                char c = input.charAt(i);

                switch (state) {
                    case DEFAULT:
                        if (Character.isWhitespace(c)) {
                            if (inToken) {
                                tokens.add(current.toString());
                                current.setLength(0);
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
                            current.append(c);
                            inToken = true;
                        }
                        break;

                    case ESCAPE:
                        current.append(c);
                        state = State.DEFAULT;
                        break;

                    case SINGLE_QUOTE:
                        if (c == '\'') {
                            state = State.DEFAULT;
                        } else {
                            current.append(c);
                        }
                        break;

                    case DOUBLE_QUOTE:
                        if (c == '"') {
                            state = State.DEFAULT;
                        } else if (c == '\\') {
                            state = State.DOUBLE_QUOTE_ESCAPE;
                        } else {
                            current.append(c);
                        }
                        break;

                    case DOUBLE_QUOTE_ESCAPE:
                        if (c == '\\' || c == '"') {
                            current.append(c);
                        } else {
                            current.append('\\');
                            current.append(c);
                        }
                        state = State.DOUBLE_QUOTE;
                        break;

                    default:
                        // unreachable
                        state = State.DEFAULT;
                        break;
                }
            }

            if (inToken) {
                tokens.add(current.toString());
            }
            return tokens;
        }
    }

    // =========================================================================
    // Parsing (Redirection extraction)
    // =========================================================================

    static final class ParsedCommand {
        final List<String> args;
        final Optional<Path> stdoutRedirect;

        ParsedCommand(List<String> args, Optional<Path> stdoutRedirect) {
            this.args = Collections.unmodifiableList(new ArrayList<>(args));
            this.stdoutRedirect = stdoutRedirect;
        }
    }

    static final class RedirectionParser {
        private static final String OP_GT = ">";
        private static final String OP_FD1_GT = "1>";

        private RedirectionParser() {
        }

        /**
         * Recognizes redirection only when provided as the last operator+filename pair:
         *   command args... > file
         *   command args... 1> file
         *
         * Otherwise, '>' and '1>' remain normal arguments.
         */
        static ParsedCommand parse(List<String> tokens) {
            if (tokens.size() >= 2) {
                String op = tokens.get(tokens.size() - 2);
                if (OP_GT.equals(op) || OP_FD1_GT.equals(op)) {
                    String fileToken = tokens.get(tokens.size() - 1);
                    Path target = Paths.get(fileToken);
                    List<String> args = tokens.subList(0, tokens.size() - 2);
                    return new ParsedCommand(args, Optional.of(target));
                }
            }
            return new ParsedCommand(tokens, Optional.<Path>empty());
        }
    }

    // =========================================================================
    // Domain (Environment / Context)
    // =========================================================================

    static final class Environment {
        private static final String ENV_PATH = "PATH";
        private static final String ENV_HOME = "HOME";

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

        String getenv(String key) {
            return System.getenv(key);
        }

        String getHome() {
            return getenv(ENV_HOME);
        }

        List<Path> getPathDirectories() {
            String pathEnv = getenv(ENV_PATH);
            if (pathEnv == null || pathEnv.isEmpty()) {
                return new ArrayList<>();
            }

            // Fast split on platform path separator without regex.
            char sep = File.pathSeparatorChar;
            List<Path> result = new ArrayList<>();

            int start = 0;
            for (int i = 0, n = pathEnv.length(); i <= n; i++) {
                if (i == n || pathEnv.charAt(i) == sep) {
                    if (i > start) {
                        result.add(Paths.get(pathEnv.substring(start, i)));
                    } else {
                        // empty segment -> ignore (consistent with "do nothing")
                    }
                    start = i + 1;
                }
            }
            return result;
        }
    }

    static final class ExecutionContext {
        final Environment env;
        final List<String> args;
        final Optional<Path> stdoutRedirect;

        ExecutionContext(Environment env, List<String> args, Optional<Path> stdoutRedirect) {
            this.env = env;
            this.args = Collections.unmodifiableList(new ArrayList<>(args));
            this.stdoutRedirect = stdoutRedirect;
        }
    }

    // =========================================================================
    // Output target strategy (builtins)
    // =========================================================================

    interface OutputTarget extends AutoCloseable {
        PrintStream out();

        @Override
        void close() throws IOException;
    }

    static final class OutputTargets {
        private OutputTargets() {
        }

        static OutputTarget forStdoutRedirection(Optional<Path> redirect) throws IOException {
            if (redirect.isPresent()) {
                Path p = redirect.get();
                OutputStream os = Files.newOutputStream(
                        p,
                        StandardOpenOption.CREATE,
                        StandardOpenOption.TRUNCATE_EXISTING,
                        StandardOpenOption.WRITE
                );
                return new FileOutputTarget(new PrintStream(os));
            }
            return StdoutOutputTarget.INSTANCE;
        }
    }

    static final class FileOutputTarget implements OutputTarget {
        private final PrintStream ps;

        FileOutputTarget(PrintStream ps) {
            this.ps = ps;
        }

        @Override
        public PrintStream out() {
            return ps;
        }

        @Override
        public void close() {
            ps.close();
        }
    }

    static final class StdoutOutputTarget implements OutputTarget {
        static final StdoutOutputTarget INSTANCE = new StdoutOutputTarget();

        private StdoutOutputTarget() {
        }

        @Override
        public PrintStream out() {
            return System.out;
        }

        @Override
        public void close() {
            // Never close System.out
        }
    }

    // =========================================================================
    // Command Pattern + Factory/Registry
    // =========================================================================

    interface ShellCommand {
        void execute(ExecutionContext ctx);
    }

    interface CommandFactory {
        ShellCommand create(String name, List<String> args);
    }

    static final class DefaultCommandFactory implements CommandFactory {
        private final BuiltinRegistry builtins;
        private final PathResolver resolver;

        DefaultCommandFactory(BuiltinRegistry builtins, PathResolver resolver) {
            this.builtins = builtins;
            this.resolver = resolver;
        }

        @Override
        public ShellCommand create(String name, List<String> args) {
            Optional<ShellCommand> builtin = builtins.lookup(name);
            if (builtin.isPresent()) return builtin.get();
            return new ExternalCommand(name, args, resolver);
        }
    }

    static final class BuiltinRegistry {
        private final Map<String, ShellCommand> map = new HashMap<String, ShellCommand>();

        BuiltinRegistry() {
            map.put("exit", new ExitCommand());
            map.put("echo", new EchoCommand());
            map.put("type", new TypeCommand(this));
            map.put("pwd", new PwdCommand());
            map.put("cd", new CdCommand());
        }

        Optional<ShellCommand> lookup(String name) {
            return Optional.ofNullable(map.get(name));
        }

        boolean isBuiltin(String name) {
            return map.containsKey(name);
        }
    }

    static abstract class BuiltinCommand implements ShellCommand {
        @Override
        public final void execute(ExecutionContext ctx) {
            try (OutputTarget target = OutputTargets.forStdoutRedirection(ctx.stdoutRedirect)) {
                executeBuiltin(ctx, target.out());
            } catch (IOException e) {
                System.err.println("Redirection error: " + e.getMessage());
            }
        }

        protected abstract void executeBuiltin(ExecutionContext ctx, PrintStream out);
    }

    // =========================================================================
    // PATH Resolution
    // =========================================================================

    static final class PathResolver {
        private final Environment env;

        PathResolver(Environment env) {
            this.env = env;
        }

        Optional<Path> findExecutable(String name) {
            // Contains a separator => treat as path (relative to shell cwd if not absolute).
            if (containsPathSeparator(name)) {
                Path p = Paths.get(name);
                if (!p.isAbsolute()) {
                    p = env.getCurrentDirectory().resolve(p).normalize();
                }
                if (Files.isRegularFile(p) && Files.isExecutable(p)) {
                    return Optional.of(p);
                }
                return Optional.empty();
            }

            List<Path> dirs = env.getPathDirectories();
            for (int i = 0; i < dirs.size(); i++) {
                Path candidate = dirs.get(i).resolve(name);
                if (Files.isRegularFile(candidate) && Files.isExecutable(candidate)) {
                    return Optional.of(candidate);
                }
            }
            return Optional.empty();
        }

        private static boolean containsPathSeparator(String s) {
            // Unix: '/', Windows: '\' and '/'.
            return s.indexOf('/') >= 0 || s.indexOf(File.separatorChar) >= 0;
        }
    }

    // =========================================================================
    // External command execution
    // =========================================================================

    static final class ExternalCommand implements ShellCommand {
        private final String commandName;
        private final List<String> originalArgs;
        private final PathResolver resolver;

        ExternalCommand(String commandName, List<String> originalArgs, PathResolver resolver) {
            this.commandName = commandName;
            this.originalArgs = originalArgs;
            this.resolver = resolver;
        }

        @Override
        public void execute(ExecutionContext ctx) {
            try {
                Optional<Path> executable = resolver.findExecutable(commandName);
                if (!executable.isPresent()) {
                    System.out.println(commandName + ": command not found");
                    return;
                }

                ProcessBuilder pb = new ProcessBuilder(new ArrayList<String>(originalArgs));
                pb.directory(ctx.env.getCurrentDirectory().toFile());

                // stdin & stderr must remain on terminal
                pb.redirectInput(ProcessBuilder.Redirect.INHERIT);
                pb.redirectError(ProcessBuilder.Redirect.INHERIT);

                if (ctx.stdoutRedirect.isPresent()) {
                    Path out = ctx.stdoutRedirect.get();

                    // Ensure "create if absent" and "overwrite/truncate if present".
                    // (Also avoids platform quirks by truncating up-front.)
                    Files.newOutputStream(out,
                                    StandardOpenOption.CREATE,
                                    StandardOpenOption.TRUNCATE_EXISTING,
                                    StandardOpenOption.WRITE)
                            .close();

                    pb.redirectOutput(out.toFile());
                } else {
                    pb.redirectOutput(ProcessBuilder.Redirect.INHERIT);
                }

                Process p = pb.start();
                p.waitFor();
            } catch (InterruptedException ie) {
                Thread.currentThread().interrupt();
                System.out.println(commandName + ": command not found");
            } catch (IOException ioe) {
                System.out.println(commandName + ": command not found");
            }
        }
    }

    // =========================================================================
    // Builtins
    // =========================================================================

    static final class ExitCommand extends BuiltinCommand {
        @Override
        protected void executeBuiltin(ExecutionContext ctx, PrintStream out) {
            int code = 0;
            if (ctx.args.size() > 1) {
                try {
                    code = Integer.parseInt(ctx.args.get(1));
                } catch (NumberFormatException ignored) {
                    // preserve behavior
                }
            }
            System.exit(code);
        }
    }

    static final class EchoCommand extends BuiltinCommand {
        @Override
        protected void executeBuiltin(ExecutionContext ctx, PrintStream out) {
            if (ctx.args.size() <= 1) {
                out.println();
                return;
            }
            // Manual join (avoid streams)
            StringBuilder sb = new StringBuilder();
            for (int i = 1; i < ctx.args.size(); i++) {
                if (i > 1) sb.append(' ');
                sb.append(ctx.args.get(i));
            }
            out.println(sb.toString());
        }
    }

    static final class TypeCommand extends BuiltinCommand {
        private final BuiltinRegistry builtins;

        TypeCommand(BuiltinRegistry builtins) {
            this.builtins = builtins;
        }

        @Override
        protected void executeBuiltin(ExecutionContext ctx, PrintStream out) {
            if (ctx.args.size() < 2) return;
            String target = ctx.args.get(1);

            if (builtins.isBuiltin(target)) {
                out.println(target + " is a shell builtin");
                return;
            }

            // Resolve via PATH (and paths with separators) exactly like external execution checks.
            PathResolver resolver = new PathResolver(ctx.env);
            Optional<Path> p = resolver.findExecutable(target);
            if (p.isPresent()) {
                out.println(target + " is " + p.get().toAbsolutePath());
            } else {
                out.println(target + ": not found");
            }
        }
    }

    static final class PwdCommand extends BuiltinCommand {
        @Override
        protected void executeBuiltin(ExecutionContext ctx, PrintStream out) {
            out.println(ctx.env.getCurrentDirectory());
        }
    }

    static final class CdCommand extends BuiltinCommand {
        @Override
        protected void executeBuiltin(ExecutionContext ctx, PrintStream out) {
            if (ctx.args.size() < 2) return;

            final String originalToken = ctx.args.get(1);
            String targetDir = originalToken;

            String home = ctx.env.getHome();

            if ("~".equals(targetDir)) {
                if (home == null) {
                    out.println("cd: HOME not set");
                    return;
                }
                targetDir = home;
            } else if (targetDir.startsWith("~" + File.separator) || targetDir.startsWith("~/")) {
                if (home == null) {
                    out.println("cd: HOME not set");
                    return;
                }
                targetDir = home + targetDir.substring(1);
            }

            Path p = Paths.get(targetDir);
            if (!p.isAbsolute()) {
                p = ctx.env.getCurrentDirectory().resolve(p);
            }

            Path resolved = p.normalize();
            if (Files.exists(resolved) && Files.isDirectory(resolved)) {
                ctx.env.setCurrentDirectory(resolved);
            } else {
                out.println("cd: " + originalToken + ": No such file or directory");
            }
        }
    }
}
