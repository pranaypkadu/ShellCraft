package com.pranay.builtin;

import com.pranay.command.Builtin;
import com.pranay.exec.Ctx;

import java.io.PrintStream;

public final class Echo extends Builtin {
    @Override
    protected void run(Ctx ctx, PrintStream out) {
        if (ctx.args().size() == 1) { out.println(); return; }
        StringBuilder sb = new StringBuilder();
        for (int i = 1; i < ctx.args().size(); i++) {
            if (i > 1) sb.append(" ");
            sb.append(ctx.args().get(i));
        }
        out.println(sb.toString());
    }
}