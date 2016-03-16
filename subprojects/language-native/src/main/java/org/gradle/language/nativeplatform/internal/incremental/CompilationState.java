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
package org.gradle.language.nativeplatform.internal.incremental;

import java.io.File;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public class CompilationState {
    List<File> sourceInputs = new ArrayList<File>();
    Map<File, CompilationFileState> fileStates = new HashMap<File, CompilationFileState>();

    public List<File> getSourceInputs() {
        return sourceInputs;
    }

    public void addSourceInput(File file) {
        sourceInputs.add(file);
    }

    public CompilationFileState getState(File file) {
        return fileStates.get(file);
    }

    public void setState(File file, CompilationFileState compilationFileState) {
        fileStates.put(file, compilationFileState);
    }
}
