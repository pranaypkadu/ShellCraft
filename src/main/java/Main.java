import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
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
 * Preserved behavior:
 * - Prompt: "$ " before each command
 * - Tokenization with single quotes, double quotes (\" and \\), and backslash escaping
 * - Builtins: exit, echo, type, pwd, cd
 * - External commands resolved via PATH (verification) and executed in current directory
 * - Stdout redirection: ">" and "1>" (last operator+filename pair only), stderr not redirected
 * - Stderr redirection: "2>" (last operator+filename pair only), stdout not redirected
 * - Stdout append redirection: ">>" and "1>>" (last operator+filename pair only)
 * - Stderr append redirection: "2>>" (last operator+filename pair only)
 *
 * Pipeline support (two external commands):
 * - Supports: <cmd1 ...> | <cmd2 ...>
 * - Connects stdout of left process to stdin of right process.
 * - Trailing redirections (>, 1>, >>, 1>>, 2>, 2>>) apply to the RIGHT command (like typical shells).
 *
 * Tab completion behavior:
 * - Completes only the first word (command position), only when no whitespace has been typed yet.
 * - If the current first word uniquely matches a builtin or a PATH executable prefix, complete it + trailing space.
 * - If it matches nothing, leave input unchanged and ring a bell (\u0007).
 * - If it is ambiguous (multiple matches):
 *   - If matches share a longer common prefix than what is currently typed, extend input to that LCP (no trailing space).
 *   - Otherwise: first <TAB> rings bell (\u0007); second consecutive <TAB> prints all matches (alphabetical,
 *     separated by two spaces) on a new line, then redraws prompt and the original input.
 * - If it is already complete (exact match), do nothing.
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
        private final CommandLineParser parser;
        private final String prompt;

        Shell(InteractiveInput input,
              Environment env,
              PathResolver resolver,
              CommandFactory factory,
              CommandLineParser parser,
              String prompt) {
            this.input = input;
            this.env = env;
            this.resolver = resolver;
            this.factory = factory;
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
                switch (parsed) {
                    case ParsedLine.Simple simple -> {
                        CommandLine cmdLine = simple.line();
                        if (cmdLine.args().isEmpty()) return;
                        String name = cmdLine.args().get(0);
                        ShellCommand cmd = factory.create(name, cmdLine.args());
                        ExecutionContext ctx = new ExecutionContext(cmdLine.args(), cmdLine.redirections());
                        cmd.execute(ctx);
                    }
                    case ParsedLine.Pipeline pipe -> {
                        if (pipe.leftArgs().isEmpty() || pipe.rightArgs().isEmpty()) return;
                        ShellCommand cmd = new PipelineCommand(pipe.leftArgs(), pipe.rightArgs(), pipe.redirections(), resolver);
                        // ExecutionContext still used to keep the pattern consistent; pipeline uses its own stored args.
                        cmd.execute(new ExecutionContext(List.of(), Redirections.none()));
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

    /**
     * Completes command names from:
     * - Builtins
     * - External executables found in PATH
     *
     * Supports LCP completion for multiple matches:
     * - If LCP extends beyond current input => return SUFFIX (no trailing space).
     * - Otherwise => AMBIGUOUS (bell then list on second tab handled by InteractiveInput).
     */
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

        // For handling "double-tab" behavior on ambiguous matches.
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

            // Best-effort raw mode; ignore failures to preserve "never crash".
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
                    // EOF: if partial input exists, return it; else terminate loop.
                    if (buf.length() > 0) {
                        System.out.print("\n");
                        resetTabState();
                        return buf.toString();
                    }
                    return null;
                }

                char c = (char) b;

                // Enter handling: accept LF or CRLF
                if (c == '\n') {
                    System.out.print("\n");
                    resetTabState();
                    return buf.toString();
                }
                if (c == '\r') {
                    // Preserve original behavior (best-effort consume a following '\n').
                    in.mark(1);
                    int next = in.read();
                    if (next != '\n') {
                        if (next != -1) {
                            // pushback not available; just ignore (preserve behavior)
                        }
                    }
                    System.out.print("\n");
                    resetTabState();
                    return buf.toString();
                }

                // TAB completion
                if (c == '\t') {
                    handleTab(buf);
                    continue;
                }

                // Backspace (DEL or BS)
                if (b == 127 || b == 8) {
                    if (buf.length() > 0) {
                        buf.deleteCharAt(buf.length() - 1);
                        System.out.print("\b \b");
                    }
                    resetTabState();
                    continue;
                }

                // Regular char: echo it.
                buf.append(c);
                System.out.print(c);
                resetTabState();
            }
        }

        private void handleTab(StringBuilder buf) {
            // Only complete the first word (command position).
            if (buf.length() == 0) {
                // No input => no completion attempt; do not ring bell.
                return;
            }

            // If there's any whitespace, do not attempt completion (arguments already started).
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
                // First tab: bell; second consecutive tab on the same buffer: print matches.
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

            // ALREADY_COMPLETE/NOT_APPLICABLE: do nothing.
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
            DEFAULT,
            ESCAPE,
            SINGLE_QUOTE,
            DOUBLE_QUOTE,
            DOUBLE_QUOTE_ESCAPE
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

        // Only recognized when the final two tokens form: <op> <filename>.
        private static final String OP_GT = ">";
        private static final String OP_1GT = "1>";
        private static final String OP_DGT = ">>";
        private static final String OP_1DGT = "1>>";
        private static final String OP_2GT = "2>";
        private static final String OP_2DGT = "2>>";

        ParsedLine parse(List<String> tokens) {
            // Extract trailing redirection first (applies to whole line; for pipelines we apply it to RHS).
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
                    if (idx != -1) return -1; // only support exactly one pipe for this stage
                    idx = i;
                }
            }
            // Must have tokens on both sides.
            if (idx <= 0 || idx >= args.size() - 1) return -1;
            return idx;
        }

        private CommandLine parseRedirections(List<String> tokens) {
            if (tokens.size() >= 2) {
                String op = tokens.get(tokens.size() - 2);
                String fileToken = tokens.get(tokens.size() - 1);

                if (OP_GT.equals(op) || OP_1GT.equals(op)) {
                    return withoutLastTwo(tokens,
                            new Redirections(
                                    Optional.of(new RedirectSpec(RedirectStream.STDOUT, Paths.get(fileToken), RedirectMode.TRUNCATE)),
                                    Optional.empty()
                            )
                    );
                }

                if (OP_DGT.equals(op) || OP_1DGT.equals(op)) {
                    return withoutLastTwo(tokens,
                            new Redirections(
                                    Optional.of(new RedirectSpec(RedirectStream.STDOUT, Paths.get(fileToken), RedirectMode.APPEND)),
                                    Optional.empty()
                            )
                    );
                }

                if (OP_2GT.equals(op)) {
                    return withoutLastTwo(tokens,
                            new Redirections(
                                    Optional.empty(),
                                    Optional.of(new RedirectSpec(RedirectStream.STDERR, Paths.get(fileToken), RedirectMode.TRUNCATE))
                            )
                    );
                }

                if (OP_2DGT.equals(op)) {
                    return withoutLastTwo(tokens,
                            new Redirections(
                                    Optional.empty(),
                                    Optional.of(new RedirectSpec(RedirectStream.STDERR, Paths.get(fileToken), RedirectMode.APPEND))
                            )
                    );
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

        /**
         * Find executable file names on PATH that start with the given prefix.
         * Missing/nonexistent PATH entries are ignored.
         */
        Set<String> findExecutableNamesByPrefix(String prefix) {
            if (prefix == null || prefix.isEmpty()) return Collections.emptySet();
            if (containsSeparator(prefix)) return Collections.emptySet(); // completion is for command names only

            Set<String> out = new LinkedHashSet<>();
            List<Path> dirs = env.getPathDirectories();

            for (int i = 0; i < dirs.size(); i++) {
                Path dir = dirs.get(i);
                if (dir == null) continue;
                if (!Files.isDirectory(dir)) continue;

                try (DirectoryStream<Path> stream = Files.newDirectoryStream(dir)) {
                    for (Path p : stream) {
                        String name = p.getFileName() == null ? null : p.getFileName().toString();
                        if (name == null) continue;
                        if (!name.startsWith(prefix)) continue;
                        if (Files.isRegularFile(p) && Files.isExecutable(p)) {
                            out.add(name);
                        }
                    }
                } catch (Exception ignored) {
                    // ignore invalid PATH entries, permission issues, transient IO problems
                }
            }
            return out;
        }

        private static boolean containsSeparator(String s) {
            return s.indexOf('/') >= 0 || s.indexOf('\\') >= 0 || s.indexOf(File.separatorChar) >= 0;
        }
    }

    record ExecutionContext(List<String> args, Redirections redirections) {
        ExecutionContext {
            args = List.copyOf(args);
        }
    }

    // =========================================================================
    // Redirection I/O utilities
    // =========================================================================

    static final class RedirectionIO {
        private RedirectionIO() {}

        static void touchTruncate(Path p) throws IOException {
            try (OutputStream os = Files.newOutputStream(
                    p,
                    StandardOpenOption.CREATE,
                    StandardOpenOption.TRUNCATE_EXISTING,
                    StandardOpenOption.WRITE
            )) {
                // intentionally empty
            }
        }

        static void touchCreateAppend(Path p) throws IOException {
            try (OutputStream os = Files.newOutputStream(
                    p,
                    StandardOpenOption.CREATE,
                    StandardOpenOption.APPEND,
                    StandardOpenOption.WRITE
            )) {
                // intentionally empty
            }
        }

        static void touch(RedirectSpec spec) throws IOException {
            if (spec.mode() == RedirectMode.APPEND) touchCreateAppend(spec.path());
            else touchTruncate(spec.path());
        }
    }

    // =========================================================================
    // Output strategy (builtins)
    // =========================================================================

    interface OutputTarget extends AutoCloseable {
        PrintStream out();
        @Override void close() throws IOException;
    }

    static final class OutputTargets {
        private OutputTargets() {}

        static OutputTarget stdout(Optional<RedirectSpec> redirect) throws IOException {
            if (!redirect.isPresent()) {
                return StdoutTarget.INSTANCE;
            }

            RedirectSpec spec = redirect.get();
            OutputStream os;
            if (spec.mode() == RedirectMode.APPEND) {
                os = Files.newOutputStream(
                        spec.path(),
                        StandardOpenOption.CREATE,
                        StandardOpenOption.APPEND,
                        StandardOpenOption.WRITE
                );
            } else {
                os = Files.newOutputStream(
                        spec.path(),
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

        @Override
        public PrintStream out() {
            return ps;
        }

        @Override
        public void close() {
            ps.close();
        }
    }

    static final class StdoutTarget implements OutputTarget {
        static final StdoutTarget INSTANCE = new StdoutTarget();
        private StdoutTarget() {}

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

        Optional<ShellCommand> lookup(String name) {
            return Optional.ofNullable(map.get(name));
        }

        boolean isBuiltin(String name) {
            return map.containsKey(name);
        }

        List<String> names() {
            return new ArrayList<>(map.keySet());
        }
    }

    static abstract class BuiltinCommand implements ShellCommand {
        @Override
        public final void execute(ExecutionContext ctx) {
            // If 2>/2>> is provided, create the file even if the builtin writes nothing to stderr.
            if (ctx.redirections().stderrRedirect().isPresent()) {
                try {
                    RedirectionIO.touch(ctx.redirections().stderrRedirect().get());
                } catch (IOException e) {
                    System.err.println("Redirection error: " + e.getMessage());
                }
            }

            try (OutputTarget target = OutputTargets.stdout(ctx.redirections().stdoutRedirect())) {
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
            this.originalArgs = List.copyOf(originalArgs);
            this.resolver = resolver;
        }

        @Override
        public void execute(ExecutionContext ctx) {
            try {
                Optional<Path> ok = resolver.findExecutable(commandName);
                if (!ok.isPresent()) {
                    System.out.println(commandName + ": command not found");
                    return;
                }

                ProcessBuilder pb = new ProcessBuilder(new ArrayList<>(originalArgs));
                pb.directory(ShellRuntime.env.getCurrentDirectory().toFile());

                pb.redirectInput(ProcessBuilder.Redirect.INHERIT);

                if (ctx.redirections().stdoutRedirect().isPresent()) {
                    RedirectSpec spec = ctx.redirections().stdoutRedirect().get();
                    if (spec.mode() == RedirectMode.APPEND) {
                        RedirectionIO.touchCreateAppend(spec.path());
                        pb.redirectOutput(ProcessBuilder.Redirect.appendTo(spec.path().toFile()));
                    } else {
                        RedirectionIO.touchTruncate(spec.path());
                        pb.redirectOutput(spec.path().toFile());
                    }
                } else {
                    pb.redirectOutput(ProcessBuilder.Redirect.INHERIT);
                }

                if (ctx.redirections().stderrRedirect().isPresent()) {
                    RedirectSpec spec = ctx.redirections().stderrRedirect().get();
                    if (spec.mode() == RedirectMode.APPEND) {
                        RedirectionIO.touchCreateAppend(spec.path());
                        pb.redirectError(ProcessBuilder.Redirect.appendTo(spec.path().toFile()));
                    } else {
                        RedirectionIO.touchTruncate(spec.path());
                        pb.redirectError(spec.path().toFile());
                    }
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
    // Pipeline (two external commands)
    // =========================================================================

    static final class PipelineCommand implements ShellCommand {
        private final List<String> leftArgs;
        private final List<String> rightArgs;
        private final Redirections redirectionsForRight;
        private final PathResolver resolver;

        PipelineCommand(List<String> leftArgs, List<String> rightArgs, Redirections redirectionsForRight, PathResolver resolver) {
            this.leftArgs = List.copyOf(leftArgs);
            this.rightArgs = List.copyOf(rightArgs);
            this.redirectionsForRight = redirectionsForRight;
            this.resolver = resolver;
        }

        @Override
        public void execute(ExecutionContext ignoredCtx) {
            if (leftArgs.isEmpty() || rightArgs.isEmpty()) return;

            String leftName = leftArgs.get(0);
            String rightName = rightArgs.get(0);

            try {
                if (!resolver.findExecutable(leftName).isPresent()) {
                    System.out.println(leftName + ": command not found");
                    return;
                }
                if (!resolver.findExecutable(rightName).isPresent()) {
                    System.out.println(rightName + ": command not found");
                    return;
                }

                ProcessBuilder leftPb = new ProcessBuilder(new ArrayList<>(leftArgs));
                leftPb.directory(ShellRuntime.env.getCurrentDirectory().toFile());
                leftPb.redirectInput(ProcessBuilder.Redirect.INHERIT);
                leftPb.redirectError(ProcessBuilder.Redirect.INHERIT);
                leftPb.redirectOutput(ProcessBuilder.Redirect.PIPE);

                ProcessBuilder rightPb = new ProcessBuilder(new ArrayList<>(rightArgs));
                rightPb.directory(ShellRuntime.env.getCurrentDirectory().toFile());
                rightPb.redirectInput(ProcessBuilder.Redirect.PIPE);

                // Apply trailing redirections to the RIGHT side (like typical shells).
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

                if (redirectionsForRight.stderrRedirect().isPresent()) {
                    RedirectSpec spec = redirectionsForRight.stderrRedirect().get();
                    if (spec.mode() == RedirectMode.APPEND) {
                        RedirectionIO.touchCreateAppend(spec.path());
                        rightPb.redirectError(ProcessBuilder.Redirect.appendTo(spec.path().toFile()));
                    } else {
                        RedirectionIO.touchTruncate(spec.path());
                        rightPb.redirectError(spec.path().toFile());
                    }
                } else {
                    rightPb.redirectError(ProcessBuilder.Redirect.INHERIT);
                }

                Process left = leftPb.start();
                Process right = rightPb.start();

                Thread pump = new Thread(() -> pump(left, right), "pipe-pump");
                pump.setDaemon(true);
                pump.start();

                // Wait for the right process (consumer). When it finishes (e.g., head -n 5),
                // force termination of the left process if it's still running (e.g., tail -f).
                right.waitFor();

                // Ensure left doesn't keep the shell stuck.
                if (left.isAlive()) {
                    left.destroy();
                    left.waitFor(200, TimeUnit.MILLISECONDS);
                    if (left.isAlive()) left.destroyForcibly();
                }

                // Best-effort join; do not hang shell.
                pump.join(200);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                System.out.println(rightName + ": command not found");
            } catch (IOException e) {
                System.out.println(rightName + ": command not found");
            }
        }

        private static void pump(Process left, Process right) {
            try (InputStream fromLeft = left.getInputStream();
                 OutputStream toRight = right.getOutputStream()) {

                byte[] buf = new byte[8192];
                int n;
                while ((n = fromLeft.read(buf)) != -1) {
                    try {
                        toRight.write(buf, 0, n);
                        toRight.flush();
                    } catch (IOException brokenPipe) {
                        // Right side closed stdin (common for head). Stop pumping.
                        break;
                    }
                }
            } catch (IOException ignored) {
                // best-effort; keep shell stable
            } finally {
                try {
                    right.getOutputStream().close();
                } catch (Exception ignored) {
                }
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
            if (ctx.args().size() > 1) {
                try {
                    code = Integer.parseInt(ctx.args().get(1));
                } catch (NumberFormatException ignored) {
                }
            }
            System.exit(code);
        }
    }

    static final class EchoCommand extends BuiltinCommand {
        @Override
        protected void executeBuiltin(ExecutionContext ctx, PrintStream out) {
            if (ctx.args().size() <= 1) {
                out.println();
                return;
            }

            StringBuilder sb = new StringBuilder();
            for (int i = 1; i < ctx.args().size(); i++) {
                if (i > 1) sb.append(' ');
                sb.append(ctx.args().get(i));
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
            if (ctx.args().size() < 2) return;

            final String originalArg = ctx.args().get(1);
            String target = originalArg;

            String home = ShellRuntime.env.getHome();
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
            if (ctx.args().size() < 2) return;

            String target = ctx.args().get(1);

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

    // =========================================================================
    // ShellRuntime (shared state)
    // =========================================================================

    static final class ShellRuntime {
        static final Environment env = new Environment();
    }
}