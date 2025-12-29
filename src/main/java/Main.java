import java.io.*;
import java.nio.file.*;
import java.util.*;

public class Main {
    static final String PROMPT = "$ ";

    public static void main(String[] args) {
        var env = RuntimeState.env;
        var resolver = new PathResolver(env);

        var builtins = new BuiltinRegistry(resolver);
        var factory = new CommandFactoryImpl(builtins, resolver);

        var input = new InteractiveInput(System.in, new CommandNameCompleter(builtins, resolver), PROMPT);
        new Shell(input, factory, new CommandLineParser(), PROMPT).run();
    }

    // =============================================================================
    // Shell / orchestration
    // =============================================================================
    static final class Shell {
        private final InteractiveInput input;
        private final CommandFactory factory;
        private final CommandLineParser parser;
        private final String prompt;

        Shell(InteractiveInput input, CommandFactory factory, CommandLineParser parser, String prompt) {
            this.input = input;
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
                // Preserve exact message (including the "+ ").
                System.err.println("Fatal I/O Error: + " + e.getMessage());
            } finally {
                input.close();
            }
        }

        private void handle(String line) {
            try {
                List<String> tokens = Tokenizer.tokenize(line);
                if (tokens.isEmpty()) return;

                // history stores the raw line (as entered)
                RuntimeState.history.add(line);

                Parsed parsed = parser.parse(tokens);
                Ctx root = Ctx.system();

                if (parsed instanceof Parsed.Simple s) {
                    CmdLine cl = s.line();
                    if (cl.args().isEmpty()) return;
                    factory.create(cl.args().get(0), cl.args()).execute(root.with(cl.args(), cl.redirs()));
                    return;
                }

                if (parsed instanceof Parsed.Pipe p) {
                    new PipelineCommand(p.stages(), p.redirs(), factory)
                            .execute(root.with(List.of(), Redirs.none()));
                }
            } catch (Exception e) {
                String msg = e.getMessage();
                if (msg != null && !msg.isEmpty()) System.out.println(msg);
            }
        }
    }

    // =============================================================================
    // Parsed line model
    // =============================================================================
    sealed interface Parsed permits Parsed.Simple, Parsed.Pipe {
        record Simple(CmdLine line) implements Parsed { }

        record Pipe(List<List<String>> stages, Redirs redirs) implements Parsed {
            public Pipe {
                var tmp = new ArrayList<List<String>>(stages.size());
                for (var s : stages) tmp.add(List.copyOf(s));
                stages = List.copyOf(tmp);
            }
        }
    }

    enum RMode { TRUNCATE, APPEND }
    enum RStream { STDOUT, STDERR }

    record RSpec(RStream stream, Path path, RMode mode) { }

    record Redirs(Optional<RSpec> out, Optional<RSpec> err) {
        static Redirs none() { return new Redirs(Optional.empty(), Optional.empty()); }
    }

    record CmdLine(List<String> args, Redirs redirs) {
        CmdLine { args = List.copyOf(args); }
    }

    // =============================================================================
    // Tokenizer
    // =============================================================================
    static final class Tokenizer {
        private Tokenizer() { }

        private enum S { D, ESC, SQ, DQ, DQESC }

        static List<String> tokenize(String in) {
            var out = new ArrayList<String>();
            var cur = new StringBuilder();
            S st = S.D;
            boolean inTok = false;

            for (int i = 0, n = in.length(); i < n; i++) {
                char c = in.charAt(i);
                switch (st) {
                    case D -> {
                        if (Character.isWhitespace(c)) {
                            if (inTok) {
                                out.add(cur.toString());
                                cur.setLength(0);
                                inTok = false;
                            }
                        } else if (c == '\\') {
                            st = S.ESC;
                            inTok = true;
                        } else if (c == '\'') {
                            st = S.SQ;
                            inTok = true;
                        } else if (c == '"') {
                            st = S.DQ;
                            inTok = true;
                        } else {
                            cur.append(c);
                            inTok = true;
                        }
                    }
                    case ESC -> {
                        cur.append(c);
                        st = S.D;
                    }
                    case SQ -> {
                        if (c == '\'') st = S.D;
                        else cur.append(c);
                    }
                    case DQ -> {
                        if (c == '"') st = S.D;
                        else if (c == '\\') st = S.DQESC;
                        else cur.append(c);
                    }
                    case DQESC -> {
                        // In CodeCrafters tests, treat \' within double-quotes as an escaped single-quote too.
                        if (c == '\\' || c == '"' || c == '\'') cur.append(c);
                        else {
                            cur.append('\\');
                            cur.append(c);
                        }
                        st = S.DQ;
                    }

                }
            }

            if (inTok) out.add(cur.toString());
            return out;
        }
    }

    // =============================================================================
    // Parser (redirections + pipelines)
    // =============================================================================
    static final class CommandLineParser {
        private static final String PIPE = "|";
        private static final String GT = ">";
        private static final String ONE_GT = "1>";
        private static final String DGT = ">>";
        private static final String ONE_DGT = "1>>";
        private static final String TWO_GT = "2>";
        private static final String TWO_DGT = "2>>";

        Parsed parse(List<String> tokens) {
            CmdLine base = parseRedirs(tokens);
            Optional<List<List<String>>> split = splitPipeline(base.args());
            return split.isPresent()
                    ? new Parsed.Pipe(split.get(), base.redirs())
                    : new Parsed.Simple(base);
        }

        private static Optional<List<List<String>>> splitPipeline(List<String> args) {
            boolean saw = false;
            var stages = new ArrayList<List<String>>();
            var cur = new ArrayList<String>();

            for (String t : args) {
                if (PIPE.equals(t)) {
                    saw = true;
                    if (cur.isEmpty()) return Optional.empty();
                    stages.add(List.copyOf(cur));
                    cur.clear();
                } else {
                    cur.add(t);
                }
            }

            if (!saw || cur.isEmpty()) return Optional.empty();
            stages.add(List.copyOf(cur));

            return stages.size() < 2 ? Optional.empty() : Optional.of(List.copyOf(stages));
        }

        private static CmdLine parseRedirs(List<String> t) {
            if (t.size() >= 2) {
                String op = t.get(t.size() - 2);
                String file = t.get(t.size() - 1);

                if (GT.equals(op) || ONE_GT.equals(op)) {
                    return drop2(t, new Redirs(Optional.of(new RSpec(RStream.STDOUT, Paths.get(file), RMode.TRUNCATE)), Optional.empty()));
                }
                if (DGT.equals(op) || ONE_DGT.equals(op)) {
                    return drop2(t, new Redirs(Optional.of(new RSpec(RStream.STDOUT, Paths.get(file), RMode.APPEND)), Optional.empty()));
                }
                if (TWO_GT.equals(op)) {
                    return drop2(t, new Redirs(Optional.empty(), Optional.of(new RSpec(RStream.STDERR, Paths.get(file), RMode.TRUNCATE))));
                }
                if (TWO_DGT.equals(op)) {
                    return drop2(t, new Redirs(Optional.empty(), Optional.of(new RSpec(RStream.STDERR, Paths.get(file), RMode.APPEND))));
                }
            }
            return new CmdLine(t, Redirs.none());
        }

        private static CmdLine drop2(List<String> t, Redirs r) {
            return new CmdLine(new ArrayList<>(t.subList(0, t.size() - 2)), r);
        }
    }

    // =============================================================================
    // Environment + PATH resolver
    // =============================================================================
    static final class Env {
        private static final String ENV_PATH = "PATH";
        private static final String ENV_HOME = "HOME";

        private Path cwd = Paths.get(System.getProperty("user.dir")).toAbsolutePath().normalize();

        Path cwd() { return cwd; }
        void cwd(Path p) { cwd = p.toAbsolutePath().normalize(); }

        String home() { return System.getenv(ENV_HOME); }

        List<Path> pathDirs() {
            String s = System.getenv(ENV_PATH);
            if (s == null || s.isEmpty()) return new ArrayList<>();

            char sep = File.pathSeparatorChar;
            var out = new ArrayList<Path>();
            int start = 0;

            for (int i = 0, n = s.length(); i <= n; i++) {
                if (i == n || s.charAt(i) == sep) {
                    if (i > start) out.add(Paths.get(s.substring(start, i)));
                    start = i + 1;
                }
            }
            return out;
        }
    }

    static final class PathResolver {
        private final Env env;

        PathResolver(Env env) { this.env = env; }

        Optional<Path> findExecutable(String name) {
            if (hasSep(name)) {
                Path p = Paths.get(name);
                if (!p.isAbsolute()) p = env.cwd().resolve(p).normalize();
                return (Files.isRegularFile(p) && Files.isExecutable(p)) ? Optional.of(p) : Optional.empty();
            }

            for (Path dir : env.pathDirs()) {
                Path c = dir.resolve(name);
                if (Files.isRegularFile(c) && Files.isExecutable(c)) return Optional.of(c);
            }
            return Optional.empty();
        }

        Set<String> execNamesByPrefix(String prefix) {
            if (prefix == null || prefix.isEmpty() || hasSep(prefix)) return Collections.emptySet();

            var out = new LinkedHashSet<String>();
            for (Path dir : env.pathDirs()) {
                if (dir == null || !Files.isDirectory(dir)) continue;
                try (DirectoryStream<Path> ds = Files.newDirectoryStream(dir)) {
                    for (Path p : ds) {
                        String n = (p.getFileName() == null) ? null : p.getFileName().toString();
                        if (n == null || !n.startsWith(prefix)) continue;
                        if (Files.isRegularFile(p) && Files.isExecutable(p)) out.add(n);
                    }
                } catch (Exception ignored) { }
            }
            return out;
        }

        private static boolean hasSep(String s) {
            return s.indexOf('/') >= 0 || s.indexOf('\\') >= 0 || s.indexOf(File.separatorChar) >= 0;
        }
    }

    // =============================================================================
    // Execution context + output targets
    // =============================================================================
    record Ctx(List<String> args, Redirs redirs, InputStream in, PrintStream out, PrintStream err) {
        Ctx { args = List.copyOf(args); }
        static Ctx system() { return new Ctx(List.of(), Redirs.none(), System.in, System.out, System.err); }
        Ctx with(List<String> a, Redirs r) { return new Ctx(a, r, in, out, err); }
    }

    interface OutTarget extends AutoCloseable {
        PrintStream ps();
        @Override void close() throws IOException;
    }

    static final class IO {
        private IO() { }

        static void touch(RSpec s) throws IOException {
            if (s.mode() == RMode.APPEND) {
                try (var os = Files.newOutputStream(s.path(), StandardOpenOption.CREATE, StandardOpenOption.APPEND, StandardOpenOption.WRITE)) { }
            } else {
                try (var os = Files.newOutputStream(s.path(), StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING, StandardOpenOption.WRITE)) { }
            }
        }

        static OutTarget out(Optional<RSpec> redir, PrintStream def) throws IOException {
            if (redir.isEmpty()) {
                return new OutTarget() {
                    @Override public PrintStream ps() { return def; }
                    @Override public void close() { }
                };
            }

            RSpec s = redir.get();
            OutputStream os = (s.mode() == RMode.APPEND)
                    ? Files.newOutputStream(s.path(), StandardOpenOption.CREATE, StandardOpenOption.APPEND, StandardOpenOption.WRITE)
                    : Files.newOutputStream(s.path(), StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING, StandardOpenOption.WRITE);

            PrintStream ps = new PrintStream(os);
            return new OutTarget() {
                @Override public PrintStream ps() { return ps; }
                @Override public void close() { ps.close(); }
            };
        }
    }

    // =============================================================================
    // Commands (Command pattern)
    // =============================================================================
    interface Cmd { void execute(Ctx ctx); }

    interface CommandFactory { Cmd create(String name, List<String> argv); }

    static final class CommandFactoryImpl implements CommandFactory {
        private final BuiltinRegistry builtins;
        private final PathResolver resolver;

        CommandFactoryImpl(BuiltinRegistry builtins, PathResolver resolver) {
            this.builtins = builtins;
            this.resolver = resolver;
        }

        @Override public Cmd create(String name, List<String> argv) {
            Cmd b = builtins.get(name);
            return (b != null) ? b : new ExternalCmd(name, argv, resolver);
        }
    }

    static abstract class Builtin implements Cmd {
        @Override public final void execute(Ctx ctx) {
            if (ctx.redirs().err().isPresent()) {
                try {
                    IO.touch(ctx.redirs().err().get());
                } catch (IOException e) {
                    System.err.println("Redirection error: " + e.getMessage());
                }
            }

            try (OutTarget t = IO.out(ctx.redirs().out(), ctx.out())) {
                run(ctx, t.ps());
            } catch (IOException e) {
                System.err.println("Redirection error: " + e.getMessage());
            }
        }

        protected abstract void run(Ctx ctx, PrintStream out);
    }

    static final class BuiltinRegistry {
        private final Map<String, Cmd> map;

        BuiltinRegistry(PathResolver resolver) {
            // Use insertion-ordered map for stable completion behavior.
            var tmp = new LinkedHashMap<String, Cmd>();
            tmp.put("exit", new Exit());
            tmp.put("echo", new Echo());
            tmp.put("pwd", new Pwd());
            tmp.put("cd", new Cd());
            tmp.put("history", new History());
            tmp.put("type", new Type(this, resolver));
            this.map = Collections.unmodifiableMap(tmp);
        }

        Cmd get(String name) { return map.get(name); }
        boolean isBuiltin(String name) { return map.containsKey(name); }
        List<String> names() { return List.copyOf(map.keySet()); }
    }

    static final class ExternalCmd implements Cmd {
        private final String name;
        private final List<String> argv;
        private final PathResolver resolver;

        ExternalCmd(String name, List<String> argv, PathResolver resolver) {
            this.name = name;
            this.argv = List.copyOf(argv);
            this.resolver = resolver;
        }

        @Override public void execute(Ctx ctx) {
            try {
                if (resolver.findExecutable(name).isEmpty()) {
                    System.out.println(name + ": command not found");
                    return;
                }

                var pb = new ProcessBuilder(new ArrayList<>(argv));
                pb.directory(RuntimeState.env.cwd().toFile());

                boolean inPiped = ctx.in() != System.in;
                pb.redirectInput(inPiped ? ProcessBuilder.Redirect.PIPE : ProcessBuilder.Redirect.INHERIT);

                // stdout
                if (ctx.redirs().out().isPresent()) {
                    RSpec s = ctx.redirs().out().get();
                    IO.touch(s);
                    pb.redirectOutput(s.mode() == RMode.APPEND
                            ? ProcessBuilder.Redirect.appendTo(s.path().toFile())
                            : ProcessBuilder.Redirect.to(s.path().toFile()));
                } else if (ctx.out() != System.out) {
                    pb.redirectOutput(ProcessBuilder.Redirect.PIPE);
                } else {
                    pb.redirectOutput(ProcessBuilder.Redirect.INHERIT);
                }

                // stderr
                if (ctx.redirs().err().isPresent()) {
                    RSpec s = ctx.redirs().err().get();
                    IO.touch(s);
                    pb.redirectError(s.mode() == RMode.APPEND
                            ? ProcessBuilder.Redirect.appendTo(s.path().toFile())
                            : ProcessBuilder.Redirect.to(s.path().toFile()));
                } else {
                    pb.redirectError(ProcessBuilder.Redirect.INHERIT);
                }

                Process p = pb.start();

                Thread inPump = null;
                if (inPiped) {
                    InputStream src = ctx.in();
                    OutputStream dst = p.getOutputStream();
                    inPump = new Thread(() -> pump(src, dst));
                    inPump.start();
                }

                Thread outPump = null;
                boolean outPiped = ctx.redirs().out().isEmpty() && ctx.out() != System.out;
                if (outPiped) {
                    InputStream src = p.getInputStream();
                    PrintStream dst = ctx.out();
                    outPump = new Thread(() -> pump(src, dst));
                    outPump.start();
                }

                p.waitFor();
                if (inPump != null) inPump.join();
                if (outPump != null) outPump.join();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                System.out.println(name + ": command not found");
            } catch (IOException e) {
                System.out.println(name + ": command not found");
            }
        }

        private static void pump(InputStream in, OutputStream out) {
            try (out) { // ALWAYS close child's stdin to signal EOF
                byte[] buf = new byte[8192];
                int n;
                while ((n = in.read(buf)) != -1) {
                    out.write(buf, 0, n);
                    out.flush();
                }
            } catch (IOException ignored) {
                // Keep shell resilient; but still ensure child's stdin is closed via try-with-resources.
            }
        }

        private static void pump(InputStream in, PrintStream out) {
            try {
                byte[] buf = new byte[8192];
                int n;
                while ((n = in.read(buf)) != -1) {
                    out.write(buf, 0, n);
                    out.flush();
                }
            } catch (IOException ignored) { }
        }
    }

    static final class PipelineCommand implements Cmd {
        private final List<List<String>> stages;
        private final Redirs lastRedirs; // applies to last stage only
        private final CommandFactory factory;

        PipelineCommand(List<List<String>> stages, Redirs lastRedirs, CommandFactory factory) {
            this.stages = List.copyOf(stages);
            this.lastRedirs = lastRedirs;
            this.factory = factory;
        }

        @Override
        public void execute(Ctx ctx) {
            if (stages.isEmpty()) return;

            // Single-stage: just run with the given redirections.
            if (stages.size() == 1) {
                var argv = stages.get(0);
                if (argv.isEmpty()) return;
                factory.create(argv.get(0), argv)
                        .execute(new Ctx(argv, lastRedirs, ctx.in(), ctx.out(), ctx.err()));
                return;
            }

            List<Thread> workers = new ArrayList<>(stages.size() - 1);
            InputStream nextIn = ctx.in();

            for (int i = 0; i < stages.size(); i++) {
                var argv = stages.get(i);
                if (argv.isEmpty()) return;

                boolean last = (i == stages.size() - 1);
                InputStream stageIn = nextIn;

                if (!last) {
                    try {
                        PipedInputStream pipeIn = new PipedInputStream();
                        PipedOutputStream pipeOut = new PipedOutputStream(pipeIn);
                        PrintStream stageOut = new PrintStream(pipeOut, true);

                        Cmd cmd = factory.create(argv.get(0), argv);
                        Ctx stageCtx = new Ctx(argv, Redirs.none(), stageIn, stageOut, ctx.err());

                        Thread t = new Thread(() -> {
                            try (stageOut; pipeOut) { // explicit close of both ends
                                cmd.execute(stageCtx);
                            } catch (Exception ignored) { }
                        });
                        t.start();
                        workers.add(t);

                        nextIn = pipeIn;
                    } catch (IOException e) {
                        String msg = e.getMessage();
                        if (msg != null && !msg.isEmpty()) System.out.println(msg);
                        return;
                    }
                } else {
                    Cmd cmd = factory.create(argv.get(0), argv);
                    Ctx lastCtx = new Ctx(argv, lastRedirs, stageIn, ctx.out(), ctx.err());
                    cmd.execute(lastCtx);
                }
            }

            for (Thread t : workers) {
                try {
                    t.join();
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    return;
                }
            }
        }
    }

    // =============================================================================
    // Builtins
    // =============================================================================
    static final class Exit extends Builtin {
        @Override protected void run(Ctx ctx, PrintStream out) {
            int code = 0;
            if (ctx.args().size() > 1) {
                try { code = Integer.parseInt(ctx.args().get(1)); }
                catch (NumberFormatException ignored) { }
            }
            System.exit(code);
        }
    }

    static final class Echo extends Builtin {
        @Override protected void run(Ctx ctx, PrintStream out) {
            if (ctx.args().size() == 1) { out.println(); return; }
            var sb = new StringBuilder();
            for (int i = 1; i < ctx.args().size(); i++) {
                if (i > 1) sb.append(" ");
                sb.append(ctx.args().get(i));
            }
            out.println(sb.toString());
        }
    }

    static final class Pwd extends Builtin {
        @Override protected void run(Ctx ctx, PrintStream out) {
            out.println(RuntimeState.env.cwd());
        }
    }

    static final class Cd extends Builtin {
        @Override protected void run(Ctx ctx, PrintStream out) {
            if (ctx.args().size() > 2) return;

            final String original = (ctx.args().size() == 1) ? "~" : ctx.args().get(1);
            String target = original;
            String home = RuntimeState.env.home();

            if ("~".equals(target)) {
                if (home == null) { out.println("cd: HOME not set"); return; }
                target = home;
            } else if (target.startsWith("~") && target.startsWith("~" + File.separator)) {
                if (home == null) { out.println("cd: HOME not set"); return; }
                target = home + target.substring(1);
            }

            Path p = Paths.get(target);
            if (!p.isAbsolute()) p = RuntimeState.env.cwd().resolve(p);
            Path resolved = p.normalize();

            if (Files.exists(resolved) && Files.isDirectory(resolved)) RuntimeState.env.cwd(resolved);
            else out.println("cd: " + original + ": No such file or directory");
        }
    }

    static final class History extends Builtin {
        @Override protected void run(Ctx ctx, PrintStream out) {
            OptionalInt limit = parseLimit(ctx.args());
            HistoryStore.View v = RuntimeState.history.view(limit);
            var list = v.lines();
            int baseIndex = v.startIndex(); // 0-based in the full history
            for (int i = 0; i < list.size(); i++) {
                out.printf("%5d  %s%n", baseIndex + i + 1, list.get(i));
            }
        }

        private static OptionalInt parseLimit(List<String> argv) {
            if (argv.size() != 2) return OptionalInt.empty();
            try {
                return OptionalInt.of(Integer.parseInt(argv.get(1)));
            } catch (NumberFormatException ignored) {
                return OptionalInt.empty();
            }
        }
    }

    static final class Type extends Builtin {
        private final BuiltinRegistry builtins;
        private final PathResolver resolver;

        Type(BuiltinRegistry builtins, PathResolver resolver) {
            this.builtins = builtins;
            this.resolver = resolver;
        }

        @Override protected void run(Ctx ctx, PrintStream out) {
            if (ctx.args().size() < 2) return;
            String t = ctx.args().get(1);

            if (builtins.isBuiltin(t)) { out.println(t + " is a shell builtin"); return; }

            var p = resolver.findExecutable(t);
            if (p.isPresent()) out.println(t + " is " + p.get().toAbsolutePath());
            else out.println(t + ": not found");
        }
    }

    // =============================================================================
    // History store + runtime state
    // =============================================================================
    static final class HistoryStore {
        record View(int startIndex, List<String> lines) { }

        private final List<String> entries = new ArrayList<>();

        void add(String line) { entries.add(line); }

        List<String> snapshot() { return List.copyOf(entries); }

        View view(OptionalInt limitOpt) {
            int total = entries.size();
            int start = 0;

            if (limitOpt != null && limitOpt.isPresent()) {
                int n = limitOpt.getAsInt();
                if (n <= 0) return new View(total, List.of());
                if (n < total) start = total - n;
            }

            return new View(start, List.copyOf(entries.subList(start, total)));
        }
    }

    static final class RuntimeState {
        static final Env env = new Env();
        static final HistoryStore history = new HistoryStore();
    }

    // =============================================================================
    // Interactive input + TAB completion + History navigation (Up arrow)
    // =============================================================================
    interface CompletionEngine { Completion completeFirstWord(String firstWord); }

    record Completion(Kind kind, String suffix, List<String> matches) {
        enum Kind { SUFFIX, NO_MATCH, AMBIGUOUS, ALREADY_COMPLETE, NOT_APPLICABLE }

        static Completion suffix(String s) { return new Completion(Kind.SUFFIX, s, List.of()); }
        static Completion amb(List<String> m) { return new Completion(Kind.AMBIGUOUS, null, List.copyOf(m)); }
        static Completion of(Kind k) { return new Completion(k, null, List.of()); }
    }

    static final class CommandNameCompleter implements CompletionEngine {
        private final BuiltinRegistry builtins;
        private final PathResolver resolver;

        CommandNameCompleter(BuiltinRegistry builtins, PathResolver resolver) {
            this.builtins = builtins;
            this.resolver = resolver;
        }

        @Override public Completion completeFirstWord(String cur) {
            if (cur == null || cur.isEmpty()) return Completion.of(Completion.Kind.NOT_APPLICABLE);

            Set<String> matches = new LinkedHashSet<>();
            for (String b : builtins.names()) if (b.startsWith(cur)) matches.add(b);
            matches.addAll(resolver.execNamesByPrefix(cur));

            if (matches.isEmpty()) return Completion.of(Completion.Kind.NO_MATCH);

            if (matches.size() == 1) {
                String only = matches.iterator().next();
                if (only.equals(cur)) return Completion.of(Completion.Kind.ALREADY_COMPLETE);
                return Completion.suffix(only.substring(cur.length()) + " ");
            }

            var sorted = new TreeSet<>(matches);
            String lcp = lcp(sorted);
            if (lcp.length() > cur.length()) return Completion.suffix(lcp.substring(cur.length()));
            return Completion.amb(new ArrayList<>(sorted));
        }

        private static String lcp(Iterable<String> it) {
            String first = null;
            for (String s : it) { first = s; break; }
            if (first == null || first.isEmpty()) return "";
            int end = first.length();
            for (String s : it) {
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
        private static final int ESC = 27;

        private final InputStream in;
        private final CompletionEngine completer;
        private final TerminalMode tty = new TerminalMode();
        private final String prompt;

        private boolean rawEnabled;

        // TAB completion state
        private int tabs;
        private String snap;
        private List<String> amb = List.of();

        // History navigation state
        private int historyPos = -1;          // -1 = not browsing
        private String historySavedLine = ""; // line buffer before browsing

        InteractiveInput(InputStream in, CompletionEngine completer, String prompt) {
            this.in = in;
            this.completer = completer;
            this.prompt = prompt;
            try { rawEnabled = tty.enableRawMode(); } catch (Exception ignored) { rawEnabled = false; }
        }

        String readLine() throws IOException {
            var buf = new StringBuilder();

            while (true) {
                int b = in.read();
                if (b == -1) {
                    if (buf.length() > 0) { System.out.print("\n"); completionReset(); historyReset(); return buf.toString(); }
                    return null;
                }

                if (b == ESC) {
                    if (handleEscapeSequence(buf)) continue;
                    completionReset();
                    continue;
                }

                char c = (char) b;

                if (c == '\n') { System.out.print("\n"); completionReset(); historyReset(); return buf.toString(); }

                if (c == '\r') {
                    // Preserve behavior: treat CR as newline. Best-effort skip of next LF if present.
                    if (in.markSupported()) {
                        in.mark(1);
                        int n = in.read();
                        if (n != '\n' && n != -1) in.reset();
                    }
                    System.out.print("\n");
                    completionReset();
                    historyReset();
                    return buf.toString();
                }

                if (c == '\t') { onTab(buf); continue; }

                if (b == 127 || b == 8) {
                    if (buf.length() > 0) {
                        buf.deleteCharAt(buf.length() - 1);
                        System.out.print("\b \b");
                    }
                    completionReset();
                    // If user edits while browsing history, stop browsing (typical readline behavior).
                    historyAbortBrowsing();
                    continue;
                }

                buf.append(c);
                System.out.print(c);
                completionReset();
                historyAbortBrowsing();
            }
        }

        private boolean handleEscapeSequence(StringBuilder buf) throws IOException {
            int b2 = in.read();
            if (b2 == -1) return true;

            if (b2 != '[') return true;

            int b3 = in.read();
            if (b3 == -1) return true;

            if (b3 == 'A') { // UP
                onHistoryUp(buf);
                return true;
            }
            if (b3 == 'B') { // DOWN (not required by the stage, but keeps state consistent)
                onHistoryDown(buf);
                return true;
            }

            return true;
        }

        private void onHistoryUp(StringBuilder buf) {
            var snap = RuntimeState.history.snapshot();
            if (snap.isEmpty()) { bell(); return; }

            if (historyPos == -1) {
                historySavedLine = buf.toString();
                historyPos = snap.size(); // one-past-end
            }
            if (historyPos <= 0) { bell(); return; }

            historyPos--;
            replaceBufferAndRedraw(buf, snap.get(historyPos));
        }

        private void onHistoryDown(StringBuilder buf) {
            if (historyPos == -1) { bell(); return; }

            var snap = RuntimeState.history.snapshot();
            if (historyPos >= snap.size() - 1) {
                historyPos = -1;
                replaceBufferAndRedraw(buf, historySavedLine);
                return;
            }

            historyPos++;
            replaceBufferAndRedraw(buf, snap.get(historyPos));
        }

        private void replaceBufferAndRedraw(StringBuilder buf, String next) {
            int oldLen = buf.length();
            buf.setLength(0);
            buf.append(next);

            // Redraw current input line.
            System.out.print('\r');
            System.out.print(prompt);
            System.out.print(next);

            int extra = oldLen - next.length();
            if (extra > 0) System.out.print(" ".repeat(extra));

            System.out.print('\r');
            System.out.print(prompt);
            System.out.print(next);
            System.out.flush();

            completionReset();
        }

        private void historyAbortBrowsing() {
            // If user starts editing after recalling history, stop browsing.
            if (historyPos != -1) {
                historyPos = -1;
                historySavedLine = "";
            }
        }

        private void historyReset() {
            historyPos = -1;
            historySavedLine = "";
        }

        private void onTab(StringBuilder buf) {
            if (buf.length() == 0) return;

            for (int i = 0; i < buf.length(); i++) if (Character.isWhitespace(buf.charAt(i))) return;

            String cur = buf.toString();
            Completion r = completer.completeFirstWord(cur);

            if (r.kind() == Completion.Kind.SUFFIX) {
                buf.append(r.suffix());
                System.out.print(r.suffix());
                completionReset();
                historyAbortBrowsing();
                return;
            }
            if (r.kind() == Completion.Kind.NO_MATCH) {
                bell();
                completionReset();
                return;
            }
            if (r.kind() == Completion.Kind.AMBIGUOUS) {
                if (tabs == 0 || snap == null || !snap.equals(cur)) {
                    bell();
                    tabs = 1;
                    snap = cur;
                    amb = r.matches();
                    return;
                }
                if (tabs == 1 && snap.equals(cur)) {
                    System.out.print("\n");
                    if (!amb.isEmpty()) System.out.print(String.join("  ", amb));
                    System.out.print("\n");
                    System.out.print(prompt);
                    System.out.print(cur);
                    System.out.flush();
                    completionReset();
                    return;
                }
            }
            completionReset();
        }

        private void bell() {
            System.out.print(BEL);
            System.out.flush();
        }

        private void completionReset() {
            tabs = 0;
            snap = null;
            amb = List.of();
        }

        @Override public void close() {
            try { if (rawEnabled) tty.disableRawMode(); } catch (Exception ignored) { }
        }
    }

    // =============================================================================
    // Terminal raw mode helper
    // =============================================================================
    static final class TerminalMode {
        private String prev;

        boolean enableRawMode() throws Exception {
            if (System.console() == null) return false;
            prev = exec("sh", "-c", "stty -g < /dev/tty").trim();

            // Cbreak-ish mode: character-at-a-time input, no echo, but DO NOT disable output processing.
            exec("sh", "-c", "stty -icanon -echo min 1 time 0 < /dev/tty");
            return true;
        }

        void disableRawMode() throws Exception {
            if (prev == null) return;
            exec("sh", "-c", "stty " + prev + " < /dev/tty");
        }

        private static String exec(String... cmd) throws Exception {
            Process p = new ProcessBuilder(cmd).redirectErrorStream(true).start();
            try (InputStream is = p.getInputStream()) {
                byte[] b = is.readAllBytes();
                p.waitFor();
                return new String(b);
            }
        }
    }
}