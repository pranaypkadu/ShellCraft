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
 * Interactive mini-shell (Java 8, single file).
 *
 * Preserved behavior:
 * - Prompt: "$ " before each command
 * - Tokenization with single quotes, double quotes (\" and \\), and backslash escaping
 * - Builtins: exit, echo, type, pwd, cd
 * - External commands resolved via PATH (verification) and executed in current directory
 * - Stdout redirection: ">" and "1>" (last operator+filename pair only), stderr not redirected
 * - Stderr redirection: "2>" (last operator+filename pair only), stdout not redirected (preserved from provided code)
 *
 * Added behavior (this stage):
 * - Stdout append redirection: ">>" and "1>>" (last operator+filename pair only)
 */
public class Main {

    public static void main(String[] args) {
        Environment env = new Environment();
        PathResolver resolver = new PathResolver(env);

        BuiltinRegistry builtins = new BuiltinRegistry(resolver);
        CommandFactory factory = new DefaultCommandFactory(builtins, resolver);

        Shell shell = new Shell(
                new BufferedReader(new InputStreamReader(System.in)),
                env,
                resolver,
                factory
        );
        shell.run();
    }

    // =========================================================================
    // REPL / Orchestration
    // =========================================================================

    static final class Shell {
        private static final String PROMPT = "$ ";

        private final BufferedReader reader;
        private final Environment env;
        private final PathResolver resolver;
        private final CommandFactory factory;

        Shell(BufferedReader reader, Environment env, PathResolver resolver, CommandFactory factory) {
            this.reader = reader;
            this.env = env;
            this.resolver = resolver;
            this.factory = factory;
        }

        void run() {
            System.out.print(PROMPT);
            try {
                String line;
                while ((line = reader.readLine()) != null) {
                    handle(line);
                    System.out.print(PROMPT);
                }
            } catch (IOException e) {
                // Preserve exact original message format (including the "+ ").
                System.err.println("Fatal I/O Error: + " + e.getMessage());
            }
        }

        private void handle(String line) {
            try {
                List<String> tokens = Tokenizer.tokenize(line);
                if (tokens.isEmpty()) return;

                ParsedCommand parsed = RedirectionParser.parse(tokens);
                if (parsed.args.isEmpty()) return;

                String name = parsed.args.get(0);
                ShellCommand cmd = factory.create(name, parsed.args);

                ExecutionContext ctx = new ExecutionContext(env, resolver, parsed.args, parsed.redirections);
                cmd.execute(ctx);
            } catch (Exception e) {
                // Preserve original behavior: swallow exceptions and print message to stdout if present.
                String msg = e.getMessage();
                if (msg != null && !msg.isEmpty()) {
                    System.out.println(msg);
                }
            }
        }
    }

    // =========================================================================
    // Tokenizer (Lexer)
    // =========================================================================

    static final class Tokenizer {

        private enum State {
            DEFAULT,
            ESCAPE,
            SINGLE_QUOTE,
            DOUBLE_QUOTE,
            DOUBLE_QUOTE_ESCAPE
        }

        private Tokenizer() {}

        static List<String> tokenize(String input) {
            List<String> tokens = new ArrayList<String>();
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
    // Parsing (redirection extraction)
    // =========================================================================

    enum RedirectMode {
        TRUNCATE,
        APPEND
    }

    static final class RedirectSpec {
        final Path path;
        final RedirectMode mode;

        RedirectSpec(Path path, RedirectMode mode) {
            this.path = path;
            this.mode = mode;
        }
    }

    static final class Redirections {
        final Optional<RedirectSpec> stdoutRedirect;
        final Optional<Path> stderrRedirect;

        Redirections(Optional<RedirectSpec> stdoutRedirect, Optional<Path> stderrRedirect) {
            this.stdoutRedirect = stdoutRedirect;
            this.stderrRedirect = stderrRedirect;
        }

        static Redirections none() {
            return new Redirections(Optional.<RedirectSpec>empty(), Optional.<Path>empty());
        }
    }

    static final class ParsedCommand {
        final List<String> args;
        final Redirections redirections;

        ParsedCommand(List<String> args, Redirections redirections) {
            this.args = Collections.unmodifiableList(new ArrayList<String>(args));
            this.redirections = redirections;
        }
    }

    static final class RedirectionParser {
        private static final String OP_GT = ">";
        private static final String OP_1GT = "1>";
        private static final String OP_2GT = "2>";
        private static final String OP_DGT = ">>";
        private static final String OP_1DGT = "1>>";

        private RedirectionParser() {}

        static ParsedCommand parse(List<String> tokens) {
            // Recognize ONLY when the final two tokens form: <op> <filename>.
            if (tokens.size() >= 2) {
                String op = tokens.get(tokens.size() - 2);
                String fileToken = tokens.get(tokens.size() - 1);

                if (OP_GT.equals(op) || OP_1GT.equals(op)) {
                    Path target = Paths.get(fileToken);
                    List<String> args = new ArrayList<String>(tokens.subList(0, tokens.size() - 2));
                    return new ParsedCommand(args, new Redirections(Optional.of(new RedirectSpec(target, RedirectMode.TRUNCATE)), Optional.<Path>empty()));
                }

                if (OP_DGT.equals(op) || OP_1DGT.equals(op)) {
                    Path target = Paths.get(fileToken);
                    List<String> args = new ArrayList<String>(tokens.subList(0, tokens.size() - 2));
                    return new ParsedCommand(args, new Redirections(Optional.of(new RedirectSpec(target, RedirectMode.APPEND)), Optional.<Path>empty()));
                }

                if (OP_2GT.equals(op)) {
                    Path target = Paths.get(fileToken);
                    List<String> args = new ArrayList<String>(tokens.subList(0, tokens.size() - 2));
                    return new ParsedCommand(args, new Redirections(Optional.<RedirectSpec>empty(), Optional.of(target)));
                }
            }
            return new ParsedCommand(tokens, Redirections.none());
        }
    }

    // =========================================================================
    // Environment / PATH resolution
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

        void setCurrentDirectory(Path p) {
            this.currentDirectory = p.toAbsolutePath().normalize();
        }

        String getenv(String key) {
            return System.getenv(key);
        }

        String getHome() {
            return getenv(ENV_HOME);
        }

        List<Path> getPathDirectories() {
            String pathEnv = getenv(ENV_PATH);
            if (pathEnv == null || pathEnv.isEmpty()) return new ArrayList<Path>();

            char sep = File.pathSeparatorChar;
            List<Path> out = new ArrayList<Path>();

            int start = 0;
            for (int i = 0, n = pathEnv.length(); i <= n; i++) {
                if (i == n || pathEnv.charAt(i) == sep) {
                    if (i > start) {
                        out.add(Paths.get(pathEnv.substring(start, i)));
                    }
                    start = i + 1;
                }
            }
            return out;
        }
    }

    static final class PathResolver {
        private final Environment env;

        PathResolver(Environment env) {
            this.env = env;
        }

        Optional<Path> findExecutable(String name) {
            if (containsSeparator(name)) {
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

        private static boolean containsSeparator(String s) {
            // Unix '/' and Windows '\' should both count, plus whatever File.separatorChar is.
            return s.indexOf('/') >= 0 || s.indexOf('\\') >= 0 || s.indexOf(File.separatorChar) >= 0;
        }
    }

    static final class ExecutionContext {
        final Environment env;
        final PathResolver resolver;
        final List<String> args;
        final Redirections redirections;

        ExecutionContext(Environment env, PathResolver resolver, List<String> args, Redirections redirections) {
            this.env = env;
            this.resolver = resolver;
            this.args = Collections.unmodifiableList(new ArrayList<String>(args));
            this.redirections = redirections;
        }
    }

    // =========================================================================
    // Redirection I/O utilities
    // =========================================================================

    static final class RedirectionIO {
        private RedirectionIO() {}

        static void touchTruncate(Path p) throws IOException {
            OutputStream os = Files.newOutputStream(
                    p,
                    StandardOpenOption.CREATE,
                    StandardOpenOption.TRUNCATE_EXISTING,
                    StandardOpenOption.WRITE
            );
            os.close();
        }

        static void touchCreateAppend(Path p) throws IOException {
            // Create if absent; do not truncate; safe for append redirection.
            OutputStream os = Files.newOutputStream(
                    p,
                    StandardOpenOption.CREATE,
                    StandardOpenOption.APPEND,
                    StandardOpenOption.WRITE
            );
            os.close();
        }
    }

    // =========================================================================
    // Output strategy (builtins)
    // =========================================================================

    interface OutputTarget extends AutoCloseable {
        PrintStream out();
        void close() throws IOException;
    }

    static final class OutputTargets {
        private OutputTargets() {}

        static OutputTarget stdout(Optional<RedirectSpec> redirect) throws IOException {
            if (!redirect.isPresent()) {
                return StdoutTarget.INSTANCE;
            }

            RedirectSpec spec = redirect.get();
            OutputStream os;
            if (spec.mode == RedirectMode.APPEND) {
                os = Files.newOutputStream(
                        spec.path,
                        StandardOpenOption.CREATE,
                        StandardOpenOption.APPEND,
                        StandardOpenOption.WRITE
                );
            } else {
                os = Files.newOutputStream(
                        spec.path,
                        StandardOpenOption.CREATE,
                        StandardOpenOption.TRUNCATE_EXISTING,
                        StandardOpenOption.WRITE
                );
            }
            return new FileTarget(new PrintStream(os));
        }
    }

    static final class FileTarget implements OutputTarget {
        private final PrintStream ps;

        FileTarget(PrintStream ps) {
            this.ps = ps;
        }

        public PrintStream out() {
            return ps;
        }

        public void close() {
            ps.close();
        }
    }

    static final class StdoutTarget implements OutputTarget {
        static final StdoutTarget INSTANCE = new StdoutTarget();
        private StdoutTarget() {}

        public PrintStream out() {
            return System.out;
        }

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

        public ShellCommand create(String name, List<String> args) {
            Optional<ShellCommand> b = builtins.lookup(name);
            if (b.isPresent()) return b.get();
            return new ExternalCommand(name, args, resolver);
        }
    }

    static final class BuiltinRegistry {
        private final Map<String, ShellCommand> map = new HashMap<String, ShellCommand>();

        BuiltinRegistry(PathResolver resolver) {
            map.put("exit", new ExitCommand());
            map.put("echo", new EchoCommand());
            map.put("pwd", new PwdCommand());
            map.put("cd", new CdCommand());
            map.put("type", new TypeCommand(this, resolver));
        }

        Optional<ShellCommand> lookup(String name) {
            return Optional.ofNullable(map.get(name));
        }

        boolean isBuiltin(String name) {
            return map.containsKey(name);
        }
    }

    static abstract class BuiltinCommand implements ShellCommand {
        public final void execute(ExecutionContext ctx) {
            // Important for 2>: create/truncate the file even if the builtin writes nothing to stderr.
            if (ctx.redirections.stderrRedirect.isPresent()) {
                try {
                    RedirectionIO.touchTruncate(ctx.redirections.stderrRedirect.get());
                } catch (IOException e) {
                    System.err.println("Redirection error: " + e.getMessage());
                    // Preserve failure behavior: still attempt to execute builtin using normal stdout behavior.
                }
            }

            try (OutputTarget target = OutputTargets.stdout(ctx.redirections.stdoutRedirect)) {
                executeBuiltin(ctx, target.out());
            } catch (IOException e) {
                System.err.println("Redirection error: " + e.getMessage());
            }
        }

        protected abstract void executeBuiltin(ExecutionContext ctx, PrintStream out);
    }

    // =========================================================================
    // External command
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

        public void execute(ExecutionContext ctx) {
            try {
                Optional<Path> ok = resolver.findExecutable(commandName);
                if (!ok.isPresent()) {
                    System.out.println(commandName + ": command not found");
                    return;
                }

                ProcessBuilder pb = new ProcessBuilder(new ArrayList<String>(originalArgs));
                pb.directory(ctx.env.getCurrentDirectory().toFile());

                pb.redirectInput(ProcessBuilder.Redirect.INHERIT);

                if (ctx.redirections.stdoutRedirect.isPresent()) {
                    RedirectSpec spec = ctx.redirections.stdoutRedirect.get();
                    if (spec.mode == RedirectMode.APPEND) {
                        RedirectionIO.touchCreateAppend(spec.path);
                        pb.redirectOutput(ProcessBuilder.Redirect.appendTo(spec.path.toFile()));
                    } else {
                        RedirectionIO.touchTruncate(spec.path);
                        pb.redirectOutput(spec.path.toFile());
                    }
                } else {
                    pb.redirectOutput(ProcessBuilder.Redirect.INHERIT);
                }

                if (ctx.redirections.stderrRedirect.isPresent()) {
                    Path err = ctx.redirections.stderrRedirect.get();
                    RedirectionIO.touchTruncate(err);
                    pb.redirectError(err.toFile());
                } else {
                    pb.redirectError(ProcessBuilder.Redirect.INHERIT);
                }

                Process p = pb.start();
                p.waitFor();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                System.out.println(commandName + ": command not found");
            } catch (IOException e) {
                System.out.println(commandName + ": command not found");
            }
        }
    }

    // =========================================================================
    // Builtins
    // =========================================================================

    static final class ExitCommand extends BuiltinCommand {
        protected void executeBuiltin(ExecutionContext ctx, PrintStream out) {
            int code = 0;
            if (ctx.args.size() > 1) {
                try {
                    code = Integer.parseInt(ctx.args.get(1));
                } catch (NumberFormatException ignored) {
                }
            }
            System.exit(code);
        }
    }

    static final class EchoCommand extends BuiltinCommand {
        protected void executeBuiltin(ExecutionContext ctx, PrintStream out) {
            if (ctx.args.size() <= 1) {
                out.println();
                return;
            }
            StringBuilder sb = new StringBuilder();
            for (int i = 1; i < ctx.args.size(); i++) {
                if (i > 1) sb.append(' ');
                sb.append(ctx.args.get(i));
            }
            out.println(sb.toString());
        }
    }

    static final class PwdCommand extends BuiltinCommand {
        protected void executeBuiltin(ExecutionContext ctx, PrintStream out) {
            out.println(ctx.env.getCurrentDirectory());
        }
    }

    static final class CdCommand extends BuiltinCommand {
        protected void executeBuiltin(ExecutionContext ctx, PrintStream out) {
            if (ctx.args.size() < 2) return;

            final String originalArg = ctx.args.get(1);
            String target = originalArg;

            String home = ctx.env.getHome();
            if ("~".equals(target)) {
                if (home == null) {
                    out.println("cd: HOME not set");
                    return;
                }
                target = home;
            } else if (target.startsWith("~/") || target.startsWith("~" + File.separator)) {
                if (home == null) {
                    out.println("cd: HOME not set");
                    return;
                }
                target = home + target.substring(1);
            }

            Path p = Paths.get(target);
            if (!p.isAbsolute()) {
                p = ctx.env.getCurrentDirectory().resolve(p);
            }
            Path resolved = p.normalize();

            if (Files.exists(resolved) && Files.isDirectory(resolved)) {
                ctx.env.setCurrentDirectory(resolved);
            } else {
                out.println("cd: " + originalArg + ": No such file or directory");
            }
        }
    }

    static final class TypeCommand extends BuiltinCommand {
        private final BuiltinRegistry builtins;
        private final PathResolver resolver;

        TypeCommand(BuiltinRegistry builtins, PathResolver resolver) {
            this.builtins = builtins;
            this.resolver = resolver;
        }

        protected void executeBuiltin(ExecutionContext ctx, PrintStream out) {
            if (ctx.args.size() < 2) return;
            String target = ctx.args.get(1);

            if (builtins.isBuiltin(target)) {
                out.println(target + " is a shell builtin");
                return;
            }

            Optional<Path> p = resolver.findExecutable(target);
            if (p.isPresent()) {
                out.println(target + " is " + p.get().toAbsolutePath());
            } else {
                out.println(target + ": not found");
            }
        }
    }
}
