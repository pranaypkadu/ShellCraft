package com.pranay.exec;

import com.pranay.command.Cmd;
import com.pranay.command.CommandFactory;
import com.pranay.parser.model.Redirs;
import com.pranay.util.Lists;

import java.io.IOException;
import java.io.InputStream;
import java.io.PipedInputStream;
import java.io.PipedOutputStream;
import java.io.PrintStream;
import java.util.ArrayList;
import java.util.List;

public final class PipelineCommand implements Cmd {
    private final List<List<String>> stages;
    private final Redirs lastRedirs; // applies to last stage only
    private final CommandFactory factory;

    public PipelineCommand(List<List<String>> stages, Redirs lastRedirs, CommandFactory factory) {
        this.stages = Lists.copyOfNoNulls(stages);
        this.lastRedirs = lastRedirs;
        this.factory = factory;
    }

    @Override
    public void execute(Ctx ctx) {
        if (stages.isEmpty()) return;

        if (stages.size() == 1) {
            List<String> argv = stages.get(0);
            if (argv.isEmpty()) return;
            factory.create(argv.get(0), argv).execute(new Ctx(argv, lastRedirs, ctx.in(), ctx.out(), ctx.err()));
            return;
        }

        List<Thread> workers = new ArrayList<Thread>(stages.size() - 1);
        InputStream nextIn = ctx.in();

        for (int i = 0; i < stages.size(); i++) {
            List<String> argv = stages.get(i);
            if (argv.isEmpty()) return;

            boolean last = (i == stages.size() - 1);
            InputStream stageIn = nextIn;

            if (!last) {
                try {
                    final PipedInputStream pipeIn = new PipedInputStream();
                    final PipedOutputStream pipeOut = new PipedOutputStream(pipeIn);
                    final PrintStream stageOut = new PrintStream(pipeOut, true);

                    final Cmd cmd = factory.create(argv.get(0), argv);
                    final Ctx stageCtx = new Ctx(argv, Redirs.none(), stageIn, stageOut, ctx.err());

                    Thread t = new Thread(new Runnable() {
                        @Override
                        public void run() {
                            try {
                                cmd.execute(stageCtx);
                            } catch (Exception ignored) {
                                // ignored
                            } finally {
                                try { stageOut.close(); } catch (Exception ignored) { }
                                try { pipeOut.close(); } catch (Exception ignored) { }
                            }
                        }
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