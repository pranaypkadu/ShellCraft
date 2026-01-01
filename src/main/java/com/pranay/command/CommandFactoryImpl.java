package com.pranay.command;

import com.pranay.env.PathResolver;

import java.util.List;

public final class CommandFactoryImpl implements CommandFactory {
    private final BuiltinRegistry builtins;
    private final PathResolver resolver;

    public CommandFactoryImpl(BuiltinRegistry builtins, PathResolver resolver) {
        this.builtins = builtins;
        this.resolver = resolver;
    }

    @Override
    public Cmd create(String name, List<String> argv) {
        Cmd b = builtins.get(name);
        return (b != null) ? b : new ExternalCmd(name, argv, resolver);
    }
}