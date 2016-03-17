/*
 * Copyright 2011 the original author or authors.
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
package org.gradle.api.internal.notations;

import com.google.common.collect.Maps;
import org.gradle.api.artifacts.SelfResolvingDependency;
import org.gradle.api.internal.ClassPathRegistry;
import org.gradle.api.internal.artifacts.dependencies.DefaultSelfResolvingDependency;
import org.gradle.api.internal.artifacts.dsl.dependencies.DependencyFactory;
import org.gradle.api.internal.file.FileCollectionInternal;
import org.gradle.api.internal.file.FileResolver;
import org.gradle.api.internal.file.collections.FileCollectionAdapter;
import org.gradle.api.internal.impldeps.GradleImplDepsRelocatedJar;
import org.gradle.internal.exceptions.DiagnosticsVisitor;
import org.gradle.internal.installation.CurrentGradleInstallation;
import org.gradle.internal.reflect.Instantiator;
import org.gradle.internal.typeconversion.NotationConvertResult;
import org.gradle.internal.typeconversion.NotationConverter;
import org.gradle.internal.typeconversion.TypeConversionException;

import java.io.File;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.concurrent.locks.Lock;
import java.util.concurrent.locks.ReentrantLock;

import static org.gradle.api.internal.artifacts.dsl.dependencies.DependencyFactory.ClassPathNotation.*;

public class DependencyClassPathNotationConverter implements NotationConverter<DependencyFactory.ClassPathNotation, SelfResolvingDependency> {

    private final ClassPathRegistry classPathRegistry;
    private final Instantiator instantiator;
    private final FileResolver fileResolver;
    private final CurrentGradleInstallation currentGradleInstallation;
    private final File gradleUserHomeDir;
    private final String gradleVersion;
    private final Map<DependencyFactory.ClassPathNotation, SelfResolvingDependency> internCache = Maps.newEnumMap(DependencyFactory.ClassPathNotation.class);
    private final Lock internCacheWriteLock = new ReentrantLock();

    public DependencyClassPathNotationConverter(
        Instantiator instantiator,
        ClassPathRegistry classPathRegistry,
        FileResolver fileResolver,
        CurrentGradleInstallation currentGradleInstallation,
        File gradleUserHomeDir,
        String gradleVersion) {
        this.instantiator = instantiator;
        this.classPathRegistry = classPathRegistry;
        this.fileResolver = fileResolver;
        this.currentGradleInstallation = currentGradleInstallation;
        this.gradleUserHomeDir = gradleUserHomeDir;
        this.gradleVersion = gradleVersion;
    }

    @Override
    public void describe(DiagnosticsVisitor visitor) {
        visitor.candidate("ClassPathNotation").example("gradleApi()");
    }

    public void convert(DependencyFactory.ClassPathNotation notation, NotationConvertResult<? super SelfResolvingDependency> result) throws TypeConversionException {
        SelfResolvingDependency dependency = internCache.get(notation);
        if (dependency == null) {
            internCacheWriteLock.lock();
            try {
                dependency = maybeCreateUnderLock(notation);
            } finally {
                internCacheWriteLock.unlock();
            }
        }

        result.converted(dependency);
    }

    private SelfResolvingDependency maybeCreateUnderLock(DependencyFactory.ClassPathNotation notation) {
        SelfResolvingDependency dependency = internCache.get(notation);
        if (dependency == null) {
            Collection<File> classpath = classPathRegistry.getClassPath(notation.name()).getAsFiles();
            boolean runningFromInstallation = currentGradleInstallation.getInstallation() != null;
            FileCollectionInternal files;
            if (runningFromInstallation && notation.equals(FAT_GRADLE_API)) {
                files = gradleApiFileCollection(classpath);
            } else if (runningFromInstallation && notation.equals(FAT_GRADLE_TEST_KIT)) {
                files = gradleTestKitFileCollection(classpath);
            } else {
                files = fileResolver.resolveFiles(classpath);
            }
            dependency = instantiator.newInstance(DefaultSelfResolvingDependency.class, files);
            internCache.put(notation, dependency);
        }
        return dependency;
    }

    private FileCollectionInternal gradleApiFileCollection(Collection<File> apiClasspath) {
        // Don't inline the Groovy jar as the Groovy “tools locator” searches for it by name
        List<File> groovyImpl = classPathRegistry.getClassPath(LOCAL_GROOVY.name()).getAsFiles();
        apiClasspath.removeAll(groovyImpl);

        return (FileCollectionInternal) relocatedDepsJar(apiClasspath, "fatGradleApi()", "api")
            .plus(fileResolver.resolveFiles(
                groovyImpl,
                classPathRegistry.getClassPath("GRADLE_INSTALLATION_BEACON").getAsFiles())
            );
    }

    private FileCollectionInternal gradleTestKitFileCollection(Collection<File> testKitClasspath) {
        List<File> gradleApi = classPathRegistry.getClassPath(GRADLE_API.name()).getAsFiles();
        testKitClasspath.removeAll(gradleApi);

        return (FileCollectionInternal) relocatedDepsJar(testKitClasspath, "fatGradleTestKit()", "test-kit")
            .plus(gradleApiFileCollection(gradleApi));
    }

    private FileCollectionInternal relocatedDepsJar(Collection<File> classpath, String displayName, String name) {
        return new FileCollectionAdapter(new GradleImplDepsRelocatedJar(displayName, classpath, getGeneratedJarPath(name)));
    }

    private File getGeneratedJarPath(String name) {
        return new File(gradleUserHomeDir, "caches/" + gradleVersion + "/generated-gradle-jars/gradle-" + name + "-" + gradleVersion + ".jar");
    }
}
