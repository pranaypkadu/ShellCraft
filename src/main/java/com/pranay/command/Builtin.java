package com.pranay.command;

import com.pranay.exec.Ctx;
import com.pranay.exec.IO;
import com.pranay.exec.OutTarget;

import java.io.IOException;
import java.io.PrintStream;

public abstract class Builtin implements Cmd {
    @Override
    public final void execute(Ctx ctx) {
        if (ctx.redirs().err().isPresent()) {
            try {
                IO.touch(ctx.redirs().err().get());
            } catch (IOException e) {
                System.err.println("Redirection error: " + e.getMessage());
            }
        }

        try {
            OutTarget t = IO.out(ctx.redirs().out(), ctx.out());
            try {
                run(ctx, t.ps());
            } finally {
                t.close();
            }
        } catch (IOException e) {
            System.err.println("Redirection error: " + e.getMessage());
        }
    }

    protected abstract void run(Ctx ctx, PrintStream out);
}