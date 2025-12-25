import java.io.BufferedReader;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
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

/**
 * Interactive Shell with Autocompletion.
 * Java 8 Single-File Implementation.
 */
public class Main {

    public static void main(String[] args) {
        Environment env = new Environment();
        Terminal terminal = new Terminal();

        // Autocompleter specifically for echo and exit as requested
        Completer completer = new SimpleCompleter(Arrays.asList("echo", "exit"));
        LineReader lineReader = new LineReader(terminal, completer);

        PathResolver pathResolver = new PathResolver(env);
        BuiltinRegistry builtins = new BuiltinRegistry(pathResolver);
        CommandDispatcher dispatcher = new CommandDispatcher(builtins, pathResolver);
        CommandParser parser = new CommandParser();

        Shell shell = new Shell(env, lineReader, parser, dispatcher);
        shell.run();
    }

    // =========================================================================
    // Core Controller
    // =========================================================================

    static final class Shell {
        private static final String PROMPT = "$ ";

        private final Environment env;
        private final LineReader lineReader;
        private final CommandParser parser;
        private final CommandDispatcher dispatcher;

        Shell(Environment env, LineReader lineReader, CommandParser parser, CommandDispatcher dispatcher) {
            this.env = env;
            this.lineReader = lineReader;
            this.parser = parser;
            this.dispatcher = dispatcher;
        }

        void run() {
            // Initial prompt
            System.out.print(PROMPT);

            while (true) {
                try {
                    // Read line with autocompletion support
                    String rawLine = lineReader.readLine();
                    if (rawLine == null) break; // EOF

                    handle(rawLine);
                    System.out.print(PROMPT);
                } catch (Exception e) {
                    // Fatal I/O errors that break the loop
                    break;
                }
            }
        }

        private void handle(String line) {
            try {
                List<String> tokens = Tokenizer.tokenize(line);
                if (tokens.isEmpty()) return;

                ParsedCommand parsed = parser.parse(tokens);
                ShellCommand command = dispatcher.dispatch(parsed.commandName, parsed.args);

                ExecutionContext ctx = new ExecutionContext(env, parsed.args, parsed.redirections);
                command.execute(ctx);
            } catch (Exception e) {
                if (e.getMessage() != null && !e.getMessage().isEmpty()) {
                    System.out.println(e.getMessage());
                }
            }
        }
    }

    // =========================================================================
    // Terminal / Input Handling (Raw Mode & Autocompletion)
    // =========================================================================

    interface Completer {
        // Returns the suffix to append if a unique match is found, otherwise empty
        Optional<String> complete(String prefix);
    }

    static final class SimpleCompleter implements Completer {
        private final List<String> candidates;

        SimpleCompleter(List<String> candidates) {
            this.candidates = candidates;
        }

        @Override
        public Optional<String> complete(String prefix) {
            List<String> matches = new ArrayList<>();
            for (String candidate : candidates) {
                if (candidate.startsWith(prefix)) {
                    matches.add(candidate);
                }
            }

            if (matches.size() == 1) {
                String match = matches.get(0);
                return Optional.of(match.substring(prefix.length()) + " ");
            }
            return Optional.empty();
        }
    }

    static final class Terminal {
        void enableRawMode() {
            try {
                String[] cmd = {"/bin/sh", "-c", "stty -icanon -echo < /dev/tty"};
                Runtime.getRuntime().exec(cmd).waitFor();
            } catch (Exception ignored) {
                // Fallback or ignore if not a TTY (e.g. testing pipes)
            }
        }

        void disableRawMode() {
            try {
                String[] cmd = {"/bin/sh", "-c", "stty sane < /dev/tty"};
                Runtime.getRuntime().exec(cmd).waitFor();
            } catch (Exception ignored) {
            }
        }

        int readByte() throws IOException {
            return System.in.read();
        }
    }

    static final class LineReader {
        private final Terminal terminal;
        private final Completer completer;

        LineReader(Terminal terminal, Completer completer) {
            this.terminal = terminal;
            this.completer = completer;
        }

        String readLine() throws IOException {
            terminal.enableRawMode();
            StringBuilder buffer = new StringBuilder();

            try {
                while (true) {
                    int c = terminal.readByte();

                    if (c == -1 || c == 4) { // EOF or EOT
                        return null;
                    } else if (c == '\n') { // Enter
                        System.out.print("\r\n");
                        return buffer.toString();
                    } else if (c == '\t') { // Tab
                        handleTab(buffer);
                    } else if (c == 127) { // Backspace
                        handleBackspace(buffer);
                    } else { // Normal character
                        buffer.append((char) c);
                        System.out.print((char) c);
                    }
                }
            } finally {
                terminal.disableRawMode();
            }
        }

        private void handleTab(StringBuilder buffer) {
            // Autocomplete only if we are typing the first word (the command)
            String currentText = buffer.toString();
            if (currentText.trim().isEmpty()) return; // Nothing typed
            if (currentText.contains(" ")) return;    // Already typing arguments

            Optional<String> suffix = completer.complete(currentText);
            if (suffix.isPresent()) {
                String toAppend = suffix.get();
                buffer.append(toAppend);
                System.out.print(toAppend);
            } else {
                // Optional: Bell sound for no match or ambiguity
                System.out.print("\u0007");
            }
        }

        private void handleBackspace(StringBuilder buffer) {
            if (buffer.length() > 0) {
                buffer.deleteCharAt(buffer.length() - 1);
                // Move back, print space to erase, move back again
                System.out.print("\b \b");
            }
        }
    }

    // =========================================================================
    // Lexer (Tokenizer)
    // =========================================================================

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
                        if (c == '\'') state = State.DEFAULT;
                        else current.append(c);
                        break;
                    case DOUBLE_QUOTE:
                        if (c == '"') state = State.DEFAULT;
                        else if (c == '\\') state = State.DOUBLE_QUOTE_ESCAPE;
                        else current.append(c);
                        break;
                    case DOUBLE_QUOTE_ESCAPE:
                        if (c == '\\' || c == '"') current.append(c);
                        else { current.append('\\'); current.append(c); }
                        state = State.DOUBLE_QUOTE;
                        break;
                }
            }
            if (inToken) tokens.add(current.toString());
            return tokens;
        }
    }

    // =========================================================================
    // Parsing & Redirection
    // =========================================================================

    static final class RedirectionSpec {
        final boolean isAppend;
        final boolean isStderr;
        final Path target;

        RedirectionSpec(boolean isAppend, boolean isStderr, Path target) {
            this.isAppend = isAppend;
            this.isStderr = isStderr;
            this.target = target;
        }
    }

    static final class ParsedCommand {
        final String commandName;
        final List<String> args;
        final Redirections redirections;

        ParsedCommand(String commandName, List<String> args, Redirections redirections) {
            this.commandName = commandName;
            this.args = args;
            this.redirections = redirections;
        }
    }

    static final class Redirections {
        final Optional<RedirectionSpec> stdout;
        final Optional<RedirectionSpec> stderr;

        Redirections(Optional<RedirectionSpec> stdout, Optional<RedirectionSpec> stderr) {
            this.stdout = stdout;
            this.stderr = stderr;
        }
    }

    static final class CommandParser {
        private static final String OP_GT = ">";
        private static final String OP_1GT = "1>";
        private static final String OP_DGT = ">>";
        private static final String OP_1DGT = "1>>";
        private static final String OP_2GT = "2>";
        private static final String OP_2DGT = "2>>";

        ParsedCommand parse(List<String> tokens) {
            if (tokens.isEmpty()) return new ParsedCommand("", Collections.emptyList(), new Redirections(Optional.empty(), Optional.empty()));

            Optional<RedirectionSpec> outRedirect = Optional.empty();
            Optional<RedirectionSpec> errRedirect = Optional.empty();
            List<String> args = new ArrayList<>(tokens);

            // Check specifically the last 2 tokens for redirection pattern
            if (args.size() >= 2) {
                String op = args.get(args.size() - 2);
                String file = args.get(args.size() - 1);
                Path path = Paths.get(file);
                boolean matched = false;

                if (OP_GT.equals(op) || OP_1GT.equals(op)) {
                    outRedirect = Optional.of(new RedirectionSpec(false, false, path));
                    matched = true;
                } else if (OP_DGT.equals(op) || OP_1DGT.equals(op)) {
                    outRedirect = Optional.of(new RedirectionSpec(true, false, path));
                    matched = true;
                } else if (OP_2GT.equals(op)) {
                    errRedirect = Optional.of(new RedirectionSpec(false, true, path));
                    matched = true;
                } else if (OP_2DGT.equals(op)) {
                    errRedirect = Optional.of(new RedirectionSpec(true, true, path));
                    matched = true;
                }

                if (matched) {
                    args.remove(args.size() - 1);
                    args.remove(args.size() - 1);
                }
            }

            String name = args.isEmpty() ? "" : args.get(0);
            return new ParsedCommand(name, args, new Redirections(outRedirect, errRedirect));
        }
    }

    // =========================================================================
    // Execution Environment
    // =========================================================================

    static final class Environment {
        private Path currentDirectory;

        Environment() {
            this.currentDirectory = Paths.get(System.getProperty("user.dir")).toAbsolutePath().normalize();
        }

        Path getCurrentDirectory() { return currentDirectory; }
        void setCurrentDirectory(Path p) { this.currentDirectory = p.toAbsolutePath().normalize(); }
        String getHome() { return System.getenv("HOME"); }
        String getPath() { return System.getenv("PATH"); }
    }

    static final class PathResolver {
        private final Environment env;

        PathResolver(Environment env) {
            this.env = env;
        }

        Optional<Path> findExecutable(String name) {
            // Direct path check (contains separator)
            if (name.contains(File.separator) || name.contains("/") || name.contains("\\")) {
                Path p = env.getCurrentDirectory().resolve(name).normalize();
                return isExecutable(p) ? Optional.of(p) : Optional.empty();
            }

            // Path lookup
            String pathEnv = env.getPath();
            if (pathEnv == null) return Optional.empty();

            String[] dirs = pathEnv.split(File.pathSeparator);
            for (String dir : dirs) {
                if (dir.isEmpty()) continue;
                Path p = Paths.get(dir).resolve(name);
                if (isExecutable(p)) return Optional.of(p);
            }
            return Optional.empty();
        }

        private boolean isExecutable(Path p) {
            return Files.isRegularFile(p) && Files.isExecutable(p);
        }
    }

    static final class ExecutionContext {
        final Environment env;
        final List<String> args;
        final Redirections redirections;

        ExecutionContext(Environment env, List<String> args, Redirections redirections) {
            this.env = env;
            this.args = args;
            this.redirections = redirections;
        }
    }

    // =========================================================================
    // Commands & Dispatcher
    // =========================================================================

    interface ShellCommand {
        void execute(ExecutionContext ctx);
    }

    static final class CommandDispatcher {
        private final BuiltinRegistry builtins;
        private final PathResolver resolver;

        CommandDispatcher(BuiltinRegistry builtins, PathResolver resolver) {
            this.builtins = builtins;
            this.resolver = resolver;
        }

        ShellCommand dispatch(String name, List<String> args) {
            Optional<ShellCommand> builtin = builtins.get(name);
            if (builtin.isPresent()) return builtin.get();
            return new ExternalCommand(name, resolver);
        }
    }

    static final class BuiltinRegistry {
        private final Map<String, ShellCommand> registry = new HashMap<>();

        BuiltinRegistry(PathResolver resolver) {
            registry.put("exit", new ExitCommand());
            registry.put("echo", new EchoCommand());
            registry.put("pwd", new PwdCommand());
            registry.put("cd", new CdCommand());
            registry.put("type", new TypeCommand(this, resolver));
        }

        Optional<ShellCommand> get(String name) {
            return Optional.ofNullable(registry.get(name));
        }

        boolean contains(String name) {
            return registry.containsKey(name);
        }
    }

    // =========================================================================
    // Output Strategies
    // =========================================================================

    static class IOManager {
        static void executeWithRedirection(ExecutionContext ctx, RunnableWithStream action) {
            // Handle Stderr setup first (independent of stdout logic)
            if (ctx.redirections.stderr.isPresent()) {
                RedirectionSpec spec = ctx.redirections.stderr.get();
                try {
                    ensureFile(spec.target, spec.isAppend);
                } catch (IOException e) {
                    System.err.println("Redirection error: " + e.getMessage());
                }
            }

            // Handle Stdout
            try (OutputStream out = resolveOutputStream(ctx.redirections.stdout)) {
                // If redirecting to file, wrap in PrintStream (non-closing for System.out)
                PrintStream ps = (out == System.out)
                        ? System.out
                        : new PrintStream(out);

                action.run(ps);

                if (ps != System.out) ps.flush(); // Ensure flush for files
            } catch (IOException e) {
                System.err.println("Redirection error: " + e.getMessage());
            }
        }

        private static OutputStream resolveOutputStream(Optional<RedirectionSpec> spec) throws IOException {
            if (!spec.isPresent()) return System.out;

            Path p = spec.get().target;
            OpenOption[] options = spec.get().isAppend
                    ? new OpenOption[]{StandardOpenOption.CREATE, StandardOpenOption.APPEND, StandardOpenOption.WRITE}
                    : new OpenOption[]{StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING, StandardOpenOption.WRITE};

            return Files.newOutputStream(p, options);
        }

        private static void ensureFile(Path p, boolean append) throws IOException {
            OpenOption[] options = append
                    ? new OpenOption[]{StandardOpenOption.CREATE, StandardOpenOption.APPEND, StandardOpenOption.WRITE}
                    : new OpenOption[]{StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING, StandardOpenOption.WRITE};
            Files.newOutputStream(p, options).close();
        }

        // Helper interface
        interface RunnableWithStream {
            void run(PrintStream out);
        }

        // Helper for OpenOption
        interface OpenOption extends java.nio.file.OpenOption {}
    }

    // =========================================================================
    // Builtin Implementations
    // =========================================================================

    static class ExitCommand implements ShellCommand {
        @Override
        public void execute(ExecutionContext ctx) {
            IOManager.executeWithRedirection(ctx, (out) -> {
                int code = 0;
                if (ctx.args.size() > 1) {
                    try { code = Integer.parseInt(ctx.args.get(1)); }
                    catch (NumberFormatException ignored) {}
                }
                System.exit(code);
            });
        }
    }

    static class EchoCommand implements ShellCommand {
        @Override
        public void execute(ExecutionContext ctx) {
            IOManager.executeWithRedirection(ctx, (out) -> {
                StringBuilder sb = new StringBuilder();
                for (int i = 1; i < ctx.args.size(); i++) {
                    if (i > 1) sb.append(' ');
                    sb.append(ctx.args.get(i));
                }
                out.println(sb.toString());
            });
        }
    }

    static class PwdCommand implements ShellCommand {
        @Override
        public void execute(ExecutionContext ctx) {
            IOManager.executeWithRedirection(ctx, (out) -> {
                out.println(ctx.env.getCurrentDirectory());
            });
        }
    }

    static class CdCommand implements ShellCommand {
        @Override
        public void execute(ExecutionContext ctx) {
            IOManager.executeWithRedirection(ctx, (out) -> {
                if (ctx.args.size() < 2) return;
                String target = ctx.args.get(1);
                String originalArg = target;

                if ("~".equals(target)) {
                    target = ctx.env.getHome();
                    if (target == null) { out.println("cd: HOME not set"); return; }
                } else if (target.startsWith("~/")) {
                    String home = ctx.env.getHome();
                    if (home == null) { out.println("cd: HOME not set"); return; }
                    target = home + target.substring(1);
                }

                Path p = ctx.env.getCurrentDirectory().resolve(target).normalize();
                if (Files.exists(p) && Files.isDirectory(p)) {
                    ctx.env.setCurrentDirectory(p);
                } else {
                    out.println("cd: " + originalArg + ": No such file or directory");
                }
            });
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
            IOManager.executeWithRedirection(ctx, (out) -> {
                if (ctx.args.size() < 2) return;
                String name = ctx.args.get(1);
                if (registry.contains(name)) {
                    out.println(name + " is a shell builtin");
                } else {
                    Optional<Path> p = resolver.findExecutable(name);
                    if (p.isPresent()) {
                        out.println(name + " is " + p.get());
                    } else {
                        out.println(name + ": not found");
                    }
                }
            });
        }
    }

    // =========================================================================
    // External Command Implementation
    // =========================================================================

    static class ExternalCommand implements ShellCommand {
        private final String name;
        private final PathResolver resolver;

        ExternalCommand(String name, PathResolver resolver) {
            this.name = name;
            this.resolver = resolver;
        }

        @Override
        public void execute(ExecutionContext ctx) {
            Optional<Path> executable = resolver.findExecutable(name);
            if (!executable.isPresent()) {
                System.out.println(name + ": command not found");
                return;
            }

            try {
                ProcessBuilder pb = new ProcessBuilder(ctx.args);
                pb.directory(ctx.env.getCurrentDirectory().toFile());

                // Input always inherited
                pb.redirectInput(ProcessBuilder.Redirect.INHERIT);

                // Configure Stdout
                if (ctx.redirections.stdout.isPresent()) {
                    RedirectionSpec spec = ctx.redirections.stdout.get();
                    if (spec.isAppend) {
                        ensureFile(spec.target, true);
                        pb.redirectOutput(ProcessBuilder.Redirect.appendTo(spec.target.toFile()));
                    } else {
                        ensureFile(spec.target, false);
                        pb.redirectOutput(spec.target.toFile());
                    }
                } else {
                    pb.redirectOutput(ProcessBuilder.Redirect.INHERIT);
                }

                // Configure Stderr
                if (ctx.redirections.stderr.isPresent()) {
                    RedirectionSpec spec = ctx.redirections.stderr.get();
                    if (spec.isAppend) {
                        ensureFile(spec.target, true);
                        pb.redirectError(ProcessBuilder.Redirect.appendTo(spec.target.toFile()));
                    } else {
                        ensureFile(spec.target, false);
                        pb.redirectError(spec.target.toFile());
                    }
                } else {
                    pb.redirectError(ProcessBuilder.Redirect.INHERIT);
                }

                Process p = pb.start();
                p.waitFor();

            } catch (Exception e) {
                // Command failed to start or find (though we checked)
                System.out.println(name + ": command not found");
            }
        }

        private void ensureFile(Path p, boolean append) throws IOException {
            StandardOpenOption[] options = append
                    ? new StandardOpenOption[]{StandardOpenOption.CREATE, StandardOpenOption.APPEND, StandardOpenOption.WRITE}
                    : new StandardOpenOption[]{StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING, StandardOpenOption.WRITE};
            Files.newOutputStream(p, options).close();
        }
    }
}