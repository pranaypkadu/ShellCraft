package com.pranay.builtin;

import com.pranay.command.Builtin;
import com.pranay.exec.Ctx;
import com.pranay.history.HistoryFile;
import com.pranay.state.RuntimeState;

import java.io.PrintStream;

public final class Exit extends Builtin {
    @Override
    protected void run(Ctx ctx, PrintStream out) {
        int code = 0;
        if (ctx.args().size() > 1) {
            try { code = Integer.parseInt(ctx.args().get(1)); }
            catch (NumberFormatException ignored) { }
        }

        // Persist history to HISTFILE on exit (if set).
        HistoryFile.appendOnExit(RuntimeState.env, RuntimeState.history);

        System.exit(code);
    }
}