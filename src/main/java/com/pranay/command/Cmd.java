package com.pranay.command;

import com.pranay.exec.Ctx;

public interface Cmd {
    void execute(Ctx ctx);
}