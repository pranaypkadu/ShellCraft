package com.pranay.builtin;

import com.pranay.command.Builtin;
import com.pranay.command.BuiltinRegistry;
import com.pranay.env.PathResolver;
import com.pranay.exec.Ctx;

import java.io.PrintStream;
import java.nio.file.Path;
import java.util.Optional;

public final class Type extends Builtin {
    private final BuiltinRegistry builtins;
    private final PathResolver resolver;

    public Type(BuiltinRegistry builtins, PathResolver resolver) {
        this.builtins = builtins;
        this.resolver = resolver;
    }

    @Override
    protected void run(Ctx ctx, PrintStream out) {
        if (ctx.args().size() < 2) return;
        String t = ctx.args().get(1);

        if (builtins.isBuiltin(t)) { out.println(t + " is a shell builtin"); return; }

        Optional<Path> p = resolver.findExecutable(t);
        if (p.isPresent()) out.println(t + " is " + p.get().toAbsolutePath());
        else out.println(t + ": not found");
    }
}