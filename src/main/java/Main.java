import java.io.*;
import java.nio.file.*;
import java.util.*;
import java.util.function.BiConsumer;

public class Main {
    static final String PROMPT = "$ ";

    public static void main(String[] args) {
        var env = ShellRuntime.env;
        var resolver = new PathResolver(env);
        var builtins = new Builtins(resolver);
        var factory = new Factory(builtins, resolver);
        var input = new InteractiveInput(System.in, new Completer(builtins, resolver), PROMPT);
        new Shell(input, factory, new Parser(), PROMPT).run();
    }

    // ===================== REPL =====================

    static final class Shell {
        private final InteractiveInput input;
        private final Factory factory;
        private final Parser parser;
        private final String prompt;

        Shell(InteractiveInput input, Factory factory, Parser parser, String prompt) {
            this.input = input; this.factory = factory; this.parser = parser; this.prompt = prompt;
        }

        void run() {
            System.out.print(prompt);
            try {
                for (String line; (line = input.readLine()) != null; ) {
                    handle(line);
                    System.out.print(prompt);
                }
            } catch (IOException e) {
                System.err.println("Fatal I/O Error: + " + e.getMessage());
            } finally {
                input.close();
            }
        }

        private void handle(String line) {
            try {
                var tokens = Tokenizer.tokenize(line);
                if (tokens.isEmpty()) return;

                ShellRuntime.history.add(line);

                var parsed = parser.parse(tokens);
                var root = ExecutionContext.system();

                switch (parsed) {
                    case Parsed.Simple s -> {
                        var cl = s.line();
                        if (cl.args().isEmpty()) return;
                        var cmd = factory.create(cl.args().get(0), cl.args());
                        cmd.execute(root.withArgsAndRedirs(cl.args(), cl.redirections()));
                    }
                    case Parsed.Pipeline p -> new PipelineCmd(p.stages(), p.redirections(), factory)
                            .execute(root.withArgsAndRedirs(List.of(), Redirections.none()));
                }
            } catch (Exception e) {
                var msg = e.getMessage();
                if (msg != null && !msg.isEmpty()) System.out.println(msg);
            }
        }
    }

    // ===================== Parsed types =====================

    sealed interface Parsed permits Parsed.Simple, Parsed.Pipeline {
        record Simple(CommandLine line) implements Parsed {}
        record Pipeline(List<List<String>> stages, Redirections redirections) implements Parsed {
            Pipeline {
                var tmp = new ArrayList<List<String>>(stages.size());
                for (var s : stages) tmp.add(List.copyOf(s));
                stages = List.copyOf(tmp);
            }
        }
    }

    // ===================== Interactive input + completion =====================

    interface CompletionEngine { Completion completeFirstWord(String firstWord); }

    record Completion(Kind kind, String suffix, List<String> matches) {
        enum Kind { SUFFIX, NO_MATCH, AMBIGUOUS, ALREADY_COMPLETE, NOT_APPLICABLE }
        static Completion of(Kind k) { return new Completion(k, null, List.of()); }
        static Completion suffix(String s) { return new Completion(Kind.SUFFIX, s, List.of()); }
        static Completion ambiguous(List<String> m) { return new Completion(Kind.AMBIGUOUS, null, List.copyOf(m)); }
    }

    static final class Completer implements CompletionEngine {
        private final Builtins builtins;
        private final PathResolver resolver;

        Completer(Builtins builtins, PathResolver resolver) { this.builtins = builtins; this.resolver = resolver; }

        @Override public Completion completeFirstWord(String w) {
            if (w == null || w.isEmpty()) return Completion.of(Completion.Kind.NOT_APPLICABLE);

            Set<String> matches = new LinkedHashSet<>();
            for (var b : builtins.names()) if (b.startsWith(w)) matches.add(b);
            matches.addAll(resolver.findExecutableNamesByPrefix(w));

            if (matches.isEmpty()) return Completion.of(Completion.Kind.NO_MATCH);
            if (matches.size() == 1) {
                var only = matches.iterator().next();
                if (only.equals(w)) return Completion.of(Completion.Kind.ALREADY_COMPLETE);
                return Completion.suffix(only.substring(w.length()) + " ");
            }

            var sorted = new TreeSet<>(matches);
            var lcp = lcp(sorted);
            if (lcp.length() > w.length()) return Completion.suffix(lcp.substring(w.length()));
            return Completion.ambiguous(new ArrayList<>(sorted));
        }

        private static String lcp(Iterable<String> it) {
            String first = null;
            for (var s : it) { first = s; break; }
            if (first == null || first.isEmpty()) return "";
            int end = first.length();
            for (var s : it) {
                int max = Math.min(end, s.length()), i = 0;
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
        private final TerminalMode terminalMode = new TerminalMode();
        private final String prompt;

        private boolean rawEnabled;
        private int consecutiveTabs;
        private String snapshot;
        private List<String> ambiguous = List.of();

        InteractiveInput(InputStream in, CompletionEngine completer, String prompt) {
            this.in = in; this.completer = completer; this.prompt = prompt;
            try { rawEnabled = terminalMode.enableRawMode(); } catch (Exception ignored) { rawEnabled = false; }
        }

        String readLine() throws IOException {
            var buf = new StringBuilder();
            while (true) {
                int b = in.read();
                if (b == -1) {
                    if (buf.length() > 0) { System.out.print("\n"); resetTab(); return buf.toString(); }
                    return null;
                }
                char c = (char) b;

                if (c == '\n') { System.out.print("\n"); resetTab(); return buf.toString(); }
                if (c == '\r') {
                    in.mark(1);
                    int next = in.read();
                    if (next != '\n' && next != -1) { /* ignore */ }
                    System.out.print("\n"); resetTab(); return buf.toString();
                }

                if (c == '\t') { handleTab(buf); continue; }

                if (b == 127 || b == 8) {
                    if (buf.length() > 0) { buf.deleteCharAt(buf.length() - 1); System.out.print("\b \b"); }
                    resetTab();
                    continue;
                }

                buf.append(c);
                System.out.print(c);
                resetTab();
            }
        }

        private void handleTab(StringBuilder buf) {
            if (buf.length() == 0) return;
            for (int i = 0; i < buf.length(); i++) if (Character.isWhitespace(buf.charAt(i))) return;

            String current = buf.toString();
            var r = completer.completeFirstWord(current);

            if (r.kind() == Completion.Kind.SUFFIX) {
                buf.append(r.suffix());
                System.out.print(r.suffix());
                resetTab();
                return;
            }

            if (r.kind() == Completion.Kind.NO_MATCH) {
                System.out.print(BEL); System.out.flush();
                resetTab();
                return;
            }

            if (r.kind() == Completion.Kind.AMBIGUOUS) {
                if (consecutiveTabs == 0 || snapshot == null || !snapshot.equals(current)) {
                    System.out.print(BEL); System.out.flush();
                    consecutiveTabs = 1; snapshot = current; ambiguous = r.matches();
                    return;
                }
                if (consecutiveTabs == 1 && snapshot.equals(current)) {
                    System.out.print("\n");
                    if (!ambiguous.isEmpty()) System.out.print(String.join("  ", ambiguous));
                    System.out.print("\n");
                    System.out.print(prompt);
                    System.out.print(current);
                    System.out.flush();
                    resetTab();
                    return;
                }
            }
            resetTab();
        }

        private void resetTab() { consecutiveTabs = 0; snapshot = null; ambiguous = List.of(); }

        @Override public void close() {
            if (rawEnabled) {
                try { terminalMode.disableRawMode(); } catch (Exception ignored) {}
            }
        }
    }

    static final class TerminalMode {
        boolean enableRawMode() throws IOException, InterruptedException {
            return exec("stty -icanon -echo min 1 time 0 < /dev/tty") == 0;
        }
        void disableRawMode() throws IOException, InterruptedException {
            exec("stty sane < /dev/tty");
        }
        private static int exec(String cmd) throws IOException, InterruptedException {
            return new ProcessBuilder("/bin/sh", "-c", cmd).start().waitFor();
        }
    }

    // ===================== Tokenizer =====================

    static final class Tokenizer {
        private enum S { DEFAULT, ESC, SQ, DQ, DQ_ESC }
        static List<String> tokenize(String input) {
            var out = new ArrayList<String>();
            var cur = new StringBuilder();
            S st = S.DEFAULT;
            boolean inTok = false;

            for (int i = 0, n = input.length(); i < n; i++) {
                char c = input.charAt(i);
                switch (st) {
                    case DEFAULT -> {
                        if (Character.isWhitespace(c)) {
                            if (inTok) { out.add(cur.toString()); cur.setLength(0); inTok = false; }
                        } else if (c == '\\') { st = S.ESC; inTok = true; }
                        else if (c == '\'') { st = S.SQ; inTok = true; }
                        else if (c == '"') { st = S.DQ; inTok = true; }
                        else { cur.append(c); inTok = true; }
                    }
                    case ESC -> { cur.append(c); st = S.DEFAULT; }
                    case SQ -> { if (c == '\'') st = S.DEFAULT; else cur.append(c); }
                    case DQ -> {
                        if (c == '"') st = S.DEFAULT;
                        else if (c == '\\') st = S.DQ_ESC;
                        else cur.append(c);
                    }
                    case DQ_ESC -> {
                        if (c == '\\' || c == '"') cur.append(c);
                        else { cur.append('\\'); cur.append(c); }
                        st = S.DQ;
                    }
                }
            }
            if (inTok) out.add(cur.toString());
            return out;
        }
    }

    // ===================== Parser (redir + pipeline) =====================

    enum RedirectMode { TRUNCATE, APPEND }
    enum RedirectStream { STDOUT, STDERR }
    record RedirectSpec(RedirectStream stream, Path path, RedirectMode mode) {}
    record Redirections(Optional<RedirectSpec> stdout, Optional<RedirectSpec> stderr) {
        static Redirections none() { return new Redirections(Optional.empty(), Optional.empty()); }
    }
    record CommandLine(List<String> args, Redirections redirections) { CommandLine { args = List.copyOf(args); } }

    static final class Parser {
        private static final String PIPE="|", GT=">", ONE_GT="1>", DGT=">>", ONE_DGT="1>>", TWO_GT="2>", TWO_DGT="2>>";

        Parsed parse(List<String> tokens) {
            var base = parseRedirs(tokens);
            var split = splitPipeline(base.args());
            return split.map(stages -> (Parsed) new Parsed.Pipeline(stages, base.redirections()))
                    .orElseGet(() -> new Parsed.Simple(base));
        }

        private static Optional<List<List<String>>> splitPipeline(List<String> args) {
            boolean saw = false;
            var stages = new ArrayList<List<String>>();
            var cur = new ArrayList<String>();

            for (var t : args) {
                if (PIPE.equals(t)) {
                    saw = true;
                    if (cur.isEmpty()) return Optional.empty();
                    stages.add(List.copyOf(cur));
                    cur.clear();
                } else cur.add(t);
            }
            if (!saw || cur.isEmpty()) return Optional.empty();
            stages.add(List.copyOf(cur));
            return stages.size() < 2 ? Optional.empty() : Optional.of(List.copyOf(stages));
        }

        private static CommandLine parseRedirs(List<String> t) {
            if (t.size() >= 2) {
                String op = t.get(t.size() - 2), file = t.get(t.size() - 1);
                if (GT.equals(op) || ONE_GT.equals(op)) return drop2(t, new Redirections(
                        Optional.of(new RedirectSpec(RedirectStream.STDOUT, Paths.get(file), RedirectMode.TRUNCATE)),
                        Optional.empty()));
                if (DGT.equals(op) || ONE_DGT.equals(op)) return drop2(t, new Redirections(
                        Optional.of(new RedirectSpec(RedirectStream.STDOUT, Paths.get(file), RedirectMode.APPEND)),
                        Optional.empty()));
                if (TWO_GT.equals(op)) return drop2(t, new Redirections(Optional.empty(),
                        Optional.of(new RedirectSpec(RedirectStream.STDERR, Paths.get(file), RedirectMode.TRUNCATE))));
                if (TWO_DGT.equals(op)) return drop2(t, new Redirections(Optional.empty(),
                        Optional.of(new RedirectSpec(RedirectStream.STDERR, Paths.get(file), RedirectMode.APPEND))));
            }
            return new CommandLine(t, Redirections.none());
        }

        private static CommandLine drop2(List<String> t, Redirections r) {
            return new CommandLine(new ArrayList<>(t.subList(0, t.size() - 2)), r);
        }
    }

    // ===================== Env + PATH resolver =====================

    static final class Environment {
        private Path cwd = Paths.get(System.getProperty("user.dir")).toAbsolutePath().normalize();
        Path cwd() { return cwd; }
        void cwd(Path p) { cwd = p.toAbsolutePath().normalize(); }
        String home() { return System.getenv("HOME"); }

        List<Path> pathDirs() {
            String s = System.getenv("PATH");
            if (s == null || s.isEmpty()) return List.of();
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
        private final Environment env;
        PathResolver(Environment env) { this.env = env; }

        Optional<Path> findExecutable(String name) {
            if (hasSep(name)) {
                Path p = Paths.get(name);
                if (!p.isAbsolute()) p = env.cwd().resolve(p).normalize();
                return (Files.isRegularFile(p) && Files.isExecutable(p)) ? Optional.of(p) : Optional.empty();
            }
            for (var dir : env.pathDirs()) {
                var c = dir.resolve(name);
                if (Files.isRegularFile(c) && Files.isExecutable(c)) return Optional.of(c);
            }
            return Optional.empty();
        }

        Set<String> findExecutableNamesByPrefix(String prefix) {
            if (prefix == null || prefix.isEmpty() || hasSep(prefix)) return Set.of();
            var out = new LinkedHashSet<String>();
            for (var dir : env.pathDirs()) {
                if (dir == null || !Files.isDirectory(dir)) continue;
                try (DirectoryStream<Path> ds = Files.newDirectoryStream(dir)) {
                    for (var p : ds) {
                        var fn = p.getFileName();
                        String name = (fn == null) ? null : fn.toString();
                        if (name == null || !name.startsWith(prefix)) continue;
                        if (Files.isRegularFile(p) && Files.isExecutable(p)) out.add(name);
                    }
                } catch (Exception ignored) {}
            }
            return out;
        }

        private static boolean hasSep(String s) {
            return s.indexOf('/') >= 0 || s.indexOf('\\') >= 0 || s.indexOf(File.separatorChar) >= 0;
        }
    }

    // ===================== Exec context + redirection =====================

    record ExecutionContext(List<String> args, Redirections redirs, InputStream in, PrintStream out, PrintStream err) {
        ExecutionContext { args = List.copyOf(args); }
        static ExecutionContext system() { return new ExecutionContext(List.of(), Redirections.none(), System.in, System.out, System.err); }
        ExecutionContext withArgsAndRedirs(List<String> a, Redirections r) { return new ExecutionContext(a, r, in, out, err); }
    }

    static final class Redir {
        static void touch(RedirectSpec s) throws IOException {
            if (s.mode() == RedirectMode.APPEND) {
                try (var os = Files.newOutputStream(s.path(), StandardOpenOption.CREATE, StandardOpenOption.APPEND, StandardOpenOption.WRITE)) {}
            } else {
                try (var os = Files.newOutputStream(s.path(), StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING, StandardOpenOption.WRITE)) {}
            }
        }

        static AutoCloseablePrintStream stdout(Redirections r, PrintStream fallback) throws IOException {
            if (r.stdout().isEmpty()) return new AutoCloseablePrintStream(fallback, false);
            var s = r.stdout().get();
            OutputStream os = (s.mode() == RedirectMode.APPEND)
                    ? Files.newOutputStream(s.path(), StandardOpenOption.CREATE, StandardOpenOption.APPEND, StandardOpenOption.WRITE)
                    : Files.newOutputStream(s.path(), StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING, StandardOpenOption.WRITE);
            return new AutoCloseablePrintStream(new PrintStream(os), true);
        }

        record AutoCloseablePrintStream(PrintStream ps, boolean close) implements AutoCloseable {
            @Override public void close() { if (close) ps.close(); }
        }
    }

    // ===================== Commands =====================

    interface Cmd { void execute(ExecutionContext ctx); }

    static final class Factory {
        private final Builtins builtins;
        private final PathResolver resolver;
        Factory(Builtins builtins, PathResolver resolver) { this.builtins = builtins; this.resolver = resolver; }
        Cmd create(String name, List<String> args) { return builtins.get(name).orElseGet(() -> new ExternalCmd(name, List.copyOf(args), resolver)); }
    }

    static final class Builtins {
        private final Map<String, Cmd> m;
        Builtins(PathResolver resolver) {
            BiConsumer<ExecutionContext, PrintStream> exit = (c, o) -> {
                int code = 0;
                if (c.args().size() > 1) try { code = Integer.parseInt(c.args().get(1)); } catch (NumberFormatException ignored) {}
                System.exit(code);
            };
            BiConsumer<ExecutionContext, PrintStream> echo = (c, o) -> {
                if (c.args().size() == 1) { o.println(); return; }
                var sb = new StringBuilder();
                for (int i = 1; i < c.args().size(); i++) { if (i > 1) sb.append(" "); sb.append(c.args().get(i)); }
                o.println(sb);
            };
            BiConsumer<ExecutionContext, PrintStream> pwd = (c, o) -> o.println(ShellRuntime.env.cwd());
            BiConsumer<ExecutionContext, PrintStream> cd = (c, o) -> {
                if (c.args().size() > 2) return;

                final String originalArg = (c.args().size() == 1) ? "~" : c.args().get(1);
                String target = originalArg;
                String home = ShellRuntime.env.home();

                if ("~".equals(target)) {
                    if (home == null) { o.println("cd: HOME not set"); return; }
                    target = home;
                } else if (target.startsWith("~") && target.startsWith("~" + File.separator)) {
                    if (home == null) { o.println("cd: HOME not set"); return; }
                    target = home + target.substring(1);
                }

                Path p = Paths.get(target);
                if (!p.isAbsolute()) p = ShellRuntime.env.cwd().resolve(p);
                Path resolved = p.normalize();

                if (Files.exists(resolved) && Files.isDirectory(resolved)) ShellRuntime.env.cwd(resolved);
                else o.println("cd: " + originalArg + ": No such file or directory");
            };
            BiConsumer<ExecutionContext, PrintStream> history = (c, o) -> {
                var h = ShellRuntime.history.snapshot();
                for (int i = 0; i < h.size(); i++) o.printf("%5d  %s%n", i + 1, h.get(i));
            };

            // "type" needs the registry and resolver, so it's filled after m exists.
            var tmp = new HashMap<String, Cmd>();
            tmp.put("exit", new BuiltinCmd(exit));
            tmp.put("echo", new BuiltinCmd(echo));
            tmp.put("pwd", new BuiltinCmd(pwd));
            tmp.put("cd", new BuiltinCmd(cd));
            tmp.put("history", new BuiltinCmd(history));
            this.m = Collections.unmodifiableMap(tmp);

            tmp.put("type", new BuiltinCmd((c, o) -> {
                if (c.args().size() < 2) return;
                String t = c.args().get(1);
                if (isBuiltin(t)) { o.println(t + " is a shell builtin"); return; }
                var p = resolver.findExecutable(t);
                if (p.isPresent()) o.println(t + " is " + p.get().toAbsolutePath());
                else o.println(t + ": not found");
            }));
        }

        Optional<Cmd> get(String name) { return Optional.ofNullable(m.get(name)); }
        boolean isBuiltin(String name) { return m.containsKey(name); }
        List<String> names() { return new ArrayList<>(m.keySet()); }

        static final class BuiltinCmd implements Cmd {
            private final BiConsumer<ExecutionContext, PrintStream> run;
            BuiltinCmd(BiConsumer<ExecutionContext, PrintStream> run) { this.run = run; }

            @Override public void execute(ExecutionContext ctx) {
                if (ctx.redirs().stderr().isPresent()) {
                    try { Redir.touch(ctx.redirs().stderr().get()); }
                    catch (IOException e) { System.err.println("Redirection error: " + e.getMessage()); }
                }
                try (var t = Redir.stdout(ctx.redirs(), ctx.out())) {
                    run.accept(ctx, t.ps());
                } catch (IOException e) {
                    System.err.println("Redirection error: " + e.getMessage());
                }
            }
        }
    }

    static final class ExternalCmd implements Cmd {
        private final String name;
        private final List<String> argv;
        private final PathResolver resolver;

        ExternalCmd(String name, List<String> argv, PathResolver resolver) { this.name = name; this.argv = argv; this.resolver = resolver; }

        @Override public void execute(ExecutionContext ctx) {
            try {
                if (resolver.findExecutable(name).isEmpty()) { System.out.println(name + ": command not found"); return; }

                var pb = new ProcessBuilder(new ArrayList<>(argv));
                pb.directory(ShellRuntime.env.cwd().toFile());

                boolean pipeIn = (ctx.in() != System.in);
                if (pipeIn) pb.redirectInput(ProcessBuilder.Redirect.PIPE);
                else pb.redirectInput(ProcessBuilder.Redirect.INHERIT);

                if (ctx.redirs().stdout().isPresent()) {
                    var s = ctx.redirs().stdout().get();
                    if (s.mode() == RedirectMode.APPEND) Redir.touch(s); else Redir.touch(s);
                    pb.redirectOutput(s.mode() == RedirectMode.APPEND
                            ? ProcessBuilder.Redirect.appendTo(s.path().toFile())
                            : ProcessBuilder.Redirect.to(s.path().toFile()));
                } else if (ctx.out() != System.out) pb.redirectOutput(ProcessBuilder.Redirect.PIPE);
                else pb.redirectOutput(ProcessBuilder.Redirect.INHERIT);

                if (ctx.redirs().stderr().isPresent()) {
                    var s = ctx.redirs().stderr().get();
                    if (s.mode() == RedirectMode.APPEND) Redir.touch(s); else Redir.touch(s);
                    pb.redirectError(s.mode() == RedirectMode.APPEND
                            ? ProcessBuilder.Redirect.appendTo(s.path().toFile())
                            : ProcessBuilder.Redirect.to(s.path().toFile()));
                } else pb.redirectError(ProcessBuilder.Redirect.INHERIT);

                var p = pb.start();

                Thread inPump = null;
                if (pipeIn) {
                    var src = ctx.in();
                    var dst = p.getOutputStream();
                    inPump = new Thread(() -> pump(src, dst, true));
                    inPump.start();
                }

                Thread outPump = null;
                boolean pipeOut = ctx.redirs().stdout().isEmpty() && ctx.out() != System.out;
                if (pipeOut) {
                    var src = p.getInputStream();
                    var dst = ctx.out();
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

        private static void pump(InputStream in, OutputStream out, boolean closeOut) {
            try {
                byte[] buf = new byte[8192];
                for (int n; (n = in.read(buf)) != -1; ) { out.write(buf, 0, n); out.flush(); }
                if (closeOut) out.close();
            } catch (IOException ignored) {}
        }
        private static void pump(InputStream in, PrintStream out) {
            try {
                byte[] buf = new byte[8192];
                for (int n; (n = in.read(buf)) != -1; ) { out.write(buf, 0, n); out.flush(); }
            } catch (IOException ignored) {}
        }
    }

    // ===================== Pipelines =====================

    static final class PipelineCmd implements Cmd {
        private final List<List<String>> stages;
        private final Redirections lastRedirs;
        private final Factory factory;

        PipelineCmd(List<List<String>> stages, Redirections lastRedirs, Factory factory) {
            this.stages = List.copyOf(stages);
            this.lastRedirs = lastRedirs;
            this.factory = factory;
        }

        @Override public void execute(ExecutionContext ctx) {
            if (stages.isEmpty()) return;

            for (var argv : stages) {
                if (argv.isEmpty()) return;
                var name = argv.get(0);
                if (factory.builtins.isBuiltin(name)) continue;
                if (factory.resolver.findExecutable(name).isEmpty()) {
                    System.out.println(name + ": command not found");
                    return;
                }
            }

            if (stages.size() == 1) {
                var argv = stages.get(0);
                factory.create(argv.get(0), argv).execute(new ExecutionContext(argv, lastRedirs, ctx.in(), ctx.out(), ctx.err()));
                return;
            }

            var threads = new ArrayList<Thread>(Math.max(0, stages.size() - 1));
            InputStream nextIn = ctx.in();

            for (int i = 0; i < stages.size(); i++) {
                var argv = stages.get(i);
                boolean last = (i == stages.size() - 1);
                var stageIn = nextIn;

                if (!last) {
                    try {
                        var pipeIn = new PipedInputStream();
                        var pipeOut = new PipedOutputStream(pipeIn);
                        var stageOut = new PrintStream(pipeOut);

                        var cmd = factory.create(argv.get(0), argv);
                        var stageCtx = new ExecutionContext(argv, Redirections.none(), stageIn, stageOut, ctx.err());

                        var t = new Thread(() -> {
                            try (stageOut; pipeOut) { cmd.execute(stageCtx); }
                            catch (IOException ignored) {}
                        });
                        t.start();
                        threads.add(t);

                        nextIn = pipeIn;
                    } catch (IOException e) {
                        var msg = e.getMessage();
                        if (msg != null && !msg.isEmpty()) System.out.println(msg);
                        return;
                    }
                } else {
                    var lastCtx = new ExecutionContext(argv, lastRedirs, stageIn, ctx.out(), ctx.err());
                    factory.create(argv.get(0), argv).execute(lastCtx);
                }
            }

            for (var t : threads) {
                try { t.join(); }
                catch (InterruptedException e) { Thread.currentThread().interrupt(); return; }
            }
        }
    }

    // ===================== History + runtime =====================

    static final class History {
        private final List<String> lines = new ArrayList<>();
        void add(String line) { lines.add(line); }
        List<String> snapshot() { return List.copyOf(lines); }
    }

    static final class ShellRuntime {
        static final Environment env = new Environment();
        static final History history = new History();
    }
}