/*
 * Copyright 2014 the original author or authors.
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

package org.gradle.api.internal.changedetection.state;

import org.gradle.api.file.FileTreeElement;
import org.gradle.api.internal.hash.Hasher;

import java.io.File;

public interface FileSnapshotter extends Hasher {
    /**
     * Takes a snapshot of the current content of the given file. The provided file must exist and be a file (rather than, say, a directory).
     */
    FileSnapshot snapshot(File file);

    /**
     * Takes a snapshot of the current content of the given file, assuming the given file metadata. The provided file must exist and be a file (rather than, say, a directory).
     */
    FileSnapshot snapshot(FileTreeElement fileDetails);
}
