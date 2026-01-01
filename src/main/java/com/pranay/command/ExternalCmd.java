package com.pranay.command;

import com.pranay.env.PathResolver;
import com.pranay.exec.Ctx;
import com.pranay.exec.IO;
import com.pranay.parser.model.RMode;
import com.pranay.parser.model.RSpec;
import com.pranay.state.RuntimeState;
import com.pranay.util.Lists;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.PrintStream;
import java.util.ArrayList;
import java.util.List;

public final class ExternalCmd implements Cmd {
    private final String name;
    private final List<String> argv;
    private final PathResolver resolver;

    public ExternalCmd(String name, List<String> argv, PathResolver resolver) {
        this.name = name;
        this.argv = Lists.copyOfNoNulls(argv);
        this.resolver = resolver;
    }

    @Override
    public void execute(Ctx ctx) {
        try {
            if (!resolver.findExecutable(name).isPresent()) {
                System.out.println(name + ": command not found");
                return;
            }

            ProcessBuilder pb = new ProcessBuilder(new ArrayList<String>(argv));
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
                final InputStream src = ctx.in();
                final OutputStream dst = p.getOutputStream();
                inPump = new Thread(new Runnable() {
                    @Override public void run() { pump(src, dst); }
                });
                inPump.start();
            }

            Thread outPump = null;
            boolean outPiped = !ctx.redirs().out().isPresent() && ctx.out() != System.out;
            if (outPiped) {
                final InputStream src = p.getInputStream();
                final PrintStream dst = ctx.out();
                outPump = new Thread(new Runnable() {
                    @Override public void run() { pump(src, dst); }
                });
                outPump.start();
            }

            p.waitFor();
            if (inPump != null) inPump.join();
            if (outPump != null) outPump.join();
        } catch (InterruptedException e) {
            // PATCH BOTH (2/2): don't say "command not found" on interrupt. [file:1]
            Thread.currentThread().interrupt();
            System.out.println(name + ": interrupted");
        } catch (IOException e) {
            // PATCH BOTH (2/2): don't say "command not found" on IO failure after resolution. [file:1]
            String msg = e.getMessage();
            if (msg != null && !msg.isEmpty()) System.out.println(name + ": " + msg);
            else System.out.println(name + ": I/O error");
        }
    }

    private static void pump(InputStream in, OutputStream out) {
        // ALWAYS close child's stdin to signal EOF
        try {
            byte[] buf = new byte[8192];
            int n;
            while ((n = in.read(buf)) != -1) {
                out.write(buf, 0, n);
                out.flush();
            }
        } catch (IOException ignored) {
            // ignored
        } finally {
            try { out.close(); } catch (IOException ignored) { }
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