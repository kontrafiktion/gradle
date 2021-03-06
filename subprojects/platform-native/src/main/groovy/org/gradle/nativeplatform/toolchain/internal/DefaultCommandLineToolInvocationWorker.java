/*
 * Copyright 2013 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.gradle.nativeplatform.toolchain.internal;

import com.google.common.base.Joiner;
import org.gradle.internal.os.OperatingSystem;
import org.gradle.process.internal.ExecAction;
import org.gradle.process.internal.ExecActionFactory;
import org.gradle.process.internal.ExecException;
import org.gradle.util.GFileUtils;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.OutputStream;

public class DefaultCommandLineToolInvocationWorker implements CommandLineToolInvocationWorker {
    private final String name;
    private final File executable;
    private final ExecActionFactory execActionFactory;

    public DefaultCommandLineToolInvocationWorker(String name, File executable, ExecActionFactory execActionFactory) {
        this.name = name;
        this.executable = executable;
        this.execActionFactory = execActionFactory;
    }

    @Override
    public String getDisplayName() {
        return String.format("command line tool '%s'", name);
    }

    @Override
    public String toString() {
        return getDisplayName();
    }

    @Override
    public void execute(CommandLineToolInvocation invocation) {
        ExecAction toolExec = execActionFactory.newExecAction();

        toolExec.executable(executable);
        if (invocation.getWorkDirectory() != null) {
            GFileUtils.mkdirs(invocation.getWorkDirectory());
            toolExec.workingDir(invocation.getWorkDirectory());
        }

        toolExec.args(invocation.getArgs());

        if (!invocation.getPath().isEmpty()) {
            String pathVar = OperatingSystem.current().getPathVar();
            String toolPath = Joiner.on(File.pathSeparator).join(invocation.getPath());
            toolPath = toolPath + File.pathSeparator + System.getenv(pathVar);
            toolExec.environment(pathVar, toolPath);
            if (OperatingSystem.current().isWindows() && toolExec.getEnvironment().containsKey(pathVar.toUpperCase())) {
                toolExec.getEnvironment().remove(pathVar.toUpperCase());
            }
        }

        toolExec.environment(invocation.getEnvironment());

        ByteArrayOutputStream errOutput = new ByteArrayOutputStream();
        ByteArrayOutputStream stdOutput = new ByteArrayOutputStream();
        toolExec.setErrorOutput(errOutput);
        toolExec.setStandardOutput(stdOutput);

        try {
            toolExec.execute();
            invocation.getLogger().operationSuccess(invocation.getDescription(), combineOutput(stdOutput, errOutput));
        } catch (ExecException e) {
            invocation.getLogger().operationFailed(invocation.getDescription(), combineOutput(stdOutput, errOutput));
            throw new CommandLineToolInvocationFailure(invocation, String.format("%s failed while %s.", name, invocation.getDescription()));
        }
    }

    private String combineOutput(OutputStream stdOutput, OutputStream errOutput) {
        return stdOutput.toString() + errOutput.toString();
    }
}
