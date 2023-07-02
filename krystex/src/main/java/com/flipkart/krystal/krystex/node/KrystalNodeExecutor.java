package com.flipkart.krystal.krystex.node;

import static com.flipkart.krystal.krystex.node.KrystalNodeExecutor.CommandOrderStrategy.BREADTH;
import static com.flipkart.krystal.krystex.node.KrystalNodeExecutor.NodeExecStrategy.BATCH;
import static com.flipkart.krystal.krystex.node.KrystalNodeExecutor.NodeExecStrategy.GRANULAR;
import static com.flipkart.krystal.utils.Futures.linkFutures;
import static com.google.common.base.Preconditions.checkArgument;
import static com.google.common.collect.ImmutableSet.toImmutableSet;
import static com.google.common.collect.Sets.union;
import static java.util.concurrent.CompletableFuture.allOf;
import static java.util.concurrent.CompletableFuture.completedFuture;
import static java.util.concurrent.CompletableFuture.failedFuture;
import static java.util.concurrent.CompletableFuture.supplyAsync;
import static java.util.function.Function.identity;

import com.flipkart.krystal.data.Inputs;
import com.flipkart.krystal.data.ValueOrError;
import com.flipkart.krystal.krystex.KrystalExecutor;
import com.flipkart.krystal.krystex.MainLogicDefinition;
import com.flipkart.krystal.krystex.commands.BatchCommand;
import com.flipkart.krystal.krystex.commands.DependencyCallbackBatch;
import com.flipkart.krystal.krystex.commands.ExecuteWithInputs;
import com.flipkart.krystal.krystex.commands.Flush;
import com.flipkart.krystal.krystex.commands.NodeCommand;
import com.flipkart.krystal.krystex.commands.NodeInputBatch;
import com.flipkart.krystal.krystex.commands.NodeInputCommand;
import com.flipkart.krystal.krystex.commands.NodeRequestCommand;
import com.flipkart.krystal.krystex.commands.SkipNode;
import com.flipkart.krystal.krystex.decoration.InitiateActiveDepChains;
import com.flipkart.krystal.krystex.decoration.LogicDecorationOrdering;
import com.flipkart.krystal.krystex.decoration.LogicExecutionContext;
import com.flipkart.krystal.krystex.decoration.MainLogicDecorator;
import com.flipkart.krystal.krystex.decoration.MainLogicDecoratorConfig;
import com.flipkart.krystal.krystex.decoration.MainLogicDecoratorConfig.DecoratorContext;
import com.flipkart.krystal.krystex.request.RequestId;
import com.flipkart.krystal.utils.MultiLeasePool;
import com.flipkart.krystal.utils.MultiLeasePool.Lease;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.RejectedExecutionException;
import java.util.function.Supplier;
import java.util.stream.Stream;
import lombok.extern.slf4j.Slf4j;

/** Default implementation of Krystal executor which */
@Slf4j
public final class KrystalNodeExecutor implements KrystalExecutor {

  public enum NodeExecStrategy {
    GRANULAR,
    BATCH;
  }

  public enum CommandOrderStrategy {
    BREADTH,
    DEPTH;
  }

  private final NodeDefinitionRegistry nodeDefinitionRegistry;
  private final LogicDecorationOrdering logicDecorationOrdering;
  private final Lease<? extends ExecutorService> commandQueueLease;
  private final String instanceId;

  /**
   * We need to have a list of request scope global decorators corresponding to each type, in case
   * we want to have a decorator of one type but based on some config in request, we want to choose
   * one. Ex : Logger, based on prod or preprod env if we want to choose different types of loggers
   * Error logger or info logger
   */
  private final ImmutableMap<
          String, // DecoratorType
          List<MainLogicDecoratorConfig>>
      requestScopedLogicDecoratorConfigs;

  private final ImmutableSet<DependantChain> disabledDependantChains;

  private final Map<
          String, // DecoratorType
          Map<
              String, // InstanceId
              MainLogicDecorator>>
      requestScopedMainDecorators = new LinkedHashMap<>();
  private final NodeRegistry nodeRegistry = new NodeRegistry();
  private final KrystalNodeExecutorMetrics krystalNodeMetrics;
  private final NodeExecStrategy nodeExecStrategy;
  private final CommandOrderStrategy commandOrderStrategy;
  private volatile boolean closed;
  private final Map<RequestId, NodeResult> allRequests = new LinkedHashMap<>();
  private final Set<RequestId> unFlushedRequests = new LinkedHashSet<>();

  private final Map<NodeId, Set<DependantChain>> dependantChainsPerNode = new LinkedHashMap<>();

  public KrystalNodeExecutor(
      NodeDefinitionRegistry nodeDefinitionRegistry,
      MultiLeasePool<? extends ExecutorService> commandQueuePool,
      KrystalNodeExecutorConfig config,
      String instanceId) {
    this(
        nodeDefinitionRegistry,
        commandQueuePool,
        config.logicDecorationOrdering(),
        config.requestScopedLogicDecoratorConfigs(),
        config.disabledDependantChains(),
        instanceId,
        NodeExecStrategy.BATCH);
  }

  public KrystalNodeExecutor(
      NodeDefinitionRegistry nodeDefinitionRegistry,
      MultiLeasePool<? extends ExecutorService> commandQueuePool,
      LogicDecorationOrdering logicDecorationOrdering,
      Map<String, List<MainLogicDecoratorConfig>> requestScopedLogicDecoratorConfigs,
      ImmutableSet<DependantChain> disabledDependantChains,
      String instanceId,
      NodeExecStrategy nodeExecStrategy) {
    this.nodeExecStrategy = GRANULAR;
    this.commandOrderStrategy = BREADTH;
    this.nodeDefinitionRegistry = nodeDefinitionRegistry;
    this.logicDecorationOrdering = logicDecorationOrdering;
    this.commandQueueLease = commandQueuePool.lease();
    this.instanceId = instanceId;
    this.requestScopedLogicDecoratorConfigs =
        ImmutableMap.copyOf(requestScopedLogicDecoratorConfigs);
    this.disabledDependantChains = disabledDependantChains;
    this.krystalNodeMetrics = new KrystalNodeExecutorMetrics();
  }

  private ImmutableMap<String, MainLogicDecorator> getRequestScopedDecorators(
      LogicExecutionContext logicExecutionContext) {
    NodeId nodeId = logicExecutionContext.nodeId();
    NodeDefinition nodeDefinition = nodeDefinitionRegistry.get(nodeId);
    MainLogicDefinition<?> mainLogicDefinition = nodeDefinition.getMainLogicDefinition();
    Map<String, MainLogicDecorator> decorators = new LinkedHashMap<>();
    Stream.concat(
            mainLogicDefinition.getRequestScopedLogicDecoratorConfigs().entrySet().stream(),
            requestScopedLogicDecoratorConfigs.entrySet().stream())
        .forEach(
            entry -> {
              String decoratorType = entry.getKey();
              List<MainLogicDecoratorConfig> decoratorConfigList =
                  new ArrayList<>(entry.getValue());
              decoratorConfigList.forEach(
                  decoratorConfig -> {
                    String instanceId =
                        decoratorConfig.instanceIdGenerator().apply(logicExecutionContext);
                    if (decoratorConfig.shouldDecorate().test(logicExecutionContext)) {
                      MainLogicDecorator mainLogicDecorator =
                          requestScopedMainDecorators
                              .computeIfAbsent(decoratorType, t -> new LinkedHashMap<>())
                              .computeIfAbsent(
                                  instanceId,
                                  _i ->
                                      decoratorConfig
                                          .factory()
                                          .apply(
                                              new DecoratorContext(
                                                  instanceId, logicExecutionContext)));
                      mainLogicDecorator.executeCommand(
                          new InitiateActiveDepChains(
                              nodeId, ImmutableSet.copyOf(dependantChainsPerNode.get(nodeId))));
                      decorators.putIfAbsent(decoratorType, mainLogicDecorator);
                    }
                  });
            });
    return ImmutableMap.copyOf(decorators);
  }

  @Override
  public <T> CompletableFuture<T> executeNode(
      NodeId nodeId, Inputs inputs, NodeExecutionConfig executionConfig) {
    if (closed) {
      throw new RejectedExecutionException("KrystalNodeExecutor is already closed");
    }

    checkArgument(executionConfig != null, "executionConfig can not be null");

    String executionId = executionConfig.executionId();
    checkArgument(executionId != null, "executionConfig.executionId can not be null");
    RequestId requestId = new RequestId("%s:%s".formatted(instanceId, executionId));

    //noinspection unchecked
    return (CompletableFuture<T>)
        enqueueCommand( // Perform all datastructure manipulations in the command queue to avoid
                // multi-thread access
                () -> {
                  createDependencyNodes(nodeId, DependantChainStart.instance(), executionConfig);
                  CompletableFuture<Object> future = new CompletableFuture<>();
                  if (allRequests.containsKey(requestId)) {
                    future.completeExceptionally(
                        new IllegalArgumentException(
                            "Received duplicate requests for same instanceId '%s' and execution Id '%s'"
                                .formatted(instanceId, executionId)));
                  } else {
                    allRequests.put(
                        requestId, new NodeResult(nodeId, inputs, executionConfig, future));
                    unFlushedRequests.add(requestId);
                  }
                  return future;
                })
            .thenCompose(identity());
  }

  private void createDependencyNodes(
      NodeId nodeId, DependantChain dependantChain, NodeExecutionConfig executionConfig) {
    NodeDefinition nodeDefinition = nodeDefinitionRegistry.get(nodeId);
    // If a dependantChain is disabled, don't create that node and its dependency nodes
    if (!union(this.disabledDependantChains, executionConfig.disabledDependantChains())
        .contains(dependantChain)) {
      nodeRegistry.createIfAbsent(
          nodeId,
          _n ->
              new Node(
                  nodeDefinition,
                  this,
                  this::getRequestScopedDecorators,
                  logicDecorationOrdering,
                  krystalNodeMetrics));
      ImmutableMap<String, NodeId> dependencyNodes = nodeDefinition.dependencyNodes();
      dependencyNodes.forEach(
          (dependencyName, depNodeId) ->
              createDependencyNodes(
                  depNodeId, dependantChain.extend(nodeId, dependencyName), executionConfig));
      dependantChainsPerNode
          .computeIfAbsent(nodeId, _n -> new LinkedHashSet<>())
          .add(dependantChain);
    }
  }

  /**
   * Enqueues the provided NodeRequestCommand supplier into the command queue. This method is
   * intended to be called in threads other than the main thread of this KrystalNodeExecutor.(for
   * example IO reactor threads). When a non-blocking IO call is made by a Node, a callback is added
   * to the resulting CompletableFuture which generates an ExecuteWithDependency command for its
   * dependents. That is when this method is used - ensuring that all further processing of the
   * nodeCammand happens in the main thread.
   */
  void enqueueNodeCommand(Supplier<NodeRequestCommand> nodeCommand) {
    enqueueCommand(() -> _executeCommand(nodeCommand.get())).thenCompose(identity());
  }

  void enqueueNodeBatchCommand(Supplier<BatchCommand<?>> nodeCommand) {
    if (BREADTH.equals(commandOrderStrategy)) {
      enqueueCommand(() -> _executeBatchCommand(nodeCommand.get())).thenCompose(identity());
    } else {
      executeBatchCommand(nodeCommand.get());
    }
  }

  /**
   * This method can be called only from the main thread of this KrystalNodeExecutor. Calling this
   * method from any other thread (for example: IO reactor threads) will cause race conditions,
   * multithreaded access of non-thread-safe data structures, and resulting unspecified behaviour.
   *
   * <p>This is an optimal version on {@link #enqueueNodeCommand(Supplier)} which bypasses the
   * command queue for the special case that the command is originating from the same main thread
   * inside the command queue,thus avoiding unnecessary contention in the thread-safe structures
   * inside the command queue.
   */
  CompletableFuture<NodeResponse> executeCommand(NodeCommand nodeCommand) {
    if (BREADTH.equals(commandOrderStrategy)) {
      return enqueueCommand(() -> _executeCommand(nodeCommand)).thenCompose(identity());
    } else {
      krystalNodeMetrics.commandQueueBypassedCount++;
      return _executeCommand(nodeCommand);
    }
  }

  CompletableFuture<NodeBatchResponse> executeBatchCommand(BatchCommand<?> nodeCommand) {
    if (BREADTH.equals(commandOrderStrategy)) {
      return enqueueCommand(() -> _executeBatchCommand(nodeCommand)).thenCompose(identity());
    } else {
      krystalNodeMetrics.commandQueueBypassedCount++;
      return executeBatchCommand(nodeCommand);
    }
  }

  CompletableFuture<NodeBatchResponse> _executeBatchCommand(BatchCommand<?> batchCommand) {
    try {
      validate(batchCommand);
    } catch (Throwable e) {
      return failedFuture(e);
    }
    if (batchCommand instanceof NodeInputBatch nodeInputBatch) {
      krystalNodeMetrics.nodeInputsBatchCount++;
      return nodeRegistry.get(batchCommand.nodeId()).executeBatchCommand(nodeInputBatch);
    } else if (batchCommand instanceof DependencyCallbackBatch callbackBatch) {
      krystalNodeMetrics.depCallbackBatchCount++;
      return nodeRegistry.get(batchCommand.nodeId()).executeBatchCommand(callbackBatch);
    } else {
      throw new UnsupportedOperationException("Unknow nodeBatch type %s".formatted(batchCommand));
    }
  }

  private CompletableFuture<NodeResponse> _executeCommand(NodeCommand nodeCommand) {
    try {
      validate(nodeCommand);
    } catch (Throwable e) {
      return failedFuture(e);
    }
    if (nodeCommand instanceof NodeRequestCommand nodeRequestCommand) {
      return nodeRegistry.get(nodeCommand.nodeId()).executeRequestCommand(nodeRequestCommand);
    } else if (nodeCommand instanceof Flush flush) {
      nodeRegistry.get(flush.nodeId()).executeCommand(flush);
      return completedFuture(null);
    } else {
      throw new UnsupportedOperationException(
          "Unknown NodeCommand type %s".formatted(nodeCommand.getClass()));
    }
  }

  private void validate(NodeCommand nodeCommand) {
    if (nodeCommand instanceof NodeInputBatch batchCommand) {
      batchCommand.subCommands().values().forEach(this::validate);
    } else if (nodeCommand instanceof NodeRequestCommand nodeRequestCommand) {
      DependantChain dependantChain = null;
      RequestId requestId = nodeRequestCommand.requestId();
      if (nodeCommand instanceof ExecuteWithInputs executeWithInputs) {
        dependantChain = executeWithInputs.dependantChain();
      } else if (nodeCommand instanceof SkipNode skipNode) {
        dependantChain = skipNode.dependantChain();
      }
      if (union(
              disabledDependantChains,
              allRequests
                  .get(requestId.originatedFrom())
                  .executionConfig()
                  .disabledDependantChains())
          .contains(dependantChain)) {
        throw new DisabledDependantChainException(dependantChain);
      }
    } else if (nodeCommand instanceof Flush flush) {
      DependantChain dependantChain = flush.nodeDependants();
      if (disabledDependantChains.contains(dependantChain)) {
        throw new DisabledDependantChainException(dependantChain);
      }
      if (allRequests.values().stream()
          .map(NodeResult::executionConfig)
          .map(NodeExecutionConfig::disabledDependantChains)
          .allMatch(disableDepChains -> disableDepChains.contains(dependantChain))) {
        throw new DisabledDependantChainException(dependantChain);
      }
    }
  }

  public void flush() {
    enqueueCommand(
        () -> {
          Map<NodeId, Map<RequestId, NodeInputCommand>> batchCommands = new LinkedHashMap<>();
          unFlushedRequests.forEach(
              requestId -> {
                NodeResult nodeResult = allRequests.get(requestId);
                NodeId nodeId = nodeResult.nodeId();
                if (nodeResult.future().isDone()) {
                  return;
                }
                NodeDefinition nodeDefinition = nodeDefinitionRegistry.get(nodeId);
                ExecuteWithInputs nodeCommand =
                    new ExecuteWithInputs(
                        nodeId,
                        nodeDefinition.getMainLogicDefinition().inputNames().stream()
                            .filter(s -> !nodeDefinition.dependencyNodes().containsKey(s))
                            .collect(toImmutableSet()),
                        nodeResult.inputs(),
                        DependantChainStart.instance(),
                        requestId);
                batchCommands
                    .computeIfAbsent(nodeId, _n -> new LinkedHashMap<>())
                    .put(requestId, nodeCommand);
                if (GRANULAR.equals(nodeExecStrategy)) {
                  CompletableFuture<Object> submissionResult =
                      executeCommand(nodeCommand)
                          .thenApply(NodeResponse::response)
                          .thenApply(
                              valueOrError -> {
                                if (valueOrError.error().isPresent()) {
                                  throw new RuntimeException(valueOrError.error().get());
                                } else {
                                  return valueOrError.value().orElse(null);
                                }
                              });
                  linkFutures(submissionResult, nodeResult.future());
                }
              });

          if (NodeExecStrategy.BATCH.equals(nodeExecStrategy)) {
            batchCommands.forEach(
                (nodeId, nodeRequestCommands) -> {
                  CompletableFuture<NodeBatchResponse> f =
                      executeBatchCommand(
                          new NodeInputBatch(
                              nodeId, nodeRequestCommands, DependantChainStart.instance()));
                  f.whenComplete(
                      (nodeBatchResponse, throwable) -> {
                        nodeRequestCommands.forEach(
                            (requestId, nodeRequestCommand) -> {
                              NodeResult nodeResult = allRequests.get(requestId);
                              Throwable error = throwable;
                              if (error == null) {
                                ValueOrError<Object> voe =
                                    Optional.ofNullable(
                                            nodeBatchResponse.responses().get(requestId))
                                        .map(NodeResponse::response)
                                        .orElse(ValueOrError.empty());
                                if (voe.error().isPresent()) {
                                  error = voe.error().get();
                                } else {
                                  nodeResult.future().complete(voe.value().orElse(null));
                                }
                              }
                              if (error != null) {
                                nodeResult.future().completeExceptionally(error);
                              }
                            });
                      });
                });
          }
          List<CompletableFuture<?>> futures = new ArrayList<>();
          unFlushedRequests.stream()
              .map(allRequests::get)
              .map(NodeResult::future)
              .forEach(futures::add);
          unFlushedRequests.stream()
              .map(requestId -> allRequests.get(requestId).nodeId())
              .distinct()
              .forEach(nodeId -> futures.add(executeCommand(new Flush(nodeId))));
          return allOf(futures.toArray(CompletableFuture[]::new))
              .whenComplete(
                  (_v, _t) -> {
                    dependantChainsPerNode.clear();
                    unFlushedRequests.clear();
                  });
        });
  }

  public KrystalNodeExecutorMetrics getKrystalNodeMetrics() {
    return krystalNodeMetrics;
  }

  /**
   * Prevents accepting new requests. For reasons of performance optimization, submitted requests
   * are executed in this method.
   */
  @Override
  public void close() {
    if (closed) {
      return;
    }
    this.closed = true;
    flush();
    enqueueCommand(
        () ->
            allOf(
                    allRequests.values().stream()
                        .map(NodeResult::future)
                        .toArray(CompletableFuture[]::new))
                .whenComplete((unused, throwable) -> commandQueueLease.close()));
  }

  private <T> CompletableFuture<T> enqueueCommand(Supplier<T> command) {
    return supplyAsync(
        () -> {
          krystalNodeMetrics.commandQueuedCount++;
          return command.get();
        },
        commandQueueLease.get());
  }

  private record NodeResult(
      NodeId nodeId,
      Inputs inputs,
      NodeExecutionConfig executionConfig,
      CompletableFuture<Object> future) {}
}
