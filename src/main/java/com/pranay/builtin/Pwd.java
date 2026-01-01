package com.pranay.builtin;

import com.pranay.command.Builtin;
import com.pranay.exec.Ctx;
import com.pranay.state.RuntimeState;

import java.io.PrintStream;

public final class Pwd extends Builtin {
    @Override
    protected void run(Ctx ctx, PrintStream out) {
        out.println(RuntimeState.env.cwd());
    }
}