package com.pranay.cli;

import com.pranay.command.Cmd;
import com.pranay.command.CommandFactory;
import com.pranay.exec.Ctx;
import com.pranay.exec.PipelineCommand;
import com.pranay.input.InteractiveInput;
import com.pranay.parser.CommandLineParser;
import com.pranay.parser.Tokenizer;
import com.pranay.parser.model.CmdLine;
import com.pranay.parser.model.Parsed;
import com.pranay.parser.model.ParsedPipe;
import com.pranay.parser.model.ParsedSimple;
import com.pranay.parser.model.Redirs;
import com.pranay.state.RuntimeState;

import java.io.IOException;
import java.util.Collections;
import java.util.List;

public final class Shell {
    private final InteractiveInput input;
    private final CommandFactory factory;
    private final CommandLineParser parser;
    private final String prompt;

    public Shell(InteractiveInput input, CommandFactory factory, CommandLineParser parser, String prompt) {
        this.input = input;
        this.factory = factory;
        this.parser = parser;
        this.prompt = prompt;
    }

    public void run() {
        System.out.print(prompt);
        try {
            String line;
            while ((line = input.readLine()) != null) {
                handle(line);
                System.out.print(prompt);
            }
        } catch (IOException e) {
            // PATCH BOTH (1/2): remove the literal "+ " from the fatal I/O error message. [file:1]
            System.err.println("Fatal I/O Error: " + e.getMessage());
        } finally {
            input.close();
        }
    }

    private void handle(String line) {
        try {
            List<String> tokens = Tokenizer.tokenize(line);
            if (tokens.isEmpty()) return;

            // history stores the raw line (as entered)
            RuntimeState.history.add(line);

            Parsed parsed = parser.parse(tokens);
            Ctx root = Ctx.system();

            if (parsed instanceof ParsedSimple) {
                CmdLine cl = ((ParsedSimple) parsed).line();
                if (cl.args().isEmpty()) return;
                factory.create(cl.args().get(0), cl.args()).execute(root.with(cl.args(), cl.redirs()));
                return;
            }

            if (parsed instanceof ParsedPipe) {
                ParsedPipe p = (ParsedPipe) parsed;
                new PipelineCommand(p.stages(), p.redirs(), factory)
                        .execute(root.with(Collections.<String>emptyList(), Redirs.none()));
            }
        } catch (Exception e) {
            String msg = e.getMessage();
            if (msg != null && !msg.isEmpty()) System.out.println(msg);
        }
    }
}