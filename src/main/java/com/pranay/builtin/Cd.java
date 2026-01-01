package com.pranay.builtin;

import com.pranay.command.Builtin;
import com.pranay.exec.Ctx;
import com.pranay.state.RuntimeState;

import java.io.File;
import java.io.PrintStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

public final class Cd extends Builtin {
    @Override
    protected void run(Ctx ctx, PrintStream out) {
        if (ctx.args().size() > 2) return;

        final String original = (ctx.args().size() == 1) ? "~" : ctx.args().get(1);
        String target = original;
        String home = RuntimeState.env.home();

        if ("~".equals(target)) {
            if (home == null) { out.println("cd: HOME not set"); return; }
            target = home;
        } else if (target.startsWith("~") && target.startsWith("~" + File.separator)) {
            if (home == null) { out.println("cd: HOME not set"); return; }
            target = home + target.substring(1);
        }

        Path p = Paths.get(target);
        if (!p.isAbsolute()) p = RuntimeState.env.cwd().resolve(p);
        Path resolved = p.normalize();

        if (Files.exists(resolved) && Files.isDirectory(resolved)) RuntimeState.env.cwd(resolved);
        else out.println("cd: " + original + ": No such file or directory");
    }
}