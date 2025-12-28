import java.io.*;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.stream.Collectors;

public class Main {

    public static void main(String[] args) {
        Environment env = new Environment();
        PathResolver resolver = new PathResolver(env);
        BuiltinRegistry builtins = new BuiltinRegistry(env, resolver);
        CommandFactory factory = new CommandFactory(builtins, resolver);
        CommandLineParser parser = new CommandLineParser();
        CompletionEngine completer = new CommandNameCompleter(builtins, resolver);

        try (InteractiveInput input = new InteractiveInput(System.in, completer, "$ ")) {
            Shell shell = new Shell(input, env, factory, parser, "$ ");
            shell.run();
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    // =========================================================================
    // Core Shell Runtime
    // =========================================================================

    static class Shell {
        private final InteractiveInput input;
        private final Environment env;
        private final CommandFactory factory;
        private final CommandLineParser parser;
        private final String prompt;

        Shell(InteractiveInput input, Environment env, CommandFactory factory, CommandLineParser parser, String prompt) {
            this.input = input;
            this.env = env;
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
                System.err.println("Fatal I/O Error: + " + e.getMessage());
            }
        }

        private void handle(String line) {
            try {
                List<String> tokens = Tokenizer.tokenize(line);
                if (tokens.isEmpty()) return;

                ParsedLine parsed = parser.parse(tokens);
                executeParsedLine(parsed);

            } catch (Exception e) {
                String msg = e.getMessage();
                if (msg != null && !msg.isEmpty()) {
                    System.out.println(msg);
                }
            }
        }

        private void executeParsedLine(ParsedLine parsed) {
            ExecutionContext rootCtx = ExecutionContext.system();

            if (parsed instanceof ParsedLine.Simple simple) {
                CommandLine cmdLine = simple.command();
                if (cmdLine.args().isEmpty()) return;

                ShellCommand cmd = factory.create(cmdLine.args());
                cmd.execute(rootCtx.withRedirections(cmdLine.redirections()));

            } else if (parsed instanceof ParsedLine.Pipeline pipeline) {
                PipelineExecutor.execute(pipeline.commands(), pipeline.finalRedirections(), factory, rootCtx);
            }
        }
    }

    // =========================================================================
    // Execution & Commands
    // =========================================================================

    interface ShellCommand {
        void execute(ExecutionContext ctx);
    }

    record ExecutionContext(InputStream in, OutputStream out, OutputStream err) {
        static ExecutionContext system() {
            return new ExecutionContext(System.in, System.out, System.err);
        }

        ExecutionContext withRedirections(Redirections redirs) {
            try {
                OutputStream newOut = OutputTargets.resolve(redirs.stdout(), out);
                OutputStream newErr = OutputTargets.resolve(redirs.stderr(), err);
                return new ExecutionContext(in, newOut, newErr);
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            }
        }
    }

    static class PipelineExecutor {
        static void execute(List<List<String>> stages, Redirections finalRedirections, CommandFactory factory, ExecutionContext rootCtx) {
            if (stages.isEmpty()) return;

            List<ShellCommand> commands = stages.stream()
                    .map(factory::create)
                    .toList();

            ExecutorService pool = Executors.newCachedThreadPool();
            List<Future<?>> futures = new ArrayList<>();
            List<Closeable> streamsToClose = new ArrayList<>();

            try {
                InputStream previousInput = rootCtx.in();

                for (int i = 0; i < commands.size(); i++) {
                    boolean isLast = (i == commands.size() - 1);
                    ShellCommand cmd = commands.get(i);

                    OutputStream currentOutput;
                    InputStream nextInput = null;

                    if (isLast) {
                        try {
                            OutputStream resolvedOut = OutputTargets.resolve(finalRedirections.stdout(), rootCtx.out());
                            OutputStream resolvedErr = OutputTargets.resolve(finalRedirections.stderr(), rootCtx.err());
                            currentOutput = resolvedOut;
                            if (resolvedOut != rootCtx.out()) streamsToClose.add(resolvedOut);
                            if (resolvedErr != rootCtx.err()) streamsToClose.add(resolvedErr);
                        } catch (IOException e) {
                            throw new UncheckedIOException(e);
                        }
                    } else {
                        PipedInputStream pis = new PipedInputStream();
                        PipedOutputStream pos = new PipedOutputStream();
                        try {
                            pis.connect(pos);
                        } catch (IOException e) {
                            throw new UncheckedIOException(e);
                        }
                        currentOutput = pos;
                        nextInput = pis;
                        streamsToClose.add(pos);
                        streamsToClose.add(pis);
                    }

                    ExecutionContext stageCtx = new ExecutionContext(previousInput, currentOutput, rootCtx.err());

                    futures.add(pool.submit(() -> {
                        try {
                            cmd.execute(stageCtx);
                        } finally {
                            if (stageCtx.out() != rootCtx.out() && stageCtx.out() != rootCtx.err()) {
                                try { stageCtx.out().close(); } catch (IOException ignored) {}
                            }
                        }
                    }));

                    previousInput = nextInput;
                }

                for (Future<?> f : futures) {
                    try {
                        f.get();
                    } catch (ExecutionException e) {
                        Throwable cause = e.getCause();
                        if (cause instanceof RuntimeException re) throw re;
                        System.out.println(cause.getMessage());
                    }
                }

            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            } finally {
                pool.shutdownNow();
                for (Closeable c : streamsToClose) {
                    try { c.close(); } catch (IOException ignored) {}
                }
            }
        }
    }

    static class CommandFactory {
        private final BuiltinRegistry builtins;
        private final PathResolver resolver;

        CommandFactory(BuiltinRegistry builtins, PathResolver resolver) {
            this.builtins = builtins;
            this.resolver = resolver;
        }

        ShellCommand create(List<String> args) {
            if (args.isEmpty()) return ctx -> {};
            String name = args.get(0);

            Optional<ShellCommand> builtin = builtins.lookup(name, args);
            if (builtin.isPresent()) return builtin.get();

            return new ExternalCommand(args, resolver);
        }
    }

    // =========================================================================
    // Parsing & Tokenization
    // =========================================================================

    sealed interface ParsedLine permits ParsedLine.Simple, ParsedLine.Pipeline {
        record Simple(CommandLine command) implements ParsedLine {}
        record Pipeline(List<List<String>> commands, Redirections finalRedirections) implements ParsedLine {}
    }

    record CommandLine(List<String> args, Redirections redirections) {}

    record Redirections(Optional<RedirectSpec> stdout, Optional<RedirectSpec> stderr) {
        static Redirections none() { return new Redirections(Optional.empty(), Optional.empty()); }
    }

    record RedirectSpec(RedirectStream stream, Path path, RedirectMode mode) {}
    enum RedirectStream { STDOUT, STDERR }
    enum RedirectMode { TRUNCATE, APPEND }

    static class CommandLineParser {
        private static final String OP_PIPE = "|";
        private static final Set<String> REDIR_OPS = Set.of(">", "1>", ">>", "1>>", "2>", "2>>");

        ParsedLine parse(List<String> tokens) {
            ParseResult stripped = stripRedirections(tokens);
            List<String> remaining = stripped.tokens;
            Redirections redirs = stripped.redirections;

            List<List<String>> stages = splitByPipe(remaining);

            if (stages.isEmpty()) {
                return new ParsedLine.Simple(new CommandLine(List.of(), redirs));
            }
            if (stages.size() == 1) {
                return new ParsedLine.Simple(new CommandLine(stages.get(0), redirs));
            }

            return new ParsedLine.Pipeline(stages, redirs);
        }

        private List<List<String>> splitByPipe(List<String> tokens) {
            List<List<String>> stages = new ArrayList<>();
            List<String> current = new ArrayList<>();

            for (String token : tokens) {
                if (OP_PIPE.equals(token)) {
                    if (!current.isEmpty()) {
                        stages.add(new ArrayList<>(current));
                        current.clear();
                    }
                } else {
                    current.add(token);
                }
            }
            if (!current.isEmpty()) {
                stages.add(current);
            }
            return stages;
        }

        private record ParseResult(List<String> tokens, Redirections redirections) {}

        private ParseResult stripRedirections(List<String> tokens) {
            if (tokens.size() >= 2) {
                String op = tokens.get(tokens.size() - 2);
                String file = tokens.get(tokens.size() - 1);

                if (REDIR_OPS.contains(op)) {
                    List<String> others = tokens.subList(0, tokens.size() - 2);
                    Path p = Paths.get(file);

                    if (op.equals(">") || op.equals("1>"))
                        return new ParseResult(others, new Redirections(Optional.of(new RedirectSpec(RedirectStream.STDOUT, p, RedirectMode.TRUNCATE)), Optional.empty()));
                    if (op.equals(">>") || op.equals("1>>"))
                        return new ParseResult(others, new Redirections(Optional.of(new RedirectSpec(RedirectStream.STDOUT, p, RedirectMode.APPEND)), Optional.empty()));
                    if (op.equals("2>"))
                        return new ParseResult(others, new Redirections(Optional.empty(), Optional.of(new RedirectSpec(RedirectStream.STDERR, p, RedirectMode.TRUNCATE))));
                    if (op.equals("2>>"))
                        return new ParseResult(others, new Redirections(Optional.empty(), Optional.of(new RedirectSpec(RedirectStream.STDERR, p, RedirectMode.APPEND))));
                }
            }
            return new ParseResult(tokens, Redirections.none());
        }
    }

    static class Tokenizer {
        private enum State { DEFAULT, ESCAPE, SINGLE_QUOTE, DOUBLE_QUOTE, DOUBLE_QUOTE_ESCAPE }

        static List<String> tokenize(String input) {
            List<String> tokens = new ArrayList<>();
            StringBuilder current = new StringBuilder();
            State state = State.DEFAULT;
            boolean inToken = false;

            for (int i = 0; i < input.length(); i++) {
                char c = input.charAt(i);
                switch (state) {
                    case DEFAULT -> {
                        if (Character.isWhitespace(c)) {
                            if (inToken) {
                                tokens.add(current.toString());
                                current.setLength(0);
                                inToken = false;
                            }
                        } else if (c == '\\') { state = State.ESCAPE; inToken = true; }
                        else if (c == '\'') { state = State.SINGLE_QUOTE; inToken = true; }
                        else if (c == '"') { state = State.DOUBLE_QUOTE; inToken = true; }
                        else { current.append(c); inToken = true; }
                    }
                    case ESCAPE -> { current.append(c); state = State.DEFAULT; }
                    case SINGLE_QUOTE -> {
                        if (c == '\'') state = State.DEFAULT;
                        else current.append(c);
                    }
                    case DOUBLE_QUOTE -> {
                        if (c == '"') state = State.DEFAULT;
                        else if (c == '\\') state = State.DOUBLE_QUOTE_ESCAPE;
                        else current.append(c);
                    }
                    case DOUBLE_QUOTE_ESCAPE -> {
                        if (c == '\\' || c == '"') current.append(c);
                        else { current.append('\\'); current.append(c); }
                        state = State.DOUBLE_QUOTE;
                    }
                }
            }
            if (inToken) tokens.add(current.toString());
            return tokens;
        }
    }

    // =========================================================================
    // I/O & Completion
    // =========================================================================

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
            try { rawEnabled = terminalMode.enableRawMode(); } catch (Exception ignored) {}
        }

        String readLine() throws IOException {
            StringBuilder buf = new StringBuilder();
            while (true) {
                int b = in.read();
                if (b == -1) return buf.length() > 0 ? buf.toString() : null;
                char c = (char) b;
                if (c == '\n') {
                    System.out.print("\n"); resetTabState();
                    return buf.toString();
                }
                if (c == '\t') { handleTab(buf); continue; }
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
            for (int i = 0; i < buf.length(); i++) if (Character.isWhitespace(buf.charAt(i))) return;

            CompletionResult r = completer.completeFirstWord(buf.toString());
            if (r.kind == CompletionResult.Kind.SUFFIX) {
                buf.append(r.suffixToAppend);
                System.out.print(r.suffixToAppend);
                resetTabState();
            } else if (r.kind == CompletionResult.Kind.AMBIGUOUS) {
                if (consecutiveTabs == 0 || !buf.toString().equals(bufferSnapshotOnFirstTab)) {
                    System.out.print(BEL);
                    consecutiveTabs = 1;
                    bufferSnapshotOnFirstTab = buf.toString();
                    ambiguousMatches = r.matches;
                } else {
                    System.out.print("\n" + String.join("  ", ambiguousMatches) + "\n" + prompt + buf);
                    resetTabState();
                }
            } else {
                System.out.print(BEL);
                resetTabState();
            }
        }

        private void resetTabState() { consecutiveTabs = 0; bufferSnapshotOnFirstTab = null; ambiguousMatches = List.of(); }
        @Override public void close() { if (rawEnabled) try { terminalMode.disableRawMode(); } catch (Exception ignored) {} }
    }

    static class TerminalMode {
        boolean enableRawMode() throws IOException, InterruptedException {
            return execStty("stty -icanon -echo min 1 time 0 < /dev/tty") == 0;
        }
        void disableRawMode() throws IOException, InterruptedException {
            execStty("stty sane < /dev/tty");
        }
        private int execStty(String cmd) throws IOException, InterruptedException {
            return new ProcessBuilder("/bin/sh", "-c", cmd).inheritIO().start().waitFor();
        }
    }

    interface CompletionEngine { CompletionResult completeFirstWord(String word); }
    record CompletionResult(Kind kind, String suffixToAppend, List<String> matches) {
        enum Kind { SUFFIX, NO_MATCH, AMBIGUOUS, ALREADY_COMPLETE, NOT_APPLICABLE }
        static CompletionResult suffix(String s) { return new CompletionResult(Kind.SUFFIX, s, List.of()); }
        static CompletionResult ambiguous(List<String> matches) { return new CompletionResult(Kind.AMBIGUOUS, null, matches); }
        static CompletionResult of(Kind k) { return new CompletionResult(k, null, List.of()); }
    }

    static class CommandNameCompleter implements CompletionEngine {
        private final BuiltinRegistry builtins;
        private final PathResolver resolver;

        CommandNameCompleter(BuiltinRegistry builtins, PathResolver resolver) {
            this.builtins = builtins;
            this.resolver = resolver;
        }

        @Override
        public CompletionResult completeFirstWord(String prefix) {
            if (prefix == null || prefix.isEmpty()) return CompletionResult.of(CompletionResult.Kind.NOT_APPLICABLE);
            Set<String> matches = new TreeSet<>();
            builtins.names().stream().filter(n -> n.startsWith(prefix)).forEach(matches::add);
            matches.addAll(resolver.findExecutableNamesByPrefix(prefix));

            if (matches.isEmpty()) return CompletionResult.of(CompletionResult.Kind.NO_MATCH);
            if (matches.size() == 1) {
                String match = matches.iterator().next();
                if (match.equals(prefix)) return CompletionResult.of(CompletionResult.Kind.ALREADY_COMPLETE);
                return CompletionResult.suffix(match.substring(prefix.length()) + " ");
            }
            return CompletionResult.ambiguous(new ArrayList<>(matches));
        }
    }

    // =========================================================================
    // Helpers: Environment, Registry, Output
    // =========================================================================

    static class Environment {
        private Path currentDirectory = Paths.get(System.getProperty("user.dir")).toAbsolutePath().normalize();
        Path getCurrentDirectory() { return currentDirectory; }
        void setCurrentDirectory(Path p) { this.currentDirectory = p.toAbsolutePath().normalize(); }
        String getHome() { return System.getenv("HOME"); }
        List<Path> getPathDirectories() {
            String pathEnv = System.getenv("PATH");
            if (pathEnv == null || pathEnv.isEmpty()) return List.of();
            return Arrays.stream(pathEnv.split(File.pathSeparator))
                    .map(Paths::get)
                    .toList();
        }
    }

    static class PathResolver {
        private final Environment env;
        PathResolver(Environment env) { this.env = env; }

        Optional<Path> findExecutable(String name) {
            if (name.contains("/") || name.contains("\\")) {
                Path p = env.getCurrentDirectory().resolve(name).normalize();
                return (Files.isRegularFile(p) && Files.isExecutable(p)) ? Optional.of(p) : Optional.empty();
            }
            for (Path dir : env.getPathDirectories()) {
                Path p = dir.resolve(name);
                if (Files.isRegularFile(p) && Files.isExecutable(p)) return Optional.of(p);
            }
            return Optional.empty();
        }

        Set<String> findExecutableNamesByPrefix(String prefix) {
            Set<String> out = new HashSet<>();
            for (Path dir : env.getPathDirectories()) {
                if (Files.isDirectory(dir)) {
                    try (var stream = Files.newDirectoryStream(dir, p -> p.getFileName().toString().startsWith(prefix))) {
                        for (Path p : stream) if (Files.isExecutable(p)) out.add(p.getFileName().toString());
                    } catch (IOException ignored) {}
                }
            }
            return out;
        }
    }

    static class BuiltinRegistry {
        private final Map<String, java.util.function.Function<List<String>, ShellCommand>> map = new HashMap<>();

        BuiltinRegistry(Environment env, PathResolver resolver) {
            map.put("exit", args -> ctx -> System.exit(0));
            map.put("echo", args -> ctx -> {
                PrintStream out = new PrintStream(ctx.out());
                out.println(String.join(" ", args.subList(1, args.size())));
                out.flush();
            });
            map.put("pwd", args -> ctx -> {
                PrintStream out = new PrintStream(ctx.out());
                out.println(env.getCurrentDirectory());
                out.flush();
            });
            map.put("cd", args -> ctx -> {
                if (args.size() < 2) return;
                String target = args.get(1);
                Path p;
                if ("~".equals(target)) {
                    String home = env.getHome();
                    if (home == null) { System.out.println("cd: HOME not set"); return; }
                    p = Paths.get(home);
                } else {
                    p = env.getCurrentDirectory().resolve(target).normalize();
                }
                if (Files.isDirectory(p)) env.setCurrentDirectory(p);
                else System.out.println("cd: " + target + ": No such file or directory");
            });
            map.put("type", args -> ctx -> {
                if (args.size() < 2) return;
                String name = args.get(1);
                PrintStream out = new PrintStream(ctx.out());
                if (map.containsKey(name)) out.println(name + " is a shell builtin");
                else {
                    var found = resolver.findExecutable(name);
                    if (found.isPresent()) out.println(name + " is " + found.get());
                    else out.println(name + ": not found");
                }
            });
        }

        Optional<ShellCommand> lookup(String name, List<String> args) {
            return Optional.ofNullable(map.get(name)).map(f -> f.apply(args));
        }
        Set<String> names() { return map.keySet(); }
    }

    static class ExternalCommand implements ShellCommand {
        private final List<String> args;
        private final PathResolver resolver;
        ExternalCommand(List<String> args, PathResolver resolver) { this.args = args; this.resolver = resolver; }

        @Override public void execute(ExecutionContext ctx) {
            String name = args.get(0);
            Optional<Path> exe = resolver.findExecutable(name);
            if (exe.isEmpty()) {
                System.out.println(name + ": command not found");
                return;
            }
            try {
                ProcessBuilder pb = new ProcessBuilder(args);
                pb.directory(resolver.env.getCurrentDirectory().toFile());
                Process p = pb.start();

                // 1. Stdin Pump (Daemon, don't join to avoid hang if process ignores stdin)
                Thread inThread = new Thread(() -> {
                    try (OutputStream processIn = p.getOutputStream()) {
                        ctx.in().transferTo(processIn);
                    } catch (IOException ignored) {}
                });
                inThread.setDaemon(true);
                inThread.start();

                // 2. Stdout Pump
                Thread outThread = new Thread(() -> {
                    try {
                        p.getInputStream().transferTo(ctx.out());
                        ctx.out().flush();
                    } catch (IOException ignored) {}
                });
                outThread.start();

                // 3. Stderr Pump
                Thread errThread = new Thread(() -> {
                    try {
                        p.getErrorStream().transferTo(ctx.err());
                        ctx.err().flush();
                    } catch (IOException ignored) {}
                });
                errThread.start();

                p.waitFor();

                // CRITICAL FIX: Wait for output pumps to finish transferring data
                outThread.join();
                errThread.join();

            } catch (Exception e) {
                throw new RuntimeException(e);
            }
        }
    }

    static class OutputTargets {
        static OutputStream resolve(Optional<RedirectSpec> spec, OutputStream defaultStream) throws IOException {
            if (spec.isEmpty()) return defaultStream;
            RedirectSpec s = spec.get();
            OpenOption[] opts = s.mode() == RedirectMode.APPEND
                    ? new OpenOption[]{StandardOpenOption.CREATE, StandardOpenOption.APPEND, StandardOpenOption.WRITE}
                    : new OpenOption[]{StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING, StandardOpenOption.WRITE};
            return Files.newOutputStream(s.path(), opts);
        }
    }
}
