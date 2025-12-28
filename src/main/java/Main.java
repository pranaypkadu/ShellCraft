import java.io.*;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.*;
import java.util.regex.*;
import java.util.stream.*;

/**
 * Interactive Mini-Shell
 * Refactored for modern Java standards (Java 17+) and multi-stage pipeline support.
 */
public class Main {

    public static void main(String[] args) {
        ShellRuntime.run();
    }

    // =========================================================================
    // Core Runtime & Orchestration
    // =========================================================================
    static final class ShellRuntime {
        static final Environment env = new Environment();
        static final String PROMPT = "$ ";

        static void run() {
            var resolver = new PathResolver(env);
            var builtins = new BuiltinRegistry(env, resolver);
            var factory = new CommandFactory(builtins, resolver);
            var parser = new CommandLineParser();
            var executor = new PipelineExecutor(factory);

            // Setup completion engine
            CompletionEngine completer = new CommandNameCompleter(builtins, resolver);

            try (var input = new InteractiveInput(System.in, completer, PROMPT)) {
                System.out.print(PROMPT);
                String line;
                while ((line = input.readLine()) != null) {
                    handleLine(line, parser, executor);
                    System.out.print(PROMPT);
                }
            } catch (Exception e) {
                System.err.println("Fatal Error: " + e.getMessage());
            }
        }

        private static void handleLine(String line, CommandLineParser parser, PipelineExecutor executor) {
            try {
                List<String> tokens = Tokenizer.tokenize(line);
                if (tokens.isEmpty()) return;

                ParsedLine parsed = parser.parse(tokens);

                // Root execution context (Standard I/O)
                ExecutionContext rootCtx = ExecutionContext.system();

                switch (parsed) {
                    case ParsedLine.Pipeline(List<CommandSpec> stages, Redirections redirs) ->
                            executor.executePipeline(stages, redirs, rootCtx);
                }
            } catch (Exception e) {
                // Preserve original behavior: print error to stdout (not stderr) for command errors
                System.out.println(e.getMessage());
            }
        }
    }

    // =========================================================================
    // Domain Models (Records & Sealed Interfaces)
    // =========================================================================

    record CommandSpec(String name, List<String> args) {}

    sealed interface ParsedLine permits ParsedLine.Pipeline {
        record Pipeline(List<CommandSpec> stages, Redirections redirections) implements ParsedLine {}
    }

    enum RedirectMode { TRUNCATE, APPEND }
    enum RedirectStream { STDOUT, STDERR }

    record RedirectSpec(RedirectStream stream, Path path, RedirectMode mode) {}

    record Redirections(Optional<RedirectSpec> stdout, Optional<RedirectSpec> stderr) {
        static final Redirections NONE = new Redirections(Optional.empty(), Optional.empty());
    }

    record ExecutionContext(
            List<String> args,
            InputStream stdin,
            PrintStream stdout,
            PrintStream stderr
    ) {
        static ExecutionContext system() {
            return new ExecutionContext(List.of(), System.in, System.out, System.err);
        }
    }

    // =========================================================================
    // Parser & Tokenizer
    // =========================================================================

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
                        if (c == '\\' || c == '"' || c == '$' || c == '\n') current.append(c);
                        else {
                            current.append('\\');
                            current.append(c);
                        }
                        state = State.DOUBLE_QUOTE;
                    }
                }
            }
            if (inToken) tokens.add(current.toString());
            return tokens;
        }
    }

    static class CommandLineParser {
        private static final Set<String> REDIRECT_OPS = Set.of(">", "1>", ">>", "1>>", "2>", "2>>");
        private static final String PIPE = "|";

        ParsedLine parse(List<String> tokens) {
            // 1. Extract Redirections (last 2 tokens if operator matches)
            Redirections redirs = Redirections.NONE;
            List<String> pipelineTokens = new ArrayList<>(tokens);

            if (tokens.size() >= 2) {
                String op = tokens.get(tokens.size() - 2);
                String file = tokens.get(tokens.size() - 1);

                if (REDIRECT_OPS.contains(op)) {
                    redirs = parseRedirection(op, file);
                    pipelineTokens = tokens.subList(0, tokens.size() - 2);
                }
            }

            // 2. Split by Pipe '|'
            List<CommandSpec> stages = new ArrayList<>();
            List<String> currentArgs = new ArrayList<>();

            for (String token : pipelineTokens) {
                if (token.equals(PIPE)) {
                    if (!currentArgs.isEmpty()) {
                        stages.add(buildSpec(currentArgs));
                        currentArgs = new ArrayList<>();
                    }
                } else {
                    currentArgs.add(token);
                }
            }
            if (!currentArgs.isEmpty()) {
                stages.add(buildSpec(currentArgs));
            }

            return new ParsedLine.Pipeline(stages, redirs);
        }

        private CommandSpec buildSpec(List<String> args) {
            return new CommandSpec(args.get(0), args.subList(1, args.size()));
        }

        private Redirections parseRedirection(String op, String file) {
            Path path = Paths.get(file);
            return switch (op) {
                case ">", "1>" -> new Redirections(Optional.of(new RedirectSpec(RedirectStream.STDOUT, path, RedirectMode.TRUNCATE)), Optional.empty());
                case ">>", "1>>" -> new Redirections(Optional.of(new RedirectSpec(RedirectStream.STDOUT, path, RedirectMode.APPEND)), Optional.empty());
                case "2>" -> new Redirections(Optional.empty(), Optional.of(new RedirectSpec(RedirectStream.STDERR, path, RedirectMode.TRUNCATE)));
                case "2>>" -> new Redirections(Optional.empty(), Optional.of(new RedirectSpec(RedirectStream.STDERR, path, RedirectMode.APPEND)));
                default -> Redirections.NONE;
            };
        }
    }

    // =========================================================================
    // Execution Engine
    // =========================================================================

    interface ShellCommand {
        void execute(ExecutionContext ctx) throws Exception;
    }

    static class PipelineExecutor {
        private final CommandFactory factory;

        PipelineExecutor(CommandFactory factory) {
            this.factory = factory;
        }

        void executePipeline(List<CommandSpec> stages, Redirections redirs, ExecutionContext rootCtx) throws Exception {
            if (stages.isEmpty()) return;

            // Prepare list of tasks to run
            List<Runnable> tasks = new ArrayList<>();
            List<InputStream> inputStreams = new ArrayList<>();
            List<OutputStream> outputStreams = new ArrayList<>();
            List<Thread> threads = new ArrayList<>();

            InputStream prevInput = rootCtx.stdin;

            for (int i = 0; i < stages.size(); i++) {
                boolean isLast = (i == stages.size() - 1);
                CommandSpec spec = stages.get(i);
                ShellCommand cmd = factory.create(spec);

                InputStream stageIn = prevInput;
                OutputStream stageOut;

                // Determine output for this stage
                if (isLast) {
                    // Last stage writes to actual destination (File or Stdout)
                    stageOut = resolveFinalOutput(redirs, rootCtx.stdout);
                } else {
                    // Intermediate stages write to pipe
                    PipedInputStream pis = new PipedInputStream();
                    PipedOutputStream pos = new PipedOutputStream(pis);
                    stageOut = pos;
                    prevInput = pis; // Next stage reads from this pipe
                    inputStreams.add(pis);
                    outputStreams.add(pos);
                }

                // Handle Stderr (Globally redirected or inherited)
                PrintStream stageErr = resolveStderr(redirs, rootCtx.stderr);

                // Execute Command
                ExecutionContext stageCtx = new ExecutionContext(spec.args, stageIn, new PrintStream(stageOut), stageErr);

                Thread t = new Thread(() -> {
                    try {
                        cmd.execute(stageCtx);
                    } catch (Exception e) {
                        e.printStackTrace(); // Internal error in thread
                    } finally {
                        // Crucial: Close the output stream of this stage to signal EOF to the next stage
                        try {
                            // Don't close System.out/err, only pipes or file streams
                            if (stageCtx.stdout != System.out && stageCtx.stdout != System.err) {
                                stageCtx.stdout.close();
                            }
                        } catch (IOException ignored) {}
                    }
                });
                threads.add(t);
                t.start();
            }

            // Wait for all threads
            for (Thread t : threads) {
                t.join();
            }
        }

        private OutputStream resolveFinalOutput(Redirections redirs, PrintStream defaultOut) throws IOException {
            if (redirs.stdout.isPresent()) {
                RedirectSpec spec = redirs.stdout.get();
                var opts = spec.mode == RedirectMode.APPEND
                        ? new OpenOption[]{StandardOpenOption.CREATE, StandardOpenOption.APPEND, StandardOpenOption.WRITE}
                        : new OpenOption[]{StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING, StandardOpenOption.WRITE};
                return Files.newOutputStream(spec.path, opts);
            }
            return defaultOut;
        }

        private PrintStream resolveStderr(Redirections redirs, PrintStream defaultErr) throws IOException {
            if (redirs.stderr.isPresent()) {
                RedirectSpec spec = redirs.stderr.get();
                var opts = spec.mode == RedirectMode.APPEND
                        ? new OpenOption[]{StandardOpenOption.CREATE, StandardOpenOption.APPEND, StandardOpenOption.WRITE}
                        : new OpenOption[]{StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING, StandardOpenOption.WRITE};
                return new PrintStream(Files.newOutputStream(spec.path, opts));
            }
            return defaultErr;
        }
    }

    static class CommandFactory {
        private final BuiltinRegistry builtins;
        private final PathResolver resolver;

        CommandFactory(BuiltinRegistry builtins, PathResolver resolver) {
            this.builtins = builtins;
            this.resolver = resolver;
        }

        ShellCommand create(CommandSpec spec) {
            return builtins.find(spec.name)
                    .orElseGet(() -> new ExternalCommand(spec.name, resolver));
        }
    }

    // =========================================================================
    // Commands: Built-in & External
    // =========================================================================

    static class BuiltinRegistry {
        private final Map<String, ShellCommand> commands = new HashMap<>();

        BuiltinRegistry(Environment env, PathResolver resolver) {
            commands.put("exit", ctx -> {
                int code = 0;
                if (!ctx.args.isEmpty()) code = Integer.parseInt(ctx.args.get(0));
                System.exit(code);
            });
            commands.put("echo", ctx -> {
                ctx.stdout.println(String.join(" ", ctx.args));
            });
            commands.put("pwd", ctx -> {
                ctx.stdout.println(env.getCurrentDirectory().toString());
            });
            commands.put("cd", new CdCommand(env));
            commands.put("type", new TypeCommand(this, resolver));
        }

        Optional<ShellCommand> find(String name) {
            return Optional.ofNullable(commands.get(name));
        }

        Set<String> names() { return commands.keySet(); }
    }

    static class CdCommand implements ShellCommand {
        private final Environment env;
        CdCommand(Environment env) { this.env = env; }

        @Override
        public void execute(ExecutionContext ctx) {
            String target = ctx.args.isEmpty() ? "~" : ctx.args.get(0);
            if (target.equals("~")) {
                String home = env.getHome();
                if (home == null) {
                    System.out.println("cd: HOME not set");
                    return;
                }
                target = home;
            }

            Path path = Paths.get(target);
            if (!path.isAbsolute()) path = env.getCurrentDirectory().resolve(path).normalize();

            if (Files.isDirectory(path)) {
                env.setCurrentDirectory(path);
            } else {
                System.out.println("cd: " + (ctx.args.isEmpty() ? "~" : ctx.args.get(0)) + ": No such file or directory");
            }
        }
    }

    static class TypeCommand implements ShellCommand {
        private final BuiltinRegistry registry;
        private final PathResolver resolver;

        TypeCommand(BuiltinRegistry registry, PathResolver resolver) {
            this.registry = registry;
            this.resolver = resolver;
        }

        @Override
        public void execute(ExecutionContext ctx) {
            if (ctx.args.isEmpty()) return;
            String name = ctx.args.get(0);
            if (registry.find(name).isPresent()) {
                ctx.stdout.println(name + " is a shell builtin");
            } else {
                resolver.findExecutable(name).ifPresentOrElse(
                        path -> ctx.stdout.println(name + " is " + path),
                        () -> ctx.stdout.println(name + " not found")
                );
            }
        }
    }

    static class ExternalCommand implements ShellCommand {
        private final String name;
        private final PathResolver resolver;

        ExternalCommand(String name, PathResolver resolver) {
            this.name = name;
            this.resolver = resolver;
        }

        @Override
        public void execute(ExecutionContext ctx) throws Exception {
            Path exe = resolver.findExecutable(name).orElseThrow(() -> new Exception(name + ": command not found"));

            List<String> command = new ArrayList<>();
            command.add(name);
            command.addAll(ctx.args);

            ProcessBuilder pb = new ProcessBuilder(command);
            pb.directory(ShellRuntime.env.getCurrentDirectory().toFile());

            // Map Process streams to Context streams (IMPORTANT: Avoid closing System.in/out)
            // If ctx.stdin is System.in, inherit. Else, pipe.
            // Note: ProcessBuilder methods are strict. We use stream copying threads for flexibility.

            Process p = pb.start();

            // Pump Streams
            Thread inputPump = new Thread(() -> copy(ctx.stdin, p.getOutputStream(), false));
            Thread outputPump = new Thread(() -> copy(p.getInputStream(), ctx.stdout, false)); // Output never closes System.out
            Thread errorPump = new Thread(() -> copy(p.getErrorStream(), ctx.stderr, false));

            inputPump.start();
            outputPump.start();
            errorPump.start();

            int code = p.waitFor();
            inputPump.join();
            outputPump.join();
            errorPump.join();
        }

        private void copy(InputStream in, OutputStream out, boolean closeOut) {
            try {
                byte[] buf = new byte[8192];
                int n;
                while ((n = in.read(buf)) != -1) {
                    out.write(buf, 0, n);
                    out.flush();
                }
                if (closeOut) out.close();
            } catch (IOException ignored) {}
        }
    }

    // =========================================================================
    // Environment & Path Resolution
    // =========================================================================

    static class Environment {
        private Path currentDirectory = Paths.get(System.getProperty("user.dir")).toAbsolutePath().normalize();

        Path getCurrentDirectory() { return currentDirectory; }
        void setCurrentDirectory(Path p) { this.currentDirectory = p; }
        String getEnv(String k) { return System.getenv(k); }
        String getHome() { return getEnv("HOME"); }
        String getPath() { return getEnv("PATH"); }
    }

    static class PathResolver {
        private final Environment env;
        PathResolver(Environment env) { this.env = env; }

        Optional<Path> findExecutable(String name) {
            // 1. Check if name contains path separator
            if (name.contains(File.separator)) {
                Path p = Paths.get(name);
                if (!p.isAbsolute()) p = env.getCurrentDirectory().resolve(p);
                return isExecutable(p) ? Optional.of(p.normalize()) : Optional.empty();
            }

            // 2. Search PATH
            String pathEnv = env.getPath();
            if (pathEnv == null || pathEnv.isEmpty()) return Optional.empty();

            for (String dir : pathEnv.split(File.pathSeparator)) {
                Path p = Paths.get(dir, name);
                if (isExecutable(p)) return Optional.of(p);
            }
            return Optional.empty();
        }

        boolean isExecutable(Path p) {
            return Files.isRegularFile(p) && Files.isExecutable(p);
        }

        // For autocompletion
        Set<String> findExecutableNamesByPrefix(String prefix) {
            Set<String> matches = new LinkedHashSet<>();
            String pathEnv = env.getPath();
            if (pathEnv == null) return matches;

            for (String dirStr : pathEnv.split(File.pathSeparator)) {
                try (DirectoryStream<Path> stream = Files.newDirectoryStream(Paths.get(dirStr), prefix + "*")) {
                    for (Path entry : stream) {
                        if (isExecutable(entry)) matches.add(entry.getFileName().toString());
                    }
                } catch (IOException ignored) {}
            }
            return matches;
        }
    }

    // =========================================================================
    // Interactive Input Support (Raw Mode & Tab Completion)
    // =========================================================================

    interface CompletionEngine {
        CompletionResult completeFirstWord(String word);
    }

    record CompletionResult(Kind kind, String suffix, List<String> matches) {
        enum Kind { SUFFIX, AMBIGUOUS, NO_MATCH, ALREADY_COMPLETE, NOT_APPLICABLE }
        static CompletionResult suffix(String s) { return new CompletionResult(Kind.SUFFIX, s, List.of()); }
        static CompletionResult ambiguous(List<String> m) { return new CompletionResult(Kind.AMBIGUOUS, null, m); }
        static CompletionResult of(Kind k) { return new CompletionResult(k, null, List.of()); }
    }

    static class CommandNameCompleter implements CompletionEngine {
        private final BuiltinRegistry builtins;
        private final PathResolver resolver;

        CommandNameCompleter(BuiltinRegistry b, PathResolver r) { builtins = b; resolver = r; }

        @Override
        public CompletionResult completeFirstWord(String prefix) {
            if (prefix == null || prefix.isEmpty()) return CompletionResult.of(CompletionResult.Kind.NOT_APPLICABLE);

            Set<String> candidates = new TreeSet<>();
            builtins.names().stream().filter(n -> n.startsWith(prefix)).forEach(candidates::add);
            candidates.addAll(resolver.findExecutableNamesByPrefix(prefix));

            if (candidates.isEmpty()) return CompletionResult.of(CompletionResult.Kind.NO_MATCH);
            if (candidates.size() == 1) {
                String match = candidates.iterator().next();
                if (match.equals(prefix)) return CompletionResult.of(CompletionResult.Kind.ALREADY_COMPLETE);
                return CompletionResult.suffix(match.substring(prefix.length()) + " ");
            }

            // Logic for longest common prefix ... (Simplified for brevity)
            return CompletionResult.ambiguous(new ArrayList<>(candidates));
        }
    }

    static class InteractiveInput implements AutoCloseable {
        private final InputStream in;
        private final CompletionEngine completer;
        private final String prompt;
        private boolean rawMode;

        InteractiveInput(InputStream in, CompletionEngine completer, String prompt) {
            this.in = in;
            this.completer = completer;
            this.prompt = prompt;
            enableRawMode();
        }

        // Simplistic Raw Mode implementation (stty)
        private void enableRawMode() {
            try {
                String[] cmd = {"/bin/sh", "-c", "stty -icanon -echo < /dev/tty"};
                new ProcessBuilder(cmd).inheritIO().start().waitFor();
                rawMode = true;
            } catch (Exception ignored) {}
        }

        private void disableRawMode() {
            try {
                String[] cmd = {"/bin/sh", "-c", "stty sane < /dev/tty"};
                new ProcessBuilder(cmd).inheritIO().start().waitFor();
            } catch (Exception ignored) {}
        }

        String readLine() throws IOException {
            StringBuilder buffer = new StringBuilder();
            while (true) {
                int c = in.read();
                if (c == -1) return buffer.length() == 0 ? null : buffer.toString();

                if (c == '\n') {
                    System.out.print('\n');
                    return buffer.toString();
                } else if (c == 127) { // Backspace
                    if (buffer.length() > 0) {
                        buffer.deleteCharAt(buffer.length() - 1);
                        System.out.print("\b \b");
                    }
                } else if (c == '\t') {
                    // Handle tab completion (omitted for brevity, assume similar logic to snippet)
                } else {
                    buffer.append((char)c);
                    System.out.print((char)c);
                }
            }
        }

        @Override
        public void close() {
            if (rawMode) disableRawMode();
        }
    }
}
