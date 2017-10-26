/*
 * Copyright 2016-present Facebook, Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License"); you may
 * not use this file except in compliance with the License. You may obtain
 * a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 * WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the
 * License for the specific language governing permissions and limitations
 * under the License.
 */

package com.facebook.buck.distributed;

import com.facebook.buck.command.Builder;
import com.facebook.buck.command.BuilderArgs;
import com.facebook.buck.command.LocalBuilder;
import com.facebook.buck.config.ActionGraphParallelizationMode;
import com.facebook.buck.distributed.build_client.BuildSlaveTimingStatsTracker.SlaveEvents;
import com.facebook.buck.distributed.thrift.BuildJob;
import com.facebook.buck.log.Logger;
import com.facebook.buck.model.BuildTarget;
import com.facebook.buck.parser.BuildTargetParser;
import com.facebook.buck.parser.DefaultParserTargetNodeFactory;
import com.facebook.buck.parser.ParserTargetNodeFactory;
import com.facebook.buck.parser.exceptions.BuildFileParseException;
import com.facebook.buck.rules.ActionGraphAndResolver;
import com.facebook.buck.rules.CachingBuildEngineDelegate;
import com.facebook.buck.rules.Cell;
import com.facebook.buck.rules.CellPathResolver;
import com.facebook.buck.rules.DefaultSourcePathResolver;
import com.facebook.buck.rules.LocalCachingBuildEngineDelegate;
import com.facebook.buck.rules.SourcePathRuleFinder;
import com.facebook.buck.rules.TargetGraph;
import com.facebook.buck.rules.TargetGraphAndBuildTargets;
import com.facebook.buck.rules.TargetNode;
import com.facebook.buck.rules.TargetNodeFactory;
import com.facebook.buck.rules.coercer.ConstructorArgMarshaller;
import com.facebook.buck.rules.coercer.DefaultTypeCoercerFactory;
import com.facebook.buck.rules.coercer.PathTypeCoercer;
import com.facebook.buck.rules.coercer.TypeCoercerFactory;
import com.facebook.buck.step.ExecutionContext;
import com.facebook.buck.step.ExecutorPool;
import com.facebook.buck.util.cache.ProjectFileHashCache;
import com.facebook.buck.util.cache.impl.DefaultFileHashCache;
import com.facebook.buck.util.cache.impl.StackedFileHashCache;
import com.facebook.buck.versions.VersionException;
import com.google.common.base.Preconditions;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import java.io.IOException;
import java.net.ServerSocket;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.stream.Collectors;
import javax.annotation.Nullable;

public class DistBuildSlaveExecutor {

  private static final Logger LOG = Logger.get(DistBuildSlaveExecutor.class);
  private static final String LOCALHOST_ADDRESS = "localhost";
  private static final boolean KEEP_GOING = true;

  private final DistBuildSlaveExecutorArgs args;

  @Nullable private TargetGraph targetGraph;

  @Nullable private ActionGraphAndResolver actionGraphAndResolver;

  @Nullable private CachingBuildEngineDelegate cachingBuildEngineDelegate;

  public DistBuildSlaveExecutor(DistBuildSlaveExecutorArgs args) {
    this.args = args;
  }

  public int buildAndReturnExitCode() throws IOException, InterruptedException {
    BuilderArgs builderArgs = args.createBuilderArgs();
    try (ExecutionContext executionContext = LocalBuilder.createExecutionContext(builderArgs)) {
      Builder localBuilder =
          new LocalBuilder(
              builderArgs,
              executionContext,
              Preconditions.checkNotNull(actionGraphAndResolver),
              Preconditions.checkNotNull(cachingBuildEngineDelegate),
              args.getArtifactCache(),
              args.getExecutorService(),
              KEEP_GOING,
              Optional.empty(),
              Optional.empty(),
              Optional.empty());

      DistBuildModeRunner runner = null;
      switch (args.getDistBuildMode()) {
        case REMOTE_BUILD:
          runner =
              new RemoteBuildModeRunner(
                  localBuilder,
                  args.getState().getRemoteState().getTopLevelTargets(),
                  exitCode ->
                      args.getDistBuildService()
                          .setFinalBuildStatus(
                              args.getStampedeId(),
                              BuildStatusUtil.exitCodeToBuildStatus(exitCode)));
          break;

        case COORDINATOR:
          runner = newCoordinatorMode(getFreePortForCoordinator(), false);
          break;

        case MINION:
          runner =
              newMinionMode(
                  localBuilder,
                  args.getRemoteCoordinatorAddress(),
                  args.getRemoteCoordinatorPort());
          break;

        case COORDINATOR_AND_MINION:
          int localCoordinatorPort = getFreePortForCoordinator();
          runner =
              new CoordinatorAndMinionModeRunner(
                  newCoordinatorMode(localCoordinatorPort, true),
                  newMinionMode(localBuilder, LOCALHOST_ADDRESS, localCoordinatorPort));
          break;

        default:
          LOG.error("Unknown distributed build mode [%s].", args.getDistBuildMode().toString());
          return -1;
      }

      return runner.runAndReturnExitCode();
    }
  }

  private MinionModeRunner newMinionMode(
      Builder localBuilder, String coordinatorAddress, int coordinatorPort) {
    MinionModeRunner.BuildCompletionChecker checker =
        () -> {
          BuildJob job = args.getDistBuildService().getCurrentBuildJobState(args.getStampedeId());
          return BuildStatusUtil.isBuildComplete(job.getStatus());
        };

    return new MinionModeRunner(
        coordinatorAddress,
        coordinatorPort,
        localBuilder,
        args.getStampedeId(),
        args.getBuildSlaveRunId(),
        args.getBuildThreadCount(),
        checker);
  }

  private CoordinatorModeRunner newCoordinatorMode(
      int coordinatorPort, boolean isLocalMinionAlsoRunning) {
    final CellPathResolver cellNames = args.getState().getRootCell().getCellPathResolver();
    List<BuildTarget> targets =
        args.getState()
            .getRemoteState()
            .getTopLevelTargets()
            .stream()
            .map(target -> BuildTargetParser.fullyQualifiedNameToBuildTarget(cellNames, target))
            .collect(Collectors.toList());
    BuildTargetsQueue queue =
        BuildTargetsQueue.newQueue(
            Preconditions.checkNotNull(actionGraphAndResolver).getResolver(), targets);
    Optional<String> minionQueue = args.getDistBuildConfig().getMinionQueue();
    Preconditions.checkArgument(
        minionQueue.isPresent(),
        "Minion queue name is missing to be able to run in Coordinator mode.");
    ThriftCoordinatorServer.EventListener listener =
        new CoordinatorEventListener(
            args.getDistBuildService(),
            args.getStampedeId(),
            minionQueue.get(),
            isLocalMinionAlsoRunning);
    return new CoordinatorModeRunner(coordinatorPort, queue, args.getStampedeId(), listener);
  }

  private TargetGraph createTargetGraph() throws IOException, InterruptedException {
    if (targetGraph != null) {
      return targetGraph;
    }

    DistBuildTargetGraphCodec codec = createGraphCodec();
    ImmutableMap<Integer, Cell> cells = args.getState().getCells();
    TargetGraphAndBuildTargets targetGraphAndBuildTargets =
        Preconditions.checkNotNull(
            codec.createTargetGraph(
                args.getState().getRemoteState().getTargetGraph(),
                key -> Preconditions.checkNotNull(cells.get(key))));

    try {
      if (args.getState().getRemoteRootCellConfig().getBuildVersions()) {
        targetGraph =
            args.getVersionedTargetGraphCache()
                .toVersionedTargetGraph(
                    args.getBuckEventBus(),
                    args.getState().getRemoteRootCellConfig(),
                    new DefaultTypeCoercerFactory(
                        PathTypeCoercer.PathExistenceVerificationMode.DO_NOT_VERIFY),
                    targetGraphAndBuildTargets)
                .getTargetGraph();
      } else {
        targetGraph = targetGraphAndBuildTargets.getTargetGraph();
      }
    } catch (VersionException e) {
      throw new RuntimeException(e);
    }

    return targetGraph;
  }

  // TODO(ruibm): This thing is time consuming and should execute in the background.
  private ActionGraphAndResolver createActionGraphAndResolver()
      throws IOException, InterruptedException {
    if (actionGraphAndResolver != null) {
      return actionGraphAndResolver;
    }

    args.getTimingStatsTracker().startTimer(SlaveEvents.TARGET_GRAPH_DESERIALIZATION_TIME);
    createTargetGraph();
    args.getTimingStatsTracker().stopTimer(SlaveEvents.TARGET_GRAPH_DESERIALIZATION_TIME);

    args.getTimingStatsTracker().startTimer(SlaveEvents.ACTION_GRAPH_CREATION_TIME);
    actionGraphAndResolver =
        args.getActionGraphCache()
            .getActionGraph(
                args.getBuckEventBus(),
                /* checkActionGraphs */ false,
                /* skipActionGraphCache */ false,
                Preconditions.checkNotNull(targetGraph),
                args.getCacheKeySeed(),
                ActionGraphParallelizationMode.DISABLED,
                Optional.empty());
    args.getTimingStatsTracker().stopTimer(SlaveEvents.ACTION_GRAPH_CREATION_TIME);
    return actionGraphAndResolver;
  }

  /** Creates the delegate for the distributed build. */
  public CachingBuildEngineDelegate createBuildEngineDelegate()
      throws IOException, InterruptedException {
    if (cachingBuildEngineDelegate != null) {
      return cachingBuildEngineDelegate;
    }

    args.getTimingStatsTracker().startTimer(SlaveEvents.SOURCE_FILE_PRELOAD_TIME);
    StackedFileHashCaches caches = createStackedFileHashesAndPreload();
    args.getTimingStatsTracker().stopTimer(SlaveEvents.SOURCE_FILE_PRELOAD_TIME);
    createActionGraphAndResolver();

    DistBuildConfig remoteConfig = new DistBuildConfig(args.getState().getRemoteRootCellConfig());
    if (remoteConfig.materializeSourceFilesOnDemand()) {
      SourcePathRuleFinder ruleFinder =
          new SourcePathRuleFinder(
              Preconditions.checkNotNull(actionGraphAndResolver).getResolver());
      cachingBuildEngineDelegate =
          new DistBuildCachingEngineDelegate(
              DefaultSourcePathResolver.from(ruleFinder),
              ruleFinder,
              caches.remoteStateCache,
              caches.materializingCache);
    } else {
      cachingBuildEngineDelegate = new LocalCachingBuildEngineDelegate(caches.remoteStateCache);
    }

    return cachingBuildEngineDelegate;
  }

  private StackedFileHashCache createStackOfDefaultFileHashCache() throws InterruptedException {
    ImmutableList.Builder<ProjectFileHashCache> allCachesBuilder = ImmutableList.builder();
    Cell rootCell = args.getState().getRootCell();

    // 1. Add all cells (including the root cell).
    for (Path cellPath : rootCell.getKnownRoots()) {
      Cell cell = rootCell.getCell(cellPath);
      allCachesBuilder.add(
          DefaultFileHashCache.createDefaultFileHashCache(
              cell.getFilesystem(), rootCell.getBuckConfig().getFileHashCacheMode()));
      allCachesBuilder.add(
          DefaultFileHashCache.createBuckOutFileHashCache(
              cell.getFilesystem(), rootCell.getBuckConfig().getFileHashCacheMode()));
    }

    // 2. Add the Operating System roots.
    allCachesBuilder.addAll(
        DefaultFileHashCache.createOsRootDirectoriesCaches(
            args.getProjectFilesystemFactory(), rootCell.getBuckConfig().getFileHashCacheMode()));

    return new StackedFileHashCache(allCachesBuilder.build());
  }

  private DistBuildTargetGraphCodec createGraphCodec() {
    // Note: This is a hack. Do not confuse this hack with the other hack where we 'pre-load' all
    // files so that file existence checks in TG -> AG transformation pass (which is a bigger bug).
    // We need this hack in addition to the other one, because some source file dependencies get
    // shaved off in the versioned target graph, and so they don't get recorded in the distributed
    // state, and hence they're not pre-loaded. So even when we pre-load the files, we need this
    // hack so that the coercer does not check for existence of these unrecorded files.
    TypeCoercerFactory typeCoercerFactory =
        new DefaultTypeCoercerFactory(PathTypeCoercer.PathExistenceVerificationMode.DO_NOT_VERIFY);
    ParserTargetNodeFactory<TargetNode<?, ?>> parserTargetNodeFactory =
        DefaultParserTargetNodeFactory.createForDistributedBuild(
            new ConstructorArgMarshaller(typeCoercerFactory),
            new TargetNodeFactory(typeCoercerFactory));

    return new DistBuildTargetGraphCodec(
        parserTargetNodeFactory,
        input -> {
          try {
            return args.getParser()
                .getRawTargetNode(
                    args.getBuckEventBus(),
                    args.getRootCell().getCell(input.getBuildTarget()),
                    /* enableProfiling */ false,
                    args.getExecutorService(),
                    input);
          } catch (BuildFileParseException e) {
            throw new RuntimeException(e);
          }
        },
        new HashSet<>(args.getState().getRemoteState().getTopLevelTargets()));
  }

  public static int getFreePortForCoordinator() throws IOException {
    // Passing argument 0 to ServerSocket will allocate a new free random port.
    try (ServerSocket socket = new ServerSocket(0)) {
      return socket.getLocalPort();
    }
  }

  private static class StackedFileHashCaches {

    public final StackedFileHashCache remoteStateCache;
    public final StackedFileHashCache materializingCache;

    private StackedFileHashCaches(
        StackedFileHashCache remoteStateCache, StackedFileHashCache materializingCache) {
      this.remoteStateCache = remoteStateCache;
      this.materializingCache = materializingCache;
    }
  }

  private StackedFileHashCaches createStackedFileHashesAndPreload()
      throws InterruptedException, IOException {
    StackedFileHashCache stackedFileHashCache = createStackOfDefaultFileHashCache();
    // Used for rule key computations.
    StackedFileHashCache remoteStackedFileHashCache =
        stackedFileHashCache.newDecoratedFileHashCache(
            cache -> args.getState().createRemoteFileHashCache(cache));

    // Used for the real build.
    StackedFileHashCache materializingStackedFileHashCache =
        stackedFileHashCache.newDecoratedFileHashCache(
            cache -> {
              try {
                return args.getState()
                    .createMaterializerAndPreload(
                        cache,
                        args.getProvider(),
                        Preconditions.checkNotNull(args.getExecutors().get(ExecutorPool.CPU)));
              } catch (IOException exception) {
                throw new RuntimeException(
                    String.format(
                        "Failed to create the Materializer for file system [%s]",
                        cache.getFilesystem()),
                    exception);
              }
            });

    return new StackedFileHashCaches(remoteStackedFileHashCache, materializingStackedFileHashCache);
  }
}
