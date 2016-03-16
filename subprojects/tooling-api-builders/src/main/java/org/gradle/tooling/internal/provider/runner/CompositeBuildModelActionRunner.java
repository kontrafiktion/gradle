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
import org.gradle.TaskExecutionRequest;
import org.gradle.api.internal.artifacts.ivyservice.projectmodule.CompositeBuildContext;
import org.gradle.api.internal.artifacts.ivyservice.projectmodule.CompositeContextBuilder;
import org.gradle.api.logging.LogLevel;
import org.gradle.initialization.*;
import org.gradle.internal.Cast;
import org.gradle.internal.UncheckedException;
import org.gradle.internal.classpath.ClassPath;
import org.gradle.internal.composite.*;
import org.gradle.internal.invocation.BuildAction;
import org.gradle.internal.invocation.BuildActionRunner;
import org.gradle.internal.service.ServiceRegistry;
import org.gradle.internal.service.scopes.BuildSessionScopeServices;
import org.gradle.launcher.daemon.configuration.DaemonUsage;
import org.gradle.launcher.exec.BuildActionParameters;
import org.gradle.launcher.exec.DefaultBuildActionParameters;
import org.gradle.launcher.exec.InProcessBuildActionExecuter;
import org.gradle.logging.ProgressLoggerFactory;
import org.gradle.tooling.*;
import org.gradle.tooling.internal.adapter.ProtocolToModelAdapter;
import org.gradle.tooling.internal.consumer.CancellationTokenInternal;
import org.gradle.tooling.internal.consumer.DefaultGradleConnector;
import org.gradle.tooling.internal.consumer.connection.InternalBuildActionAdapter;
import org.gradle.tooling.internal.protocol.CompositeBuildExceptionVersion1;
import org.gradle.tooling.internal.protocol.DefaultBuildIdentity;
import org.gradle.tooling.internal.protocol.DefaultProjectIdentity;
import org.gradle.tooling.internal.protocol.InternalBuildAction;
import org.gradle.tooling.internal.provider.*;
import org.gradle.tooling.model.gradle.BasicGradleProject;

import java.io.File;
import java.io.Serializable;
import java.util.*;
import java.util.concurrent.TimeUnit;

public class CompositeBuildModelActionRunner implements CompositeBuildActionRunner {
    public void run(BuildAction action, BuildRequestContext requestContext, CompositeBuildActionParameters actionParameters, CompositeBuildController buildController) {
        if (!(action instanceof BuildModelAction)) {
            return;
        }
        Class<?> modelType = resolveModelType((BuildModelAction) action);
        ProgressLoggerFactory progressLoggerFactory = buildController.getBuildScopeServices().get(ProgressLoggerFactory.class);
        Map<Object, Object> results = null;
        if (modelType != Void.class) {
            results = new HashMap<Object, Object>();
            results.putAll(fetchCompositeModelsInProcess((BuildModelAction) action, modelType, actionParameters.getCompositeParameters().getBuilds(), requestContext.getCancellationToken(), buildController.getBuildScopeServices()));
        } else {
            if (!((BuildModelAction) action).isRunTasks()) {
                throw new IllegalStateException("No tasks defined.");
            }
            executeTasks(action.getStartParameter(), actionParameters, requestContext.getCancellationToken(), progressLoggerFactory);
        }
        PayloadSerializer payloadSerializer = buildController.getBuildScopeServices().get(PayloadSerializer.class);
        buildController.setResult(new BuildActionResult(payloadSerializer.serialize(results), null));
    }

    private void executeTasks(StartParameter startParameter, CompositeBuildActionParameters actionParameters, BuildCancellationToken cancellationToken, ProgressLoggerFactory progressLoggerFactory) {
        CompositeParameters compositeParameters = actionParameters.getCompositeParameters();
        boolean buildFound = false;
        for (GradleParticipantBuild participant : compositeParameters.getBuilds()) {
            if (!participant.getProjectDir().getAbsolutePath().equals(compositeParameters.getCompositeTargetBuildRootDir().getAbsolutePath())) {
                continue;
            }
            buildFound = true;
            if (cancellationToken.isCancellationRequested()) {
                break;
            }
            ProjectConnection projectConnection = connect(participant, compositeParameters);
            try {
                BuildLauncher buildLauncher = projectConnection.newBuild();
                buildLauncher.withCancellationToken(new CancellationTokenAdapter(cancellationToken));
                buildLauncher.addProgressListener(new ProgressListenerToProgressLoggerAdapter(progressLoggerFactory));
                List<String> taskArgs = new ArrayList<String>();
                for (TaskExecutionRequest request : startParameter.getTaskRequests()) {
                    if (request.getProjectPath() == null) {
                        taskArgs.addAll(request.getArgs());
                    } else {
                        String projectPath = request.getProjectPath();
                        int index = 0;
                        for (String arg : request.getArgs()) {
                            if (index == 0) {
                                // add project path to first arg
                                taskArgs.add(projectPath + ":" + arg);
                            } else {
                                taskArgs.add(arg);
                            }
                            index++;
                        }
                    }
                }
                buildLauncher.forTasks(taskArgs.toArray(new String[0]));
                buildLauncher.run();
            } catch (GradleConnectionException e) {
                throw new CompositeBuildExceptionVersion1(e, new DefaultBuildIdentity(compositeParameters.getCompositeTargetBuildRootDir()));
            } finally {
                projectConnection.close();
            }
        }
        if (!buildFound) {
            throw new IllegalStateException("Build not part of composite");
        }
    }

    private Class<?> resolveModelType(BuildModelAction action) {
        final String requestedModelName = action.getModelName();
        try {
            return Cast.uncheckedCast(getClass().getClassLoader().loadClass(requestedModelName));
        } catch (ClassNotFoundException e) {
            throw UncheckedException.throwAsUncheckedException(e);
        }
    }

    private Map<Object, Object> aggregateModels(BuildModelAction action, CompositeBuildController buildController, Class<?> modelType, CompositeBuildActionParameters actionParameters, BuildCancellationToken cancellationToken, ProgressLoggerFactory progressLoggerFactory) {
        final Map<Object, Object> results = new HashMap<Object, Object>();
        final CompositeParameters compositeParameters = actionParameters.getCompositeParameters();
        results.putAll(fetchCompositeModelsInProcess(action, modelType, compositeParameters.getBuilds(), cancellationToken, buildController.getBuildScopeServices()));
        return results;
    }

    private DefaultProjectIdentity convertToProjectIdentity(InternalProjectIdentity internalProjectIdentity) {
        return new DefaultProjectIdentity(new DefaultBuildIdentity(internalProjectIdentity.rootDir), internalProjectIdentity.rootDir, internalProjectIdentity.projectPath);
    }

    private <T> Map<Object, Object> fetchCompositeModelsInProcess(BuildModelAction modelAction, Class<T> modelType, List<GradleParticipantBuild> participantBuilds,
                                                                  BuildCancellationToken cancellationToken, ServiceRegistry sharedServices) {
        final Map<Object, Object> results = new HashMap<Object, Object>();

        PayloadSerializer payloadSerializer = sharedServices.get(PayloadSerializer.class);
        GradleLauncherFactory gradleLauncherFactory = sharedServices.get(GradleLauncherFactory.class);
//        CompositeBuildContext context = constructCompositeContext(gradleLauncherFactory, participantBuilds);

//        DefaultServiceRegistry compositeServices = (DefaultServiceRegistry) ServiceRegistryBuilder.builder()
//            .displayName("Composite services")
//            .parent(sharedServices)
//            .build();
//        compositeServices.add(CompositeBuildContext.class, context);
//        compositeServices.addProvider(new CompositeScopeServices(modelAction.getStartParameter(), compositeServices));

        BuildActionRunner runner = new ClientProvidedBuildActionRunner();
        org.gradle.launcher.exec.BuildActionExecuter<BuildActionParameters> buildActionExecuter = new InProcessBuildActionExecuter(gradleLauncherFactory, runner);
        DefaultBuildRequestContext requestContext = new DefaultBuildRequestContext(new DefaultBuildRequestMetaData(System.currentTimeMillis()), cancellationToken, new NoOpBuildEventConsumer());

        ProtocolToModelAdapter protocolToModelAdapter = new ProtocolToModelAdapter();

        FetchPerProjectModelAction fetchPerProjectModelAction = new FetchPerProjectModelAction(modelType.getName());
        InternalBuildAction<?> internalBuildAction = new InternalBuildActionAdapter<Map<Object, Object>>(fetchPerProjectModelAction, protocolToModelAdapter);
        SerializedPayload serializedAction = payloadSerializer.serialize(internalBuildAction);

        for (GradleParticipantBuild participant : participantBuilds) {
            DefaultBuildActionParameters actionParameters = new DefaultBuildActionParameters(Collections.EMPTY_MAP, Collections.<String, String>emptyMap(), participant.getProjectDir(), LogLevel.INFO, DaemonUsage.EXPLICITLY_DISABLED, false, true, ClassPath.EMPTY);

            StartParameter startParameter = modelAction.getStartParameter().newInstance();
            startParameter.setProjectDir(participant.getProjectDir());

            ServiceRegistry buildScopedServices = new BuildSessionScopeServices(sharedServices, startParameter, ClassPath.EMPTY);

            ClientProvidedBuildAction mappedAction = new ClientProvidedBuildAction(startParameter, serializedAction, modelAction.getClientSubscriptions());

            try {
                BuildActionResult result = (BuildActionResult) buildActionExecuter.execute(mappedAction, requestContext, actionParameters, buildScopedServices);
                if (result.result != null) {
                    Map<Object, Object> values = (Map<Object, Object>) payloadSerializer.deserialize(result.result);
                    for (Map.Entry<Object, Object> e : values.entrySet()) {
                        InternalProjectIdentity internalProjectIdentity = (InternalProjectIdentity) e.getKey();
                        results.put(convertToProjectIdentity(internalProjectIdentity), e.getValue());
                    }
                } else {
                    Throwable failure = (Throwable) payloadSerializer.deserialize(result.failure);
                    File rootDir = participant.getProjectDir();
                    DefaultBuildIdentity buildIdentity = new DefaultBuildIdentity(rootDir);
                    results.put(new DefaultProjectIdentity(buildIdentity, rootDir, ":"), failure);
                }
            } catch (Exception e) {
                File rootDir = participant.getProjectDir();
                DefaultBuildIdentity buildIdentity = new DefaultBuildIdentity(rootDir);
                results.put(new DefaultProjectIdentity(buildIdentity, rootDir, ":"), e);
            }

        }
        return results;
    }

    private CompositeBuildContext constructCompositeContext(GradleLauncherFactory gradleLauncherFactory, List<GradleParticipantBuild> participantBuilds) {
        CompositeContextBuilder builder = new CompositeContextBuilder(gradleLauncherFactory);
        for (GradleParticipantBuild participant : participantBuilds) {
            final String participantName = participant.getProjectDir().getName();
            builder.addParticipant(participantName, participant.getProjectDir());
        }
        return builder.build();
    }

    private static final class FetchPerProjectModelAction implements org.gradle.tooling.BuildAction<Map<Object, Object>> {
        private final String modelTypeName;
        private FetchPerProjectModelAction(String modelTypeName) {
            this.modelTypeName = modelTypeName;
        }

        @Override
        public Map<Object, Object> execute(BuildController controller) {
            Class<?> modelType;
            try {
                modelType = Class.forName(modelTypeName);
            } catch (ClassNotFoundException e) {
                throw UncheckedException.throwAsUncheckedException(e);
            }
            final Map<Object, Object> results = new HashMap<Object, Object>();
            fetchResults(modelType, results, controller, controller.getBuildModel().getRootProject(), controller.getBuildModel().getRootProject());
            return results;
        }

        private void fetchResults(Class<?> modelType, Map<Object, Object> results, BuildController controller, BasicGradleProject project, BasicGradleProject rootProject) {
            File rootDir = rootProject.getProjectDirectory();
            results.put(new InternalProjectIdentity(rootDir, project.getPath()), controller.getModel(project, modelType));
            for (BasicGradleProject child : project.getChildren()) {
                fetchResults(modelType, results, controller, child, rootProject);
            }
        }
    }

    private static final class InternalProjectIdentity implements Serializable {
        private final File rootDir;
        private final String projectPath;

        private InternalProjectIdentity(File rootDir, String projectPath) {
            this.rootDir = rootDir;
            this.projectPath = projectPath;
        }
    }

    private ProjectConnection connect(GradleParticipantBuild build, CompositeParameters compositeParameters) {
        DefaultGradleConnector connector = getInternalConnector();
        File gradleUserHomeDir = compositeParameters.getGradleUserHomeDir();
        File daemonBaseDir = compositeParameters.getDaemonBaseDir();
        Integer daemonMaxIdleTimeValue = compositeParameters.getDaemonMaxIdleTimeValue();
        TimeUnit daemonMaxIdleTimeUnits = compositeParameters.getDaemonMaxIdleTimeUnits();
        Boolean embeddedParticipants = compositeParameters.isEmbeddedParticipants();

        if (gradleUserHomeDir != null) {
            connector.useGradleUserHomeDir(gradleUserHomeDir);
        }
        if (daemonBaseDir != null) {
            connector.daemonBaseDir(daemonBaseDir);
        }
        if (daemonMaxIdleTimeValue != null && daemonMaxIdleTimeUnits != null) {
            connector.daemonMaxIdleTime(daemonMaxIdleTimeValue, daemonMaxIdleTimeUnits);
        }
        connector.searchUpwards(false);
        connector.forProjectDirectory(build.getProjectDir());

        if (embeddedParticipants) {
            connector.embedded(true);
            connector.useClasspathDistribution();
            return connector.connect();
        } else {
            return configureDistribution(connector, build).connect();
        }
    }

    private DefaultGradleConnector getInternalConnector() {
        return (DefaultGradleConnector) GradleConnector.newConnector();
    }

    private GradleConnector configureDistribution(GradleConnector connector, GradleParticipantBuild build) {
        if (build.getGradleDistribution() == null) {
            if (build.getGradleHome() == null) {
                if (build.getGradleVersion() == null) {
                    connector.useBuildDistribution();
                } else {
                    connector.useGradleVersion(build.getGradleVersion());
                }
            } else {
                connector.useInstallation(build.getGradleHome());
            }
        } else {
            connector.useDistribution(build.getGradleDistribution());
        }

        return connector;
    }

    private final static class CancellationTokenAdapter implements CancellationToken, CancellationTokenInternal {
        private final BuildCancellationToken token;

        private CancellationTokenAdapter(BuildCancellationToken token) {
            this.token = token;
        }

        public boolean isCancellationRequested() {
            return token.isCancellationRequested();
        }

        public BuildCancellationToken getToken() {
            return token;
        }
    }

}
