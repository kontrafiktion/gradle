/*
 * Copyright 2016 the original author or authors.
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

package org.gradle.tooling.internal.provider.runner;

import org.gradle.StartParameter;
import org.gradle.api.logging.LogLevel;
import org.gradle.initialization.*;
import org.gradle.internal.classpath.ClassPath;
import org.gradle.internal.invocation.BuildActionRunner;
import org.gradle.internal.service.ServiceRegistry;
import org.gradle.internal.service.scopes.BuildSessionScopeServices;
import org.gradle.launcher.daemon.configuration.DaemonUsage;
import org.gradle.launcher.exec.BuildActionParameters;
import org.gradle.launcher.exec.DefaultBuildActionParameters;
import org.gradle.launcher.exec.InProcessBuildActionExecuter;
import org.gradle.tooling.*;
import org.gradle.tooling.composite.BuildIdentity;
import org.gradle.tooling.composite.ProjectIdentity;
import org.gradle.tooling.events.OperationType;
import org.gradle.tooling.internal.adapter.ProtocolToModelAdapter;
import org.gradle.tooling.internal.composite.GradleBuildInternal;
import org.gradle.tooling.internal.composite.GradleConnectionParticipant;
import org.gradle.tooling.internal.provider.BuildClientSubscriptions;
import org.gradle.tooling.internal.provider.BuildModelAction;

import java.io.File;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.Collections;
import java.util.Set;

class InProcessGradleConnectionParticipant implements GradleConnectionParticipant {
    private final ServiceRegistry serviceRegistry;
    private final GradleBuildInternal build;
    private final File gradleUserHome;
    private final File projectDirectory;

    public InProcessGradleConnectionParticipant(GradleBuildInternal build, File gradleUserHome, ServiceRegistry serviceRegistry) {
        this(build, build.getProjectDir(), gradleUserHome, serviceRegistry);
    }

    private InProcessGradleConnectionParticipant(GradleBuildInternal build, File projectDirectory, File gradleUserHome, ServiceRegistry serviceRegistry) {
        this.build = build;
        this.projectDirectory = projectDirectory;
        this.gradleUserHome = gradleUserHome;
        this.serviceRegistry = serviceRegistry;
    }

    @Override
    public GradleConnectionParticipant withProjectDirectory(File projectDirectory) {
        return new InProcessGradleConnectionParticipant(build, projectDirectory, gradleUserHome, serviceRegistry);
    }

    @Override
    public ProjectIdentity toProjectIdentity(String projectPath) {
        return build.toProjectIdentity(projectPath);
    }

    @Override
    public BuildIdentity toBuildIdentity() {
        return build.toBuildIdentity();
    }

    @Override
    public ProjectConnection connect() {
        return new InProcessProjectConnection();
    }

    private <T> T fetchModelInProcess(Class<T> modelType, BuildCancellationToken cancellationToken, ServiceRegistry sharedServices) {
        GradleLauncherFactory gradleLauncherFactory = sharedServices.get(GradleLauncherFactory.class);

        BuildActionRunner runner = new NonSerializingBuildModelActionRunner();
        org.gradle.launcher.exec.BuildActionExecuter<BuildActionParameters> buildActionExecuter = new InProcessBuildActionExecuter(gradleLauncherFactory, runner);
        DefaultBuildRequestContext requestContext = new DefaultBuildRequestContext(new DefaultBuildRequestMetaData(System.currentTimeMillis()), cancellationToken, new NoOpBuildEventConsumer());

        ProtocolToModelAdapter protocolToModelAdapter = new ProtocolToModelAdapter();

        DefaultBuildActionParameters actionParameters = new DefaultBuildActionParameters(Collections.EMPTY_MAP, Collections.<String, String>emptyMap(), projectDirectory, LogLevel.INFO, DaemonUsage.EXPLICITLY_DISABLED, false, true, ClassPath.EMPTY);

        StartParameter startParameter = new StartParameter();
        startParameter.setProjectDir(projectDirectory);

        ServiceRegistry buildScopedServices = new BuildSessionScopeServices(sharedServices, startParameter, ClassPath.EMPTY);

        BuildModelAction modelAction = new BuildModelAction(startParameter, modelType.getName(), false, new BuildClientSubscriptions(false, false, false));

        Object result = buildActionExecuter.execute(modelAction, requestContext, actionParameters, buildScopedServices);
        return protocolToModelAdapter.adapt(modelType, result);
    }

    private class InProcessProjectConnection implements ProjectConnection {
        @Override
        public <T> T getModel(Class<T> modelType) {
            return model(modelType).get();
        }

        @Override
        public <T> void getModel(final Class<T> modelType, final ResultHandler<? super T> handler) {
            model(modelType).get(handler);
        }

        @Override
        public <T> ModelBuilder<T> model(Class<T> modelType) {
            return new InProcessConnectionModelBuilder<T>(modelType);
        }

        @Override
        public <T> BuildActionExecuter<T> action(BuildAction<T> buildAction) {
            throw new UnsupportedOperationException();
        }

        @Override
        public BuildLauncher newBuild() {
            throw new UnsupportedOperationException();
        }

        @Override
        public TestLauncher newTestLauncher() {
            throw new UnsupportedOperationException();
        }

        @Override
        public void close() {

        }
    }

    private class InProcessConnectionModelBuilder<T> implements ModelBuilder<T> {
        private final Class<T> modelType;

        private InProcessConnectionModelBuilder(Class<T> modelType) {
            this.modelType = modelType;
        }

        @Override
        public T get() throws GradleConnectionException, IllegalStateException {
            return fetchModelInProcess(modelType, new DefaultBuildCancellationToken(), serviceRegistry);
        }

        @Override
        public void get(ResultHandler<? super T> handler) throws IllegalStateException {
            T result = get();
            handler.onComplete(result);
        }

        @Override
        public ModelBuilder<T> forTasks(Iterable<String> tasks) {
            throw new UnsupportedOperationException();
        }

        @Override
        public ModelBuilder<T> forTasks(String... tasks) {
            throw new UnsupportedOperationException();
        }

        @Override
        public ModelBuilder<T> withArguments(String... arguments) {
            throw new UnsupportedOperationException();
        }

        @Override
        public ModelBuilder<T> withArguments(Iterable<String> arguments) {
            throw new UnsupportedOperationException();
        }

        @Override
        public ModelBuilder<T> setStandardOutput(OutputStream outputStream) {
            throw new UnsupportedOperationException();
        }

        @Override
        public ModelBuilder<T> setStandardError(OutputStream outputStream) {
            throw new UnsupportedOperationException();
        }

        @Override
        public ModelBuilder<T> setColorOutput(boolean colorOutput) {
            throw new UnsupportedOperationException();
        }

        @Override
        public ModelBuilder<T> setStandardInput(InputStream inputStream) {
            throw new UnsupportedOperationException();
        }

        @Override
        public ModelBuilder<T> setJavaHome(File javaHome) {
            throw new UnsupportedOperationException();
        }

        @Override
        public ModelBuilder<T> setJvmArguments(String... jvmArguments) {
            throw new UnsupportedOperationException();
        }

        @Override
        public ModelBuilder<T> setJvmArguments(Iterable<String> jvmArguments) {
            throw new UnsupportedOperationException();
        }

        @Override
        public ModelBuilder<T> addProgressListener(ProgressListener listener) {
            throw new UnsupportedOperationException();
        }

        @Override
        public ModelBuilder<T> addProgressListener(org.gradle.tooling.events.ProgressListener listener) {
            throw new UnsupportedOperationException();
        }

        @Override
        public ModelBuilder<T> addProgressListener(org.gradle.tooling.events.ProgressListener listener, Set<OperationType> eventTypes) {
            throw new UnsupportedOperationException();
        }

        @Override
        public ModelBuilder<T> addProgressListener(org.gradle.tooling.events.ProgressListener listener, OperationType... operationTypes) {
            throw new UnsupportedOperationException();
        }

        @Override
        public ModelBuilder<T> withCancellationToken(CancellationToken cancellationToken) {
            throw new UnsupportedOperationException();
        }
    }
}
