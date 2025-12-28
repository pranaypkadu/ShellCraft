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

public class Main {
    static final String PROMPT = "$ ";

    public static void main(String[] args) {
        Environment env = ShellRuntime.env;
        var resolver = new PathResolver(env);
        var builtins = new BuiltinRegistry(resolver);
        var factory = new DefaultCommandFactory(builtins, resolver);
        CompletionEngine completer = new CommandNameCompleter(builtins, resolver);

        try (var input = new InteractiveInput(System.in, completer, PROMPT)) {
            var shell = new Shell(input, env, resolver, factory, builtins, new CommandLineParser(), PROMPT);
            shell.run();
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    // =========================================================================
    // Core Shell Logic
    // =========================================================================

    static final class Shell {
        private final InteractiveInput input;
        private final Environment env;
        private final PathResolver resolver;
        private final CommandFactory factory;
        private final BuiltinRegistry builtins;
        private final CommandLineParser parser;
        private final String prompt;

        Shell(InteractiveInput input, Environment env, PathResolver resolver, CommandFactory factory,
              BuiltinRegistry builtins, CommandLineParser parser, String prompt) {
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
                System.err.println("Fatal IO Error: " + e.getMessage());
            }
        }

        private void handle(String line) {
            try {
                List<String> tokens = Tokenizer.tokenize(line);
                if (tokens.isEmpty()) return;

                ParsedLine parsed = parser.parse(tokens);
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
                        if (pipe.stages().isEmpty()) return;
                        ShellCommand cmd = new PipelineCommand(pipe.stages(), pipe.redirections(), resolver, builtins);
                        // Pipeline command itself doesn't use args, but needs redirections for the final stage
                        cmd.execute(rootCtx.withArgsAndRedirs(List.of(), pipe.redirections()));
                    }
                }
            } catch (Exception e) {
                String msg = e.getMessage();
                if (msg != null && !msg.isEmpty()) {
                    System.out.println(msg);
                }
            }
        }
    }

    // =========================================================================
    // Parsing & Tokenization
    // =========================================================================

    sealed interface ParsedLine permits ParsedLine.Simple, ParsedLine.Pipeline {
        record Simple(CommandLine line) implements ParsedLine {}
        record Pipeline(List<List<String>> stages, Redirections redirections) implements ParsedLine {}
    }

    record CommandLine(List<String> args, Redirections redirections) {}

    static final class CommandLineParser {
        private static final String OP_PIPE = "|";
        private static final String OP_GT = ">";
        private static final String OP_1GT = "1>";
        private static final String OP_DGT = ">>";
        private static final String OP_1DGT = "1>>";
        private static final String OP_2GT = "2>";
        private static final String OP_2DGT = "2>>";

        ParsedLine parse(List<String> tokens) {
            // 1. Extract redirections from the very end (applies to the last command)
            CommandLine base = parseRedirections(tokens);
            List<String> args = base.args();

            // 2. Split by Pipe
            List<List<String>> stages = new ArrayList<>();
            List<String> currentStage = new ArrayList<>();

            for (String token : args) {
                if (OP_PIPE.equals(token)) {
                    if (!currentStage.isEmpty()) {
                        stages.add(List.copyOf(currentStage));
                        currentStage = new ArrayList<>();
                    }
                } else {
                    currentStage.add(token);
                }
            }
            if (!currentStage.isEmpty()) {
                stages.add(List.copyOf(currentStage));
            }

            if (stages.size() > 1) {
                return new ParsedLine.Pipeline(stages, base.redirections());
            } else {
                return new ParsedLine.Simple(base);
            }
        }

        private CommandLine parseRedirections(List<String> tokens) {
            if (tokens.size() >= 2) {
                String op = tokens.get(tokens.size() - 2);
                String fileToken = tokens.get(tokens.size() - 1);
                Path path = Paths.get(fileToken);

                if (OP_GT.equals(op) || OP_1GT.equals(op)) {
                    return withoutLastTwo(tokens, new Redirections(
                            Optional.of(new RedirectSpec(RedirectStream.STDOUT, path, RedirectMode.TRUNCATE)),
                            Optional.empty()));
                }
                if (OP_DGT.equals(op) || OP_1DGT.equals(op)) {
                    return withoutLastTwo(tokens, new Redirections(
                            Optional.of(new RedirectSpec(RedirectStream.STDOUT, path, RedirectMode.APPEND)),
                            Optional.empty()));
                }
                if (OP_2GT.equals(op)) {
                    return withoutLastTwo(tokens, new Redirections(
                            Optional.empty(),
                            Optional.of(new RedirectSpec(RedirectStream.STDERR, path, RedirectMode.TRUNCATE))));
                }
                if (OP_2DGT.equals(op)) {
                    return withoutLastTwo(tokens, new Redirections(
                            Optional.empty(),
                            Optional.of(new RedirectSpec(RedirectStream.STDERR, path, RedirectMode.APPEND))));
                }
            }
            return new CommandLine(tokens, Redirections.none());
        }

        private CommandLine withoutLastTwo(List<String> tokens, Redirections redirs) {
            return new CommandLine(new ArrayList<>(tokens.subList(0, tokens.size() - 2)), redirs);
        }
    }

    static final class Tokenizer {
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
                        if (c == '\'') state = State.DEFAULT;
                        else current.append(c);
                    }
                    case DOUBLE_QUOTE -> {
                        if (c == '"') state = State.DEFAULT;
                        else if (c == '\\') state = State.DOUBLE_QUOTE_ESCAPE;
                        else current.append(c);
                    }
                    case DOUBLE_QUOTE_ESCAPE -> {
                        if (c == '"' || c == '\\') current.append(c);
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
    // Execution & Commands
    // =========================================================================

    enum RedirectMode { TRUNCATE, APPEND }
    enum RedirectStream { STDOUT, STDERR }
    record RedirectSpec(RedirectStream stream, Path path, RedirectMode mode) {}
    record Redirections(Optional<RedirectSpec> stdoutRedirect, Optional<RedirectSpec> stderrRedirect) {
        static Redirections none() { return new Redirections(Optional.empty(), Optional.empty()); }
    }

    record ExecutionContext(List<String> args, Redirections redirections, InputStream stdin, PrintStream stdout, PrintStream stderr) {
        static ExecutionContext system() {
            return new ExecutionContext(List.of(), Redirections.none(), System.in, System.out, System.err);
        }
        ExecutionContext withArgsAndRedirs(List<String> newArgs, Redirections newRedirs) {
            return new ExecutionContext(newArgs, newRedirs, this.stdin, this.stdout, this.stderr);
        }
        ExecutionContext withStreams(InputStream in, PrintStream out) {
            return new ExecutionContext(this.args, this.redirections, in, out, this.stderr);
        }
    }

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
            return builtins.lookup(name).orElseGet(() -> new ExternalCommand(name, args, resolver));
        }
    }

    static final class PipelineCommand implements ShellCommand {
        private final List<List<String>> stages;
        private final Redirections finalRedirections;
        private final PathResolver resolver;
        private final BuiltinRegistry builtins;

        PipelineCommand(List<List<String>> stages, Redirections finalRedirections, PathResolver resolver, BuiltinRegistry builtins) {
            this.stages = List.copyOf(stages);
            this.finalRedirections = finalRedirections;
            this.resolver = resolver;
            this.builtins = builtins;
        }

        @Override
        public void execute(ExecutionContext ctx) {
            // Validation first
            for (List<String> stage : stages) {
                if (stage.isEmpty()) return;
                String name = stage.get(0);
                if (!builtins.isBuiltin(name) && resolver.findExecutable(name).isEmpty()) {
                    System.out.println(name + ": command not found");
                    return;
                }
            }

            try {
                InputStream currentIn = ctx.stdin;
                List<Thread> threads = new ArrayList<>();
                List<Process> processes = new ArrayList<>();

                for (int i = 0; i < stages.size(); i++) {
                    List<String> args = stages.get(i);
                    String name = args.get(0);
                    boolean isLast = (i == stages.size() - 1);

                    // Setup Output
                    OutputStream currentOut;
                    InputStream nextIn = null;
                    PrintStream finalPrintStream = null;

                    if (isLast) {
                        // Last stage uses the context's stdout (which might be redirected to file)
                        // We need to resolve the redirection if present, or use ctx.stdout
                        if (ctx.redirections.stdoutRedirect.isPresent()) {
                            OutputTarget target = OutputTargets.resolve(ctx.redirections.stdoutRedirect, ctx.stdout);
                            finalPrintStream = target.out(); // We don't close target here, External/Builtin logic handles
                            currentOut = finalPrintStream;
                        } else {
                            currentOut = ctx.stdout;
                        }
                    } else {
                        // Intermediate stages pipe to next
                        PipedInputStream pis = new PipedInputStream();
                        currentOut = new PipedOutputStream(pis);
                        nextIn = pis;
                    }

                    if (builtins.isBuiltin(name)) {
                        ShellCommand cmd = builtins.lookup(name).get();
                        // For builtins, we must wrap output in PrintStream.
                        // If it's a pipe, we MUST close it after execution to signal EOF to next.
                        final InputStream inForCmd = currentIn;
                        final OutputStream outForCmd = currentOut;
                        final boolean shouldCloseOut = !isLast; // Close pipe, don't close System.out

                        Thread t = new Thread(() -> {
                            try {
                                PrintStream ps = (outForCmd instanceof PrintStream) ? (PrintStream)outForCmd : new PrintStream(outForCmd);
                                cmd.execute(new ExecutionContext(args, Redirections.none(), inForCmd, ps, ctx.stderr));
                                ps.flush();
                                if (shouldCloseOut) ps.close();
                            } catch (Exception e) {
                                e.printStackTrace();
                            }
                        });
                        t.start();
                        threads.add(t);

                    } else {
                        // External Command
                        ProcessBuilder pb = new ProcessBuilder(args);
                        pb.directory(ShellRuntime.env.getCurrentDirectory().toFile());

                        // Input: pipe manually if not System.in
                        pb.redirectInput(ProcessBuilder.Redirect.PIPE);
                        // Output: pipe manually if not System.out (or last redirection)
                        pb.redirectOutput(ProcessBuilder.Redirect.PIPE);

                        // Stderr: Inherit (unless last stage redirects it, but for now assuming inherit per requirements)
                        if (isLast && ctx.redirections.stderrRedirect.isPresent()) {
                            RedirectSpec spec = ctx.redirections.stderrRedirect.get();
                            if (spec.mode() == RedirectMode.APPEND) pb.redirectError(ProcessBuilder.Redirect.appendTo(spec.path().toFile()));
                            else pb.redirectError(spec.path().toFile());
                        } else {
                            pb.redirectError(ProcessBuilder.Redirect.INHERIT);
                        }

                        Process p = pb.start();
                        processes.add(p);

                        // Pump Input (currentIn -> p.output)
                        // If currentIn is System.in, we shouldn't close it, but process expects close for EOF?
                        // Actually, if currentIn is System.in, we just copy until user stops?
                        // Shell usually doesn't pipe System.in to first process unless user types.
                        // But ProcessBuilder.Redirect.PIPE means we must write to getOutputStream().
                        final InputStream srcIn = currentIn;
                        final OutputStream destIn = p.getOutputStream();

                        Thread inputPump = new Thread(() -> {
                            try {
                                if (srcIn == System.in) {
                                    // Special case: don't close System.in, just read available?
                                    // Actually usually external commands inherit System.in directly.
                                    // But here we need a unified chain.
                                    // Better: if(i==0) pb.redirectInput(Inherit) if not piped?
                                    // But mixed chain implies previous could be builtin.
                                    copyQuietly(srcIn, destIn);
                                    // Don't close System.in, but destIn (process stdin) MUST be closed to signal EOF.
                                    destIn.close();
                                } else {
                                    copyQuietly(srcIn, destIn);
                                    destIn.close();
                                }
                            } catch (IOException e) { /* ignore */ }
                        });
                        inputPump.start();
                        threads.add(inputPump);

                        // Pump Output (p.input -> currentOut)
                        final InputStream srcOut = p.getInputStream();
                        final OutputStream destOut = currentOut;
                        final boolean shouldCloseDest = !isLast;

                        Thread outputPump = new Thread(() -> {
                            try {
                                copyQuietly(srcOut, destOut);
                                if (shouldCloseDest) destOut.close();
                            } catch (IOException e) { /* ignore */ }
                        });
                        outputPump.start();
                        threads.add(outputPump);
                    }

                    currentIn = nextIn; // Prepare for next stage
                }

                // Wait for all
                for (Thread t : threads) t.join();
                for (Process p : processes) p.waitFor();

            } catch (Exception e) {
                e.printStackTrace();
            }
        }

        private static void copyQuietly(InputStream in, OutputStream out) throws IOException {
            byte[] buf = new byte[8192];
            int n;
            while ((n = in.read(buf)) != -1) {
                out.write(buf, 0, n);
                out.flush();
            }
        }
    }

    static final class ExternalCommand implements ShellCommand {
        private final String name;
        private final List<String> args;
        private final PathResolver resolver;

        ExternalCommand(String name, List<String> args, PathResolver resolver) {
            this.name = name;
            this.args = List.copyOf(args);
            this.resolver = resolver;
        }

        @Override
        public void execute(ExecutionContext ctx) {
            try {
                if (resolver.findExecutable(name).isEmpty()) {
                    System.out.println(name + ": command not found");
                    return;
                }

                ProcessBuilder pb = new ProcessBuilder(args);
                pb.directory(ShellRuntime.env.getCurrentDirectory().toFile());

                // Standalone execution (non-pipeline) logic
                // Input
                if (ctx.stdin == System.in) pb.redirectInput(ProcessBuilder.Redirect.INHERIT);
                else pb.redirectInput(ProcessBuilder.Redirect.PIPE);

                // Output
                if (ctx.stdout == System.out) pb.redirectOutput(ProcessBuilder.Redirect.INHERIT);
                else pb.redirectOutput(ProcessBuilder.Redirect.PIPE);

                // Stderr
                if (ctx.redirections.stderrRedirect().isPresent()) {
                    RedirectSpec spec = ctx.redirections.stderrRedirect().get();
                    if (spec.mode() == RedirectMode.APPEND) pb.redirectError(ProcessBuilder.Redirect.appendTo(spec.path().toFile()));
                    else pb.redirectError(spec.path().toFile());
                } else {
                    pb.redirectError(ProcessBuilder.Redirect.INHERIT);
                }

                Process p = pb.start();

                Thread inPump = null, outPump = null;
                if (ctx.stdin != System.in) {
                    inPump = new Thread(() -> {
                        try (OutputStream os = p.getOutputStream()) { copyQuietly(ctx.stdin, os); }
                        catch (IOException e) {}
                    });
                    inPump.start();
                }
                if (ctx.stdout != System.out) {
                    outPump = new Thread(() -> {
                        try (InputStream is = p.getInputStream()) { copyQuietly(is, ctx.stdout); }
                        catch (IOException e) {}
                    });
                    outPump.start();
                }

                p.waitFor();
                if (inPump != null) inPump.join();
                if (outPump != null) outPump.join();

            } catch (Exception e) {
                System.out.println(name + ": command not found");
            }
        }

        private static void copyQuietly(InputStream in, OutputStream out) throws IOException {
            byte[] buf = new byte[8192];
            int n;
            while ((n = in.read(buf)) != -1) {
                out.write(buf, 0, n);
                out.flush();
            }
        }
    }

    // =========================================================================
    // Builtins & Registry
    // =========================================================================

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
        Set<String> names() { return map.keySet(); }
    }

    static final class ExitCommand implements ShellCommand {
        @Override
        public void execute(ExecutionContext ctx) {
            if (ctx.args.size() > 1) {
                try { System.exit(Integer.parseInt(ctx.args.get(1))); }
                catch (NumberFormatException ignored) {}
            }
            System.exit(0);
        }
    }

    static final class EchoCommand implements ShellCommand {
        @Override
        public void execute(ExecutionContext ctx) {
            StringBuilder sb = new StringBuilder();
            for (int i = 1; i < ctx.args.size(); i++) {
                if (i > 1) sb.append(" ");
                sb.append(ctx.args.get(i));
            }
            ctx.stdout.println(sb.toString());
        }
    }

    static final class PwdCommand implements ShellCommand {
        @Override
        public void execute(ExecutionContext ctx) {
            ctx.stdout.println(ShellRuntime.env.getCurrentDirectory());
        }
    }

    static final class CdCommand implements ShellCommand {
        @Override
        public void execute(ExecutionContext ctx) {
            String path = (ctx.args.size() < 2) ? "~" : ctx.args.get(1);
            if ("~".equals(path)) {
                String home = ShellRuntime.env.getHome();
                if (home == null) { ctx.stdout.println("cd: HOME not set"); return; }
                path = home;
            } else if (path.startsWith("~" + File.separator)) {
                String home = ShellRuntime.env.getHome();
                if (home == null) { ctx.stdout.println("cd: HOME not set"); return; }
                path = home + path.substring(1);
            }

            Path target = Paths.get(path);
            if (!target.isAbsolute()) target = ShellRuntime.env.getCurrentDirectory().resolve(target);
            target = target.normalize();

            if (Files.exists(target) && Files.isDirectory(target)) {
                ShellRuntime.env.setCurrentDirectory(target);
            } else {
                ctx.stdout.println("cd: " + (ctx.args.size() < 2 ? "~" : ctx.args.get(1)) + ": No such file or directory");
            }
        }
    }

    static final class TypeCommand implements ShellCommand {
        private final BuiltinRegistry builtins;
        private final PathResolver resolver;
        TypeCommand(BuiltinRegistry builtins, PathResolver resolver) { this.builtins = builtins; this.resolver = resolver; }
        @Override
        public void execute(ExecutionContext ctx) {
            if (ctx.args.size() < 2) return;
            String name = ctx.args.get(1);
            if (builtins.isBuiltin(name)) {
                ctx.stdout.println(name + " is a shell builtin");
            } else {
                resolver.findExecutable(name).ifPresentOrElse(
                        p -> ctx.stdout.println(name + " is " + p),
                        () -> ctx.stdout.println(name + ": not found")
                );
            }
        }
    }

    // =========================================================================
    // Environment & Runtime
    // =========================================================================

    static final class ShellRuntime {
        static final Environment env = new Environment();
    }

    static final class Environment {
        private Path currentDirectory = Paths.get(System.getProperty("user.dir")).toAbsolutePath().normalize();
        Path getCurrentDirectory() { return currentDirectory; }
        void setCurrentDirectory(Path p) { this.currentDirectory = p; }
        String getHome() { return System.getenv("HOME"); }
        List<Path> getPathDirectories() {
            String pathEnv = System.getenv("PATH");
            if (pathEnv == null || pathEnv.isEmpty()) return List.of();
            List<Path> dirs = new ArrayList<>();
            for (String part : pathEnv.split(File.pathSeparator)) {
                if (!part.isEmpty()) dirs.add(Paths.get(part));
            }
            return dirs;
        }
    }

    static final class PathResolver {
        private final Environment env;
        PathResolver(Environment env) { this.env = env; }
        Optional<Path> findExecutable(String name) {
            if (name.contains(File.separator)) {
                Path p = Paths.get(name);
                if (!p.isAbsolute()) p = env.getCurrentDirectory().resolve(p);
                return (Files.isRegularFile(p) && Files.executable(p)) ? Optional.of(p.normalize()) : Optional.empty();
            }
            for (Path dir : env.getPathDirectories()) {
                Path p = dir.resolve(name);
                if (Files.isRegularFile(p) && Files.executable(p)) return Optional.of(p);
            }
            return Optional.empty();
        }

        Set<String> findExecutableNamesByPrefix(String prefix) {
            Set<String> out = new LinkedHashSet<>();
            for (Path dir : env.getPathDirectories()) {
                if (!Files.isDirectory(dir)) continue;
                try (DirectoryStream<Path> stream = Files.newDirectoryStream(dir)) {
                    for (Path p : stream) {
                        String name = p.getFileName().toString();
                        if (name.startsWith(prefix) && Files.isRegularFile(p) && Files.isExecutable(p)) out.add(name);
                    }
                } catch (IOException ignored) {}
            }
            return out;
        }

        // Helper specifically for file system checks in resolver
        private static boolean Files_executable(Path p) { return Files.isExecutable(p); }
    }

    // =========================================================================
    // IO Utils (Redirection, OutputTargets)
    // =========================================================================

    static final class RedirectionIO {
        static void touch(RedirectSpec spec) throws IOException {
            StandardOpenOption[] opts = (spec.mode == RedirectMode.APPEND) ?
                    new StandardOpenOption[]{StandardOpenOption.CREATE, StandardOpenOption.APPEND, StandardOpenOption.WRITE} :
                    new StandardOpenOption[]{StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING, StandardOpenOption.WRITE};
            Files.newOutputStream(spec.path, opts).close();
        }
    }

    interface OutputTarget extends AutoCloseable {
        PrintStream out();
        @Override void close() throws IOException;
    }

    static final class OutputTargets {
        static OutputTarget resolve(Optional<RedirectSpec> spec, PrintStream defaultStream) throws IOException {
            if (spec.isEmpty()) return new OutputTarget() {
                public PrintStream out() { return defaultStream; }
                public void close() {}
            };
            RedirectSpec s = spec.get();
            StandardOpenOption[] opts = (s.mode == RedirectMode.APPEND) ?
                    new StandardOpenOption[]{StandardOpenOption.CREATE, StandardOpenOption.APPEND, StandardOpenOption.WRITE} :
                    new StandardOpenOption[]{StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING, StandardOpenOption.WRITE};
            OutputStream os = Files.newOutputStream(s.path, opts);
            return new OutputTarget() {
                final PrintStream ps = new PrintStream(os);
                public PrintStream out() { return ps; }
                public void close() throws IOException { ps.close(); }
            };
        }
    }

    // =========================================================================
    // Interactive Input & Completion (Preserved)
    // =========================================================================

    interface CompletionEngine {
        CompletionResult completeFirstWord(String currentFirstWord);
    }

    static final class CompletionResult {
        enum Kind { SUFFIX, NO_MATCH, AMBIGUOUS, ALREADY_COMPLETE, NOT_APPLICABLE }
        final Kind kind;
        final String suffixToAppend;
        final List<String> matches;

        private CompletionResult(Kind k, String s, List<String> m) { kind = k; suffixToAppend = s; matches = (m == null) ? List.of() : m; }
        static CompletionResult suffix(String s) { return new CompletionResult(Kind.SUFFIX, s, null); }
        static CompletionResult ambiguous(List<String> m) { return new CompletionResult(Kind.AMBIGUOUS, null, m); }
        static CompletionResult of(Kind k) { return new CompletionResult(k, null, null); }
    }

    static final class CommandNameCompleter implements CompletionEngine {
        private final BuiltinRegistry builtins;
        private final PathResolver resolver;
        CommandNameCompleter(BuiltinRegistry builtins, PathResolver resolver) { this.builtins = builtins; this.resolver = resolver; }
        @Override public CompletionResult completeFirstWord(String prefix) {
            if (prefix == null || prefix.isEmpty()) return CompletionResult.of(CompletionResult.Kind.NOT_APPLICABLE);
            Set<String> matches = new TreeSet<>();
            for (String b : builtins.names()) if (b.startsWith(prefix)) matches.add(b);
            matches.addAll(resolver.findExecutableNamesByPrefix(prefix));
            if (matches.isEmpty()) return CompletionResult.of(CompletionResult.Kind.NO_MATCH);
            if (matches.size() == 1) {
                String val = matches.iterator().next();
                if (val.equals(prefix)) return CompletionResult.of(CompletionResult.Kind.ALREADY_COMPLETE);
                return CompletionResult.suffix(val.substring(prefix.length()) + " ");
            }
            return CompletionResult.ambiguous(new ArrayList<>(matches));
        }
    }

    static final class InteractiveInput implements AutoCloseable {
        private final InputStream in;
        private final CompletionEngine completer;
        private final String prompt;
        private final TerminalMode terminal = new TerminalMode();
        private boolean raw;

        InteractiveInput(InputStream in, CompletionEngine completer, String prompt) {
            this.in = in; this.completer = completer; this.prompt = prompt;
            try { raw = terminal.enableRawMode(); } catch(Exception e) { raw = false; }
        }

        String readLine() throws IOException {
            StringBuilder buf = new StringBuilder();
            while (true) {
                int b = in.read();
                if (b == -1) return buf.length() == 0 ? null : buf.toString();
                char c = (char) b;
                if (c == '\n') { System.out.print("\r\n"); return buf.toString(); }
                if (b == 127 || b == 8) { // Backspace
                    if (buf.length() > 0) {
                        buf.deleteCharAt(buf.length() - 1);
                        System.out.print("\b \b");
                    }
                } else if (c == '\t') {
                    handleTab(buf);
                } else {
                    buf.append(c);
                    System.out.print(c);
                }
            }
        }

        private void handleTab(StringBuilder buf) throws IOException {
            // Simplified tab handling to fit single file constraints & missing snippets
            String current = buf.toString();
            CompletionResult r = completer.completeFirstWord(current);
            if (r.kind == CompletionResult.Kind.SUFFIX) {
                buf.append(r.suffixToAppend);
                System.out.print(r.suffixToAppend);
            } else if (r.kind == CompletionResult.Kind.AMBIGUOUS) {
                System.out.print("\u0007\r\n" + String.join("  ", r.matches) + "\r\n" + prompt + buf);
            } else {
                System.out.print("\u0007");
            }
        }

        public void close() { if(raw) try { terminal.disableRawMode(); } catch(Exception e){} }
    }

    static final class TerminalMode {
        boolean enableRawMode() throws IOException, InterruptedException {
            String[] cmd = {"/bin/sh", "-c", "stty -icanon -echo < /dev/tty"};
            return new ProcessBuilder(cmd).inheritIO().start().waitFor() == 0;
        }
        void disableRawMode() throws IOException, InterruptedException {
            String[] cmd = {"/bin/sh", "-c", "stty sane < /dev/tty"};
            new ProcessBuilder(cmd).inheritIO().start().waitFor();
        }
    }
}
