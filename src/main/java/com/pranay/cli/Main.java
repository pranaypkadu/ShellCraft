package com.pranay.cli;

import com.pranay.command.BuiltinRegistry;
import com.pranay.command.CommandFactoryImpl;
import com.pranay.env.Env;
import com.pranay.env.PathResolver;
import com.pranay.history.HistoryFile;
import com.pranay.input.CommandNameCompleter;
import com.pranay.input.InteractiveInput;
import com.pranay.parser.CommandLineParser;
import com.pranay.state.RuntimeState;

public class Main {
    static final String PROMPT = "$ ";

    public static void main(String[] args) {
        Env env = RuntimeState.env;

        // Load history on startup from HISTFILE (if set) and remember cursor for "new this session".
        HistoryFile.loadOnStartup(env, RuntimeState.history);

        PathResolver resolver = new PathResolver(env);

        BuiltinRegistry builtins = new BuiltinRegistry(resolver);
        CommandFactoryImpl factory = new CommandFactoryImpl(builtins, resolver);

        InteractiveInput input = new InteractiveInput(System.in, new CommandNameCompleter(builtins, resolver), PROMPT);
        new Shell(input, factory, new CommandLineParser(), PROMPT).run();
    }
}