import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.PipedInputStream;
import java.io.PipedOutputStream;
import java.io.PrintStream;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;
import java.util.concurrent.TimeUnit;

/**
 * Interactive mini-shell (single-file).
 *
 * Modified to support pipelines with built-in commands.
 */
public class Main {
    static final String PROMPT = "$ ";

    public static void main(String[] args) {
        // Single shared runtime environment for cwd + HOME expansion.
        Environment env = ShellRuntime.env;
        var resolver = new PathResolver(env);

        var builtins = new BuiltinRegistry(resolver);
        var factory = new DefaultCommandFactory(builtins, resolver);

        CompletionEngine completer = new CommandNameCompleter(builtins, resolver);
        var input = new InteractiveInput(System.in, completer, PROMPT);

        var shell = new Shell(
                input,
                env,
                resolver,
                factory,
                builtins, // Pass registry to shell for pipeline resolution
                new CommandLineParser(),
                PROMPT
        );
        shell.run();
    }

    // =========================================================================
    // REPL / Orchestration
    // =========================================================================

    static final class Shell {
        private final InteractiveInput input;
        @SuppressWarnings("unused")
        private final Environment env;
        @SuppressWarnings("unused")
        private final PathResolver resolver;
        private final CommandFactory factory;
        private final BuiltinRegistry builtins;
        private final CommandLineParser parser;
        private final String prompt;

        Shell(InteractiveInput input,
              Environment env,
              PathResolver resolver,
              CommandFactory factory,
              BuiltinRegistry builtins,
              CommandLineParser parser,
              String prompt) {
            this.input = input;
            this.env = env;
            this.resolver = resolver;
            this.factory = factory;
            this.builtins = builtins;
            this.parser = parser;
            this.prompt = prompt;
        }

        void run() {
            System.out.print(prompt);
            try {
                String line;
                while ((line = input.readLine()) != null) {
                    handle(line);
                    System.out.print(prompt);
                }
            } catch (IOException e) {
                // Preserve exact original message format (including the "+ ").
                System.err.println("Fatal I/O Error: + " + e.getMessage());
            } finally {
                input.close();
            }
        }

        private void handle(String line) {
            try {
                List<String> tokens = Tokenizer.tokenize(line);
                if (tokens.isEmpty()) return;

                ParsedLine parsed = parser.parse(tokens);

                // Default context uses System streams
                ExecutionContext rootCtx = ExecutionContext.system();

                switch (parsed) {
                    case ParsedLine.Simple simple -> {
                        CommandLine cmdLine = simple.line();
                        if (cmdLine.args().isEmpty()) return;
                        String name = cmdLine.args().get(0);
                        ShellCommand cmd = factory.create(name, cmdLine.args());
                        ExecutionContext ctx = rootCtx.withArgsAndRedirs(cmdLine.args(), cmdLine.redirections());
                        cmd.execute(ctx);
                    }
                    case ParsedLine.Pipeline pipe -> {
                        if (pipe.leftArgs().isEmpty() || pipe.rightArgs().isEmpty()) return;
                        ShellCommand cmd = new PipelineCommand(
                                pipe.leftArgs(),
                                pipe.rightArgs(),
                                pipe.redirections(),
                                resolver,
                                builtins
                        );
                        // ExecutionContext still used to keep the pattern consistent
                        cmd.execute(rootCtx.withArgsAndRedirs(List.of(), Redirections.none()));
                    }
                }
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
    // Parsed line types
    // =========================================================================

    sealed interface ParsedLine permits ParsedLine.Simple, ParsedLine.Pipeline {
        record Simple(CommandLine line) implements ParsedLine {}
        record Pipeline(List<String> leftArgs, List<String> rightArgs, Redirections redirections) implements ParsedLine {
            public Pipeline {
                leftArgs = List.copyOf(leftArgs);
                rightArgs = List.copyOf(rightArgs);
            }
        }
    }

    // =========================================================================
    // Interactive input + TAB completion
    // =========================================================================

    interface CompletionEngine {
        CompletionResult completeFirstWord(String currentFirstWord);
    }

    static final class CompletionResult {
        enum Kind {
            SUFFIX,
            NO_MATCH,
            AMBIGUOUS,
            ALREADY_COMPLETE,
            NOT_APPLICABLE
        }

        final Kind kind;
        final String suffixToAppend; // only for SUFFIX
        final List<String> matches;  // only meaningful for AMBIGUOUS printing

        private CompletionResult(Kind kind, String suffixToAppend, List<String> matches) {
            this.kind = kind;
            this.suffixToAppend = suffixToAppend;
            this.matches = matches == null ? List.of() : matches;
        }

        static CompletionResult suffix(String s) {
            return new CompletionResult(Kind.SUFFIX, s, List.of());
        }

        static CompletionResult ambiguous(List<String> matchesSorted) {
            return new CompletionResult(Kind.AMBIGUOUS, null, List.copyOf(matchesSorted));
        }

        static CompletionResult of(Kind k) {
            return new CompletionResult(k, null, List.of());
        }
    }

    static final class CommandNameCompleter implements CompletionEngine {
        private final BuiltinRegistry builtins;
        private final PathResolver resolver;

        CommandNameCompleter(BuiltinRegistry builtins, PathResolver resolver) {
            this.builtins = builtins;
            this.resolver = resolver;
        }

        @Override
        public CompletionResult completeFirstWord(String currentFirstWord) {
            if (currentFirstWord == null || currentFirstWord.isEmpty()) {
                return CompletionResult.of(CompletionResult.Kind.NOT_APPLICABLE);
            }

            Set<String> matches = new LinkedHashSet<>();
            for (String b : builtins.names()) {
                if (b.startsWith(currentFirstWord)) matches.add(b);
            }
            matches.addAll(resolver.findExecutableNamesByPrefix(currentFirstWord));

            if (matches.isEmpty()) return CompletionResult.of(CompletionResult.Kind.NO_MATCH);

            if (matches.size() == 1) {
                String only = matches.iterator().next();
                if (only.equals(currentFirstWord)) return CompletionResult.of(CompletionResult.Kind.ALREADY_COMPLETE);
                return CompletionResult.suffix(only.substring(currentFirstWord.length()) + " ");
            }

            TreeSet<String> sorted = new TreeSet<>(matches);
            String lcp = longestCommonPrefix(sorted);
            if (lcp.length() > currentFirstWord.length()) {
                return CompletionResult.suffix(lcp.substring(currentFirstWord.length()));
            }

            return CompletionResult.ambiguous(new ArrayList<>(sorted));
        }

        private static String longestCommonPrefix(Iterable<String> items) {
            String first = null;
            for (String s : items) {
                first = s;
                break;
            }
            if (first == null || first.isEmpty()) return "";

            int end = first.length();
            for (String s : items) {
                int max = Math.min(end, s.length());
                int i = 0;
                while (i < max && first.charAt(i) == s.charAt(i)) i++;
                end = i;
                if (end == 0) return "";
            }
            return first.substring(0, end);
        }
    }

    static final class InteractiveInput implements AutoCloseable {
        private static final char BEL = '\u0007';

        private final InputStream in;
        private final CompletionEngine completer;
        private final TerminalMode terminalMode;
        private final String prompt;

        private boolean rawEnabled;

        private int consecutiveTabs;
        private String bufferSnapshotOnFirstTab;
        private List<String> ambiguousMatches;

        InteractiveInput(InputStream in, CompletionEngine completer, String prompt) {
            this.in = in;
            this.completer = completer;
            this.terminalMode = new TerminalMode();
            this.prompt = prompt;

            this.rawEnabled = false;
            this.consecutiveTabs = 0;
            this.bufferSnapshotOnFirstTab = null;
            this.ambiguousMatches = List.of();

            try {
                rawEnabled = terminalMode.enableRawMode();
            } catch (Exception ignored) {
                rawEnabled = false;
            }
        }

        String readLine() throws IOException {
            StringBuilder buf = new StringBuilder();

            while (true) {
                int b = in.read();
                if (b == -1) {
                    if (buf.length() > 0) {
                        System.out.print("\n");
                        resetTabState();
                        return buf.toString();
                    }
                    return null;
                }

                char c = (char) b;

                if (c == '\n') {
                    System.out.print("\n");
                    resetTabState();
                    return buf.toString();
                }
                if (c == '\r') {
                    in.mark(1);
                    int next = in.read();
                    if (next != '\n') {
                        if (next != -1) {
                            // pushback not available; just ignore
                        }
                    }
                    System.out.print("\n");
                    resetTabState();
                    return buf.toString();
                }

                if (c == '\t') {
                    handleTab(buf);
                    continue;
                }

                if (b == 127 || b == 8) {
                    if (buf.length() > 0) {
                        buf.deleteCharAt(buf.length() - 1);
                        System.out.print("\b \b");
                    }
                    resetTabState();
                    continue;
                }

                buf.append(c);
                System.out.print(c);
                resetTabState();
            }
        }

        private void handleTab(StringBuilder buf) {
            if (buf.length() == 0) return;

            for (int i = 0; i < buf.length(); i++) {
                if (Character.isWhitespace(buf.charAt(i))) {
                    return;
                }
            }

            String current = buf.toString();
            CompletionResult r = completer.completeFirstWord(current);

            if (r.kind == CompletionResult.Kind.SUFFIX) {
                String add = r.suffixToAppend;
                buf.append(add);
                System.out.print(add);
                resetTabState();
                return;
            }

            if (r.kind == CompletionResult.Kind.NO_MATCH) {
                System.out.print(BEL);
                System.out.flush();
                resetTabState();
                return;
            }

            if (r.kind == CompletionResult.Kind.AMBIGUOUS) {
                if (consecutiveTabs == 0 || bufferSnapshotOnFirstTab == null || !bufferSnapshotOnFirstTab.equals(current)) {
                    System.out.print(BEL);
                    System.out.flush();

                    consecutiveTabs = 1;
                    bufferSnapshotOnFirstTab = current;
                    ambiguousMatches = r.matches;
                    return;
                }

                if (consecutiveTabs == 1 && bufferSnapshotOnFirstTab.equals(current)) {
                    System.out.print("\n");
                    if (!ambiguousMatches.isEmpty()) {
                        System.out.print(String.join("  ", ambiguousMatches));
                    }
                    System.out.print("\n");
                    System.out.print(prompt);
                    System.out.print(current);
                    System.out.flush();
                    resetTabState();
                    return;
                }

                resetTabState();
                return;
            }
            resetTabState();
        }

        private void resetTabState() {
            consecutiveTabs = 0;
            bufferSnapshotOnFirstTab = null;
            ambiguousMatches = List.of();
        }

        @Override
        public void close() {
            if (rawEnabled) {
                try {
                    terminalMode.disableRawMode();
                } catch (Exception ignored) {
                }
            }
        }
    }

    static final class TerminalMode {
        boolean enableRawMode() throws IOException, InterruptedException {
            int code = execStty("stty -icanon -echo min 1 time 0 < /dev/tty");
            return code == 0;
        }

        void disableRawMode() throws IOException, InterruptedException {
            execStty("stty sane < /dev/tty");
        }

        private static int execStty(String cmd) throws IOException, InterruptedException {
            Process p = new ProcessBuilder("/bin/sh", "-c", cmd).start();
            return p.waitFor();
        }
    }

    // =========================================================================
    // Tokenizer (Lexer)
    // =========================================================================

    static final class Tokenizer {
        private enum State {
            DEFAULT, ESCAPE, SINGLE_QUOTE, DOUBLE_QUOTE, DOUBLE_QUOTE_ESCAPE
        }

        private Tokenizer() {}

        static List<String> tokenize(String input) {
            List<String> tokens = new ArrayList<>();
            StringBuilder current = new StringBuilder();

            State state = State.DEFAULT;
            boolean inToken = false;

            for (int i = 0, n = input.length(); i < n; i++) {
                char c = input.charAt(i);

                switch (state) {
                    case DEFAULT -> {
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
                    }
                    case ESCAPE -> {
                        current.append(c);
                        state = State.DEFAULT;
                    }
                    case SINGLE_QUOTE -> {
                        if (c == '\'') {
                            state = State.DEFAULT;
                        } else {
                            current.append(c);
                        }
                    }
                    case DOUBLE_QUOTE -> {
                        if (c == '"') {
                            state = State.DEFAULT;
                        } else if (c == '\\') {
                            state = State.DOUBLE_QUOTE_ESCAPE;
                        } else {
                            current.append(c);
                        }
                    }
                    case DOUBLE_QUOTE_ESCAPE -> {
                        if (c == '\\' || c == '"') {
                            current.append(c);
                        } else {
                            current.append('\\');
                            current.append(c);
                        }
                        state = State.DOUBLE_QUOTE;
                    }
                }
            }

            if (inToken) {
                tokens.add(current.toString());
            }
            return tokens;
        }
    }

    // =========================================================================
    // Parsing (redirection extraction + pipeline detection)
    // =========================================================================

    enum RedirectMode { TRUNCATE, APPEND }
    enum RedirectStream { STDOUT, STDERR }

    record RedirectSpec(RedirectStream stream, Path path, RedirectMode mode) {}

    record Redirections(Optional<RedirectSpec> stdoutRedirect, Optional<RedirectSpec> stderrRedirect) {
        static Redirections none() {
            return new Redirections(Optional.empty(), Optional.empty());
        }
    }

    record CommandLine(List<String> args, Redirections redirections) {
        CommandLine {
            args = List.copyOf(args);
        }
    }

    static final class CommandLineParser {
        private static final String OP_PIPE = "|";
        private static final String OP_GT = ">";
        private static final String OP_1GT = "1>";
        private static final String OP_DGT = ">>";
        private static final String OP_1DGT = "1>>";
        private static final String OP_2GT = "2>";
        private static final String OP_2DGT = "2>>";

        ParsedLine parse(List<String> tokens) {
            CommandLine base = parseRedirections(tokens);
            List<String> args = base.args();

            int pipeIdx = indexOfSinglePipe(args);
            if (pipeIdx >= 0) {
                List<String> left = args.subList(0, pipeIdx);
                List<String> right = args.subList(pipeIdx + 1, args.size());
                return new ParsedLine.Pipeline(left, right, base.redirections());
            }

            return new ParsedLine.Simple(base);
        }

        private static int indexOfSinglePipe(List<String> args) {
            int idx = -1;
            for (int i = 0; i < args.size(); i++) {
                if (OP_PIPE.equals(args.get(i))) {
                    if (idx != -1) return -1;
                    idx = i;
                }
            }
            if (idx <= 0 || idx >= args.size() - 1) return -1;
            return idx;
        }

        private CommandLine parseRedirections(List<String> tokens) {
            if (tokens.size() >= 2) {
                String op = tokens.get(tokens.size() - 2);
                String fileToken = tokens.get(tokens.size() - 1);

                if (OP_GT.equals(op) || OP_1GT.equals(op)) {
                    return withoutLastTwo(tokens, new Redirections(
                            Optional.of(new RedirectSpec(RedirectStream.STDOUT, Paths.get(fileToken), RedirectMode.TRUNCATE)),
                            Optional.empty()));
                }
                if (OP_DGT.equals(op) || OP_1DGT.equals(op)) {
                    return withoutLastTwo(tokens, new Redirections(
                            Optional.of(new RedirectSpec(RedirectStream.STDOUT, Paths.get(fileToken), RedirectMode.APPEND)),
                            Optional.empty()));
                }
                if (OP_2GT.equals(op)) {
                    return withoutLastTwo(tokens, new Redirections(
                            Optional.empty(),
                            Optional.of(new RedirectSpec(RedirectStream.STDERR, Paths.get(fileToken), RedirectMode.TRUNCATE))));
                }
                if (OP_2DGT.equals(op)) {
                    return withoutLastTwo(tokens, new Redirections(
                            Optional.empty(),
                            Optional.of(new RedirectSpec(RedirectStream.STDERR, Paths.get(fileToken), RedirectMode.APPEND))));
                }
            }
            return new CommandLine(tokens, Redirections.none());
        }

        private static CommandLine withoutLastTwo(List<String> tokens, Redirections redirs) {
            List<String> args = new ArrayList<>(tokens.subList(0, tokens.size() - 2));
            return new CommandLine(args, redirs);
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

        Path getCurrentDirectory() { return currentDirectory; }
        void setCurrentDirectory(Path p) { this.currentDirectory = p.toAbsolutePath().normalize(); }
        String getenv(String key) { return System.getenv(key); }
        String getHome() { return getenv(ENV_HOME); }

        List<Path> getPathDirectories() {
            String pathEnv = getenv(ENV_PATH);
            if (pathEnv == null || pathEnv.isEmpty()) return new ArrayList<>();

            char sep = File.pathSeparatorChar;
            List<Path> out = new ArrayList<>();
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

        PathResolver(Environment env) { this.env = env; }

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
            for (Path dir : dirs) {
                Path candidate = dir.resolve(name);
                if (Files.isRegularFile(candidate) && Files.isExecutable(candidate)) {
                    return Optional.of(candidate);
                }
            }
            return Optional.empty();
        }

        Set<String> findExecutableNamesByPrefix(String prefix) {
            if (prefix == null || prefix.isEmpty()) return Collections.emptySet();
            if (containsSeparator(prefix)) return Collections.emptySet();

            Set<String> out = new LinkedHashSet<>();
            List<Path> dirs = env.getPathDirectories();
            for (Path dir : dirs) {
                if (dir == null || !Files.isDirectory(dir)) continue;
                try (DirectoryStream<Path> stream = Files.newDirectoryStream(dir)) {
                    for (Path p : stream) {
                        String name = p.getFileName() == null ? null : p.getFileName().toString();
                        if (name == null || !name.startsWith(prefix)) continue;
                        if (Files.isRegularFile(p) && Files.isExecutable(p)) {
                            out.add(name);
                        }
                    }
                } catch (Exception ignored) {}
            }
            return out;
        }

        private static boolean containsSeparator(String s) {
            return s.indexOf('/') >= 0 || s.indexOf('\\') >= 0 || s.indexOf(File.separatorChar) >= 0;
        }
    }

    // =========================================================================
    // Execution Context + I/O
    // =========================================================================

    record ExecutionContext(
            List<String> args,
            Redirections redirections,
            InputStream stdin,
            PrintStream stdout,
            PrintStream stderr
    ) {
        public ExecutionContext {
            args = List.copyOf(args);
        }

        static ExecutionContext system() {
            return new ExecutionContext(List.of(), Redirections.none(), System.in, System.out, System.err);
        }

        ExecutionContext withArgsAndRedirs(List<String> newArgs, Redirections newRedirs) {
            return new ExecutionContext(newArgs, newRedirs, this.stdin, this.stdout, this.stderr);
        }
    }

    static final class RedirectionIO {
        private RedirectionIO() {}

        static void touchCreateAppend(Path p) throws IOException {
            try (OutputStream os = Files.newOutputStream(p, StandardOpenOption.CREATE, StandardOpenOption.APPEND, StandardOpenOption.WRITE)) {}
        }
        static void touchTruncate(Path p) throws IOException {
            try (OutputStream os = Files.newOutputStream(p, StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING, StandardOpenOption.WRITE)) {}
        }
        static void touch(RedirectSpec spec) throws IOException {
            if (spec.mode() == RedirectMode.APPEND) touchCreateAppend(spec.path());
            else touchTruncate(spec.path());
        }
    }

    interface OutputTarget extends AutoCloseable {
        PrintStream out();
        @Override void close() throws IOException;
    }

    static final class OutputTargets {
        private OutputTargets() {}

        static OutputTarget resolve(Optional<RedirectSpec> redirect, PrintStream defaultStream) throws IOException {
            if (redirect.isEmpty()) {
                // Wrapper to avoid closing the default stream
                return new OutputTarget() {
                    @Override public PrintStream out() { return defaultStream; }
                    @Override public void close() { /* do nothing */ }
                };
            }

            RedirectSpec spec = redirect.get();
            OutputStream os;
            if (spec.mode() == RedirectMode.APPEND) {
                os = Files.newOutputStream(spec.path(), StandardOpenOption.CREATE, StandardOpenOption.APPEND, StandardOpenOption.WRITE);
            } else {
                os = Files.newOutputStream(spec.path(), StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING, StandardOpenOption.WRITE);
            }
            return new FileTarget(new PrintStream(os));
        }
    }

    static final class FileTarget implements OutputTarget {
        private final PrintStream ps;
        FileTarget(PrintStream ps) { this.ps = ps; }
        @Override public PrintStream out() { return ps; }
        @Override public void close() { ps.close(); }
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
            Optional<ShellCommand> b = builtins.lookup(name);
            if (b.isPresent()) return b.get();
            return new ExternalCommand(name, args, resolver);
        }
    }

    static final class BuiltinRegistry {
        private final Map<String, ShellCommand> map;

        BuiltinRegistry(PathResolver resolver) {
            var tmp = new HashMap<String, ShellCommand>();
            tmp.put("exit", new ExitCommand());
            tmp.put("echo", new EchoCommand());
            tmp.put("pwd", new PwdCommand());
            tmp.put("cd", new CdCommand());
            tmp.put("type", new TypeCommand(this, resolver));
            this.map = Collections.unmodifiableMap(tmp);
        }

        Optional<ShellCommand> lookup(String name) { return Optional.ofNullable(map.get(name)); }
        boolean isBuiltin(String name) { return map.containsKey(name); }
        List<String> names() { return new ArrayList<>(map.keySet()); }
    }

    static abstract class BuiltinCommand implements ShellCommand {
        @Override
        public final void execute(ExecutionContext ctx) {
            // Touch stderr file if redirected
            if (ctx.redirections().stderrRedirect().isPresent()) {
                try {
                    RedirectionIO.touch(ctx.redirections().stderrRedirect().get());
                } catch (IOException e) {
                    System.err.println("Redirection error: " + e.getMessage());
                }
            }

            try (OutputTarget target = OutputTargets.resolve(ctx.redirections().stdoutRedirect(), ctx.stdout())) {
                executeBuiltin(ctx, target.out());
            } catch (IOException e) {
                System.err.println("Redirection error: " + e.getMessage());
            }
        }
        protected abstract void executeBuiltin(ExecutionContext ctx, PrintStream out);
    }

    static final class ExternalCommand implements ShellCommand {
        private final String commandName;
        private final List<String> originalArgs;
        private final PathResolver resolver;

        ExternalCommand(String commandName, List<String> originalArgs, PathResolver resolver) {
            this.commandName = commandName;
            this.originalArgs = List.copyOf(originalArgs);
            this.resolver = resolver;
        }

        @Override
        public void execute(ExecutionContext ctx) {
            try {
                Optional<Path> ok = resolver.findExecutable(commandName);
                if (ok.isEmpty()) {
                    System.out.println(commandName + ": command not found");
                    return;
                }

                ProcessBuilder pb = new ProcessBuilder(new ArrayList<>(originalArgs));
                pb.directory(ShellRuntime.env.getCurrentDirectory().toFile());

                // Input strategy: only pipe if strictly necessary (not System.in)
                boolean inputPiped = (ctx.stdin() != System.in);
                if (inputPiped) {
                    pb.redirectInput(ProcessBuilder.Redirect.PIPE);
                } else {
                    pb.redirectInput(ProcessBuilder.Redirect.INHERIT);
                }

                // Stdout
                if (ctx.redirections().stdoutRedirect().isPresent()) {
                    RedirectSpec spec = ctx.redirections().stdoutRedirect().get();
                    if (spec.mode() == RedirectMode.APPEND) RedirectionIO.touchCreateAppend(spec.path());
                    else RedirectionIO.touchTruncate(spec.path());
                    pb.redirectOutput(spec.mode() == RedirectMode.APPEND
                            ? ProcessBuilder.Redirect.appendTo(spec.path().toFile())
                            : ProcessBuilder.Redirect.to(spec.path().toFile()));
                } else if (ctx.stdout() != System.out) {
                    pb.redirectOutput(ProcessBuilder.Redirect.PIPE);
                } else {
                    pb.redirectOutput(ProcessBuilder.Redirect.INHERIT);
                }

                // Stderr
                if (ctx.redirections().stderrRedirect().isPresent()) {
                    RedirectSpec spec = ctx.redirections().stderrRedirect().get();
                    if (spec.mode() == RedirectMode.APPEND) RedirectionIO.touchCreateAppend(spec.path());
                    else RedirectionIO.touchTruncate(spec.path());
                    pb.redirectError(spec.mode() == RedirectMode.APPEND
                            ? ProcessBuilder.Redirect.appendTo(spec.path().toFile())
                            : ProcessBuilder.Redirect.to(spec.path().toFile()));
                } else {
                    // Inherit stderr usually, unless ctx.stderr is distinct?
                    // For now, external commands just inherit stderr to terminal if not redirected to file.
                    pb.redirectError(ProcessBuilder.Redirect.INHERIT);
                }

                Process p = pb.start();

                // Pump input if needed
                Thread inputPump = null;
                if (inputPiped) {
                    InputStream src = ctx.stdin();
                    OutputStream dest = p.getOutputStream();
                    inputPump = new Thread(() -> copyQuietly(src, dest));
                    inputPump.start();
                }

                // Pump output if needed
                Thread outputPump = null;
                boolean outputPiped = (ctx.redirections().stdoutRedirect().isEmpty() && ctx.stdout() != System.out);
                if (outputPiped) {
                    InputStream src = p.getInputStream();
                    PrintStream dest = ctx.stdout();
                    outputPump = new Thread(() -> copyQuietly(src, dest));
                    outputPump.start();
                }

                p.waitFor();
                if (inputPump != null) inputPump.join();
                if (outputPump != null) outputPump.join();

            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                System.out.println(commandName + ": command not found");
            } catch (IOException e) {
                System.out.println(commandName + ": command not found");
            }
        }

        private static void copyQuietly(InputStream in, OutputStream out) {
            try {
                byte[] buf = new byte[8192];
                int n;
                while ((n = in.read(buf)) != -1) {
                    out.write(buf, 0, n);
                    out.flush();
                }
                // Close output to signal EOF to the process/stream
                out.close();
            } catch (IOException ignored) {}
        }
    }

    static final class PipelineCommand implements ShellCommand {
        private final List<String> leftArgs;
        private final List<String> rightArgs;
        private final Redirections redirectionsForRight;
        private final PathResolver resolver;
        private final BuiltinRegistry builtins;

        PipelineCommand(List<String> leftArgs, List<String> rightArgs, Redirections redirectionsForRight, PathResolver resolver, BuiltinRegistry builtins) {
            this.leftArgs = List.copyOf(leftArgs);
            this.rightArgs = List.copyOf(rightArgs);
            this.redirectionsForRight = redirectionsForRight;
            this.resolver = resolver;
            this.builtins = builtins;
        }

        @Override
        public void execute(ExecutionContext ctx) {
            if (leftArgs.isEmpty() || rightArgs.isEmpty()) return;

            String leftName = leftArgs.get(0);
            String rightName = rightArgs.get(0);

            boolean leftIsBuiltin = builtins.isBuiltin(leftName);
            boolean rightIsBuiltin = builtins.isBuiltin(rightName);

            // Validation: only fail if external command missing
            if (!leftIsBuiltin && resolver.findExecutable(leftName).isEmpty()) {
                System.out.println(leftName + ": command not found");
                return;
            }
            if (!rightIsBuiltin && resolver.findExecutable(rightName).isEmpty()) {
                System.out.println(rightName + ": command not found");
                return;
            }

            // Case 1: External | External (Optimized with PIPE)
            if (!leftIsBuiltin && !rightIsBuiltin) {
                executeExternalPipeline(leftName, rightName, ctx);
                return;
            }

            // Mixed Pipeline Execution
            try {
                // Prepare pipe
                PipedInputStream pis = new PipedInputStream();
                PipedOutputStream pos = new PipedOutputStream(pis);

                // --- Execute LEFT ---
                Thread leftThread;
                Process leftProcess = null;

                if (leftIsBuiltin) {
                    leftThread = new Thread(() -> {
                        try (pos; PrintStream pipeOut = new PrintStream(pos)) {
                            ShellCommand cmd = builtins.lookup(leftName).orElseThrow();
                            // Left builtin writes to the pipe. Stderr inherits (usually to screen).
                            // No redirections supported on left side args per parser logic.
                            cmd.execute(new ExecutionContext(leftArgs, Redirections.none(), ctx.stdin(), pipeOut, ctx.stderr()));
                        } catch (IOException e) {
                            e.printStackTrace();
                        }
                    });
                    leftThread.start();
                } else {
                    // Left is External -> run it, pumping stdout to pos
                    leftThread = new Thread(() -> {
                        try (pos; PrintStream pipeOut = new PrintStream(pos)) {
                            new ExternalCommand(leftName, leftArgs, resolver)
                                    .execute(new ExecutionContext(leftArgs, Redirections.none(), ctx.stdin(), pipeOut, ctx.stderr()));
                        } catch (IOException e) {
                            e.printStackTrace();
                        }
                    });
                    leftThread.start();
                }

                // --- Execute RIGHT ---
                // Right reads from pis.
                // Right output goes to actual context stdout (or file redirection).

                ShellCommand rightCmd;
                if (rightIsBuiltin) {
                    rightCmd = builtins.lookup(rightName).orElseThrow();
                } else {
                    rightCmd = new ExternalCommand(rightName, rightArgs, resolver);
                }

                // Execute right in current thread (blocking)
                rightCmd.execute(new ExecutionContext(rightArgs, redirectionsForRight, pis, ctx.stdout(), ctx.stderr()));

                // Cleanup
                leftThread.join();

            } catch (Exception e) {
                e.printStackTrace();
            }
        }

        private void executeExternalPipeline(String leftName, String rightName, ExecutionContext ctx) {
            try {
                ProcessBuilder leftPb = new ProcessBuilder(new ArrayList<>(leftArgs));
                leftPb.directory(ShellRuntime.env.getCurrentDirectory().toFile());
                leftPb.redirectInput(ProcessBuilder.Redirect.INHERIT);
                leftPb.redirectError(ProcessBuilder.Redirect.INHERIT);
                leftPb.redirectOutput(ProcessBuilder.Redirect.PIPE);

                ProcessBuilder rightPb = new ProcessBuilder(new ArrayList<>(rightArgs));
                rightPb.directory(ShellRuntime.env.getCurrentDirectory().toFile());
                rightPb.redirectInput(ProcessBuilder.Redirect.PIPE);
                // Right stderr usually inherits
                rightPb.redirectError(ProcessBuilder.Redirect.INHERIT);

                // Right output handling
                if (redirectionsForRight.stdoutRedirect().isPresent()) {
                    RedirectSpec spec = redirectionsForRight.stdoutRedirect().get();
                    if (spec.mode() == RedirectMode.APPEND) {
                        RedirectionIO.touchCreateAppend(spec.path());
                        rightPb.redirectOutput(ProcessBuilder.Redirect.appendTo(spec.path().toFile()));
                    } else {
                        RedirectionIO.touchTruncate(spec.path());
                        rightPb.redirectOutput(spec.path().toFile());
                    }
                } else {
                    rightPb.redirectOutput(ProcessBuilder.Redirect.INHERIT);
                }

                // Handle stderr redirection for right if needed
                if (redirectionsForRight.stderrRedirect().isPresent()) {
                    RedirectSpec spec = redirectionsForRight.stderrRedirect().get();
                    if (spec.mode() == RedirectMode.APPEND) {
                        RedirectionIO.touchCreateAppend(spec.path());
                        rightPb.redirectError(ProcessBuilder.Redirect.appendTo(spec.path().toFile()));
                    } else {
                        RedirectionIO.touchTruncate(spec.path());
                        rightPb.redirectError(spec.path().toFile());
                    }
                }

                Process left = leftPb.start();
                Process right = rightPb.start();

                // Pump left -> right
                Thread pump = new Thread(() -> {
                    try (InputStream fromLeft = left.getInputStream();
                         OutputStream toRight = right.getOutputStream()) {
                        byte[] buf = new byte[8192];
                        int n;
                        while ((n = fromLeft.read(buf)) != -1) {
                            toRight.write(buf, 0, n);
                            toRight.flush();
                        }
                    } catch (IOException ignored) {}
                });
                pump.setDaemon(true);
                pump.start();

                right.waitFor();
                if (left.isAlive()) {
                    left.destroy();
                    left.waitFor(200, TimeUnit.MILLISECONDS);
                    if (left.isAlive()) left.destroyForcibly();
                }
                pump.join(200);

            } catch (Exception e) {
                System.out.println(rightName + ": command not found");
            }
        }
    }

    static final class ExitCommand extends BuiltinCommand {
        @Override
        protected void executeBuiltin(ExecutionContext ctx, PrintStream out) {
            int code = 0;
            if (ctx.args.size() > 1) {
                try {
                    code = Integer.parseInt(ctx.args.get(1));
                } catch (NumberFormatException ignored) {}
            }
            System.exit(code);
        }
    }

    static final class EchoCommand extends BuiltinCommand {
        @Override
        protected void executeBuiltin(ExecutionContext ctx, PrintStream out) {
            if (ctx.args.size() == 1) {
                out.println();
                return;
            }
            StringBuilder sb = new StringBuilder();
            for (int i = 1; i < ctx.args.size(); i++) {
                if (i > 1) sb.append(" ");
                sb.append(ctx.args.get(i));
            }
            out.println(sb.toString());
        }
    }

    static final class PwdCommand extends BuiltinCommand {
        @Override
        protected void executeBuiltin(ExecutionContext ctx, PrintStream out) {
            out.println(ShellRuntime.env.getCurrentDirectory());
        }
    }

    static final class CdCommand extends BuiltinCommand {
        @Override
        protected void executeBuiltin(ExecutionContext ctx, PrintStream out) {
            if (ctx.args.size() > 2) return;
            final String originalArg = ctx.args.size() == 1 ? "~" : ctx.args.get(1);
            String target = originalArg;
            String home = ShellRuntime.env.getHome();

            if ("~".equals(target)) {
                if (home == null) {
                    out.println("cd: HOME not set");
                    return;
                }
                target = home;
            } else if (target.startsWith("~")) {
                if (target.startsWith("~" + File.separator)) {
                    if (home == null) {
                        out.println("cd: HOME not set");
                        return;
                    }
                    target = home + target.substring(1);
                }
            }

            Path p = Paths.get(target);
            if (!p.isAbsolute()) {
                p = ShellRuntime.env.getCurrentDirectory().resolve(p);
            }
            Path resolved = p.normalize();

            if (Files.exists(resolved) && Files.isDirectory(resolved)) {
                ShellRuntime.env.setCurrentDirectory(resolved);
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

        @Override
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

    static final class ShellRuntime {
        static final Environment env = new Environment();
    }
}
