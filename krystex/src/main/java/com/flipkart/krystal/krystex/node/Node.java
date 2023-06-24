package com.flipkart.krystal.krystex.node;

import static com.flipkart.krystal.data.ValueOrError.withError;
import static com.google.common.base.Functions.identity;
import static com.google.common.collect.ImmutableMap.toImmutableMap;
import static com.google.common.collect.ImmutableSet.toImmutableSet;
import static java.lang.Math.max;
import static java.util.Collections.emptyList;
import static java.util.concurrent.CompletableFuture.*;
import static java.util.stream.Collectors.groupingBy;
import static java.util.stream.Collectors.toMap;
import static java.util.stream.Collectors.toSet;

import com.flipkart.krystal.data.InputValue;
import com.flipkart.krystal.data.Inputs;
import com.flipkart.krystal.data.Results;
import com.flipkart.krystal.data.ValueOrError;
import com.flipkart.krystal.krystex.ComputeLogicDefinition;
import com.flipkart.krystal.krystex.IOLogicDefinition;
import com.flipkart.krystal.krystex.MainLogic;
import com.flipkart.krystal.krystex.MainLogicDefinition;
import com.flipkart.krystal.krystex.RequestId;
import com.flipkart.krystal.krystex.ResolverCommand;
import com.flipkart.krystal.krystex.ResolverCommand.SkipDependency;
import com.flipkart.krystal.krystex.ResolverDefinition;
import com.flipkart.krystal.krystex.commands.ExecuteWithDependency;
import com.flipkart.krystal.krystex.commands.ExecuteWithInputs;
import com.flipkart.krystal.krystex.commands.Flush;
import com.flipkart.krystal.krystex.commands.NodeRequestCommand;
import com.flipkart.krystal.krystex.commands.SkipNode;
import com.flipkart.krystal.krystex.decoration.FlushCommand;
import com.flipkart.krystal.krystex.decoration.LogicDecorationOrdering;
import com.flipkart.krystal.krystex.decoration.LogicExecutionContext;
import com.flipkart.krystal.krystex.decoration.MainLogicDecorator;
import com.flipkart.krystal.utils.ImmutableMapView;
import com.flipkart.krystal.utils.SkippedExecutionException;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.NavigableSet;
import java.util.Optional;
import java.util.Set;
import java.util.SortedSet;
import java.util.TreeSet;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.LongAdder;
import java.util.function.Function;
import java.util.function.Supplier;
import java.util.stream.Collector;
import java.util.stream.Stream;

class Node {

  private final NodeId nodeId;

  private final NodeDefinition nodeDefinition;

  private final KrystalNodeExecutor krystalNodeExecutor;

  /** decoratorType -> Decorator */
  private final Function<LogicExecutionContext, ImmutableMap<String, MainLogicDecorator>>
      requestScopedDecoratorsSupplier;

  private final ImmutableMapView<Optional<String>, List<ResolverDefinition>>
      resolverDefinitionsByInput;
  private final ImmutableMapView<String, ImmutableSet<ResolverDefinition>>
      resolverDefinitionsByDependencies;
  private final LogicDecorationOrdering logicDecorationOrdering;

  private final Map<RequestId, Map<String, DependencyNodeExecutions>> dependencyExecutions =
      new LinkedHashMap<>();

  private final Map<RequestId, Map<String, InputValue<Object>>> inputsValueCollector =
      new LinkedHashMap<>();

  private final Map<RequestId, Map<String, Results<Object>>> dependencyValuesCollector =
      new LinkedHashMap<>();

  /** A unique Result future for every requestId. */
  private final Map<RequestId, CompletableFuture<NodeResponse>> resultsByRequest =
      new LinkedHashMap<>();

  /**
   * A unique {@link CompletableFuture} for every new set of Inputs. This acts as a cache so that
   * the same computation is not repeated multiple times .
   */
  private final Map<Inputs, CompletableFuture<Object>> resultsCache = new LinkedHashMap<>();

  private final Map<RequestId, Boolean> mainLogicExecuted = new LinkedHashMap<>();

  private final Map<RequestId, Optional<SkipNode>> skipLogicRequested = new LinkedHashMap<>();

  private final Map<RequestId, Map<NodeLogicId, ResolverCommand>> resolverResults =
      new LinkedHashMap<>();

  private final Map<DependantChain, Boolean> flushedDependantChain = new LinkedHashMap<>();
  private final Map<DependantChain, Set<RequestId>> requestsByDependantChain =
      new LinkedHashMap<>();
  private final Map<RequestId, DependantChain> dependantChainByRequest = new LinkedHashMap<>();

  Node(
      NodeDefinition nodeDefinition,
      KrystalNodeExecutor krystalNodeExecutor,
      Function<LogicExecutionContext, ImmutableMap<String, MainLogicDecorator>>
          requestScopedDecoratorsSupplier,
      LogicDecorationOrdering logicDecorationOrdering) {
    this.nodeId = nodeDefinition.nodeId();
    this.nodeDefinition = nodeDefinition;
    this.krystalNodeExecutor = krystalNodeExecutor;
    this.requestScopedDecoratorsSupplier = requestScopedDecoratorsSupplier;
    this.logicDecorationOrdering = logicDecorationOrdering;
    this.resolverDefinitionsByInput =
        createResolverDefinitionsByInputs(nodeDefinition.resolverDefinitions());
    this.resolverDefinitionsByDependencies =
        ImmutableMapView.viewOf(
            nodeDefinition.resolverDefinitions().stream()
                .collect(groupingBy(ResolverDefinition::dependencyName, toImmutableSet())));
  }

  void executeCommand(Flush nodeCommand) {
    flushedDependantChain.put(nodeCommand.nodeDependants(), true);
    flushAllDependenciesIfNeeded(nodeCommand.nodeDependants());
    flushDecoratorsIfNeeded(nodeCommand.nodeDependants());
  }

  CompletableFuture<NodeResponse> executeRequestCommand(NodeRequestCommand nodeCommand) {
    RequestId requestId = nodeCommand.requestId();
    final CompletableFuture<NodeResponse> resultForRequest =
        resultsByRequest.computeIfAbsent(requestId, r -> new CompletableFuture<>());
    if (resultForRequest.isDone()) {
      // This is possible if this node was already skipped, for example.
      // If the result for this requestId is already available, just return and avoid unnecessary
      // computation.
      return resultForRequest;
    }
    try {
      if (nodeCommand instanceof SkipNode skipNode) {
        requestsByDependantChain
            .computeIfAbsent(skipNode.dependantChain(), k -> new LinkedHashSet<>())
            .add(requestId);
        dependantChainByRequest.put(requestId, skipNode.dependantChain());
        skipLogicRequested.put(requestId, Optional.of(skipNode));
        return handleSkipDependency(requestId, skipNode, resultForRequest);
      } else if (nodeCommand instanceof ExecuteWithDependency executeWithDependency) {
        executeWithDependency(requestId, executeWithDependency);
      } else if (nodeCommand instanceof ExecuteWithInputs executeWithInputs) {
        requestsByDependantChain
            .computeIfAbsent(executeWithInputs.dependantChain(), k -> new LinkedHashSet<>())
            .add(requestId);
        dependantChainByRequest.computeIfAbsent(requestId, r -> executeWithInputs.dependantChain());
        executeWithInputs(requestId, executeWithInputs);
      } else {
        throw new UnsupportedOperationException(
            "Unknown type of nodeCommand: %s".formatted(nodeCommand));
      }
      executeMainLogicIfPossible(requestId, resultForRequest);
    } catch (Throwable e) {
      resultForRequest.completeExceptionally(e);
    }
    return resultForRequest;
  }

  private void executeMainLogicIfPossible(
      RequestId requestId, CompletableFuture<NodeResponse> resultForRequest) {
    // If all the inputs and dependency values are available, then prepare run mainLogic
    MainLogicDefinition<Object> mainLogicDefinition = nodeDefinition.getMainLogicDefinition();
    ImmutableSet<String> inputNames = mainLogicDefinition.inputNames();
    Set<String> collect =
        new LinkedHashSet<>(
            inputsValueCollector.getOrDefault(requestId, ImmutableMap.of()).keySet());
    collect.addAll(dependencyValuesCollector.getOrDefault(requestId, ImmutableMap.of()).keySet());
    if (collect.containsAll(inputNames)) { // All the inputs of the logic node have data present
      executeMainLogic(resultForRequest, requestId);
    }
  }

  private CompletableFuture<NodeResponse> handleSkipDependency(
      RequestId requestId, SkipNode skipNode, CompletableFuture<NodeResponse> resultForRequest) {

    // Since this node is skipped, we need to get all the pending resolvers (irrespective of
    // whether their inputs are available or not) and mark them resolved.
    Set<ResolverDefinition> pendingResolvers =
        resolverDefinitionsByInput.values().stream()
            .flatMap(Collection::stream)
            .filter(
                resolverDefinition ->
                    !this.resolverResults
                        .computeIfAbsent(requestId, r -> new LinkedHashMap<>())
                        .containsKey(resolverDefinition.resolverNodeLogicId()))
            .collect(toSet());

    for (ResolverDefinition resolverDefinition : pendingResolvers) {
      executeResolver(requestId, resolverDefinition);
    }
    resultForRequest.completeExceptionally(skipNodeException(skipNode));
    return resultForRequest;
  }

  private static SkippedExecutionException skipNodeException(SkipNode skipNode) {
    return new SkippedExecutionException(skipNode.skipDependencyCommand().reason());
  }

  private void flushDecoratorsIfNeeded(DependantChain dependantChain) {
    if (!flushedDependantChain.getOrDefault(dependantChain, false)) {
      return;
    }
    Set<RequestId> requestIds = requestsByDependantChain.get(dependantChain);
    int requestIdExecuted = 0;
    for (RequestId requestId : requestIds) {
      if (mainLogicExecuted.getOrDefault(requestId, false)
          || skipLogicRequested.getOrDefault(requestId, Optional.empty()).isPresent()) {
        requestIdExecuted += 1;
      }
    }
    if (requestIdExecuted == requestIds.size()) {
      Iterable<MainLogicDecorator> reverseSortedDecorators =
          getSortedDecorators(dependantChain)::descendingIterator;
      for (MainLogicDecorator decorator : reverseSortedDecorators) {
        decorator.executeCommand(new FlushCommand(dependantChain));
      }
    }
  }

  private void executeWithInputs(RequestId requestId, ExecuteWithInputs executeWithInputs) {
    collectInputValues(requestId, executeWithInputs.inputNames(), executeWithInputs.values());
    execute(requestId, executeWithInputs.inputNames());
  }

  private void executeWithDependency(RequestId requestId, ExecuteWithDependency executeWithInput) {
    String dependencyName = executeWithInput.dependencyName();
    ImmutableSet<String> inputNames = ImmutableSet.of(dependencyName);
    if (dependencyValuesCollector
            .computeIfAbsent(requestId, k -> new LinkedHashMap<>())
            .putIfAbsent(dependencyName, executeWithInput.results())
        != null) {
      throw new DuplicateRequestException(
          "Duplicate data for dependency %s of node %s in request %s"
              .formatted(dependencyName, nodeId, requestId));
    }
    execute(requestId, inputNames);
  }

  private void execute(RequestId requestId, ImmutableSet<String> newInputNames) {
    MainLogicDefinition<Object> mainLogicDefinition = nodeDefinition.getMainLogicDefinition();

    Map<String, InputValue<Object>> allInputs =
        inputsValueCollector.computeIfAbsent(requestId, r -> new LinkedHashMap<>());
    Map<String, Results<Object>> allDependencies =
        dependencyValuesCollector.computeIfAbsent(requestId, k -> new LinkedHashMap<>());
    ImmutableSet<String> allInputNames = mainLogicDefinition.inputNames();
    Set<String> availableInputs =
        Stream.concat(allInputs.keySet().stream(), allDependencies.keySet().stream())
            .collect(toSet());
    if (availableInputs.isEmpty()) {
      if (!allInputNames.isEmpty()
          && nodeDefinition.resolverDefinitions().isEmpty()
          && !nodeDefinition.dependencyNodes().isEmpty()) {
        executeDependenciesWhenNoResolvers(requestId);
      }
      return;
    }

    Map<NodeLogicId, ResolverDefinition> pendingResolvers =
        getPendingResolvers(requestId, newInputNames, availableInputs);
    for (ResolverDefinition pendingResolver : pendingResolvers.values()) {
      executeResolver(requestId, pendingResolver);
    }
  }

  /**
   * @param requestId The requestId.
   * @param newInputNames The input names for which new values were just made available.
   * @param availableInputs The inputs for which values are available.
   * @return the resolver definitions which need at least one of the provided {@code inputNames} and
   *     all of whose inputs' values are available.
   */
  private Map<NodeLogicId, ResolverDefinition> getPendingResolvers(
      RequestId requestId, ImmutableSet<String> newInputNames, Set<String> availableInputs) {
    Map<NodeLogicId, ResolverCommand> nodeResults =
        this.resolverResults.computeIfAbsent(requestId, r -> new LinkedHashMap<>());

    Map<NodeLogicId, ResolverDefinition> pendingResolvers;
    Collector<ResolverDefinition, ?, Map<NodeLogicId, ResolverDefinition>> resolverCollector =
        toMap(ResolverDefinition::resolverNodeLogicId, identity(), (o1, o2) -> o1);
    Map<NodeLogicId, ResolverDefinition> pendingUnboundResolvers =
        resolverDefinitionsByInput.getOrDefault(Optional.<String>empty(), emptyList()).stream()
            .filter(
                resolverDefinition -> availableInputs.containsAll(resolverDefinition.boundFrom()))
            .filter(
                resolverDefinition ->
                    !nodeResults.containsKey(resolverDefinition.resolverNodeLogicId()))
            .collect(resolverCollector);
    pendingResolvers =
        newInputNames.stream()
            .flatMap(
                input ->
                    resolverDefinitionsByInput
                        .getOrDefault(Optional.ofNullable(input), ImmutableList.of())
                        .stream()
                        .filter(
                            resolverDefinition ->
                                availableInputs.containsAll(resolverDefinition.boundFrom()))
                        .filter(
                            resolverDefinition ->
                                !nodeResults.containsKey(resolverDefinition.resolverNodeLogicId())))
            .collect(resolverCollector);
    pendingResolvers.putAll(pendingUnboundResolvers);
    return pendingResolvers;
  }

  private void executeResolver(RequestId requestId, ResolverDefinition resolverDefinition) {
    Map<NodeLogicId, ResolverCommand> nodeResults =
        this.resolverResults.computeIfAbsent(requestId, r -> new LinkedHashMap<>());
    String dependencyName = resolverDefinition.dependencyName();
    NodeId depNodeId = nodeDefinition.dependencyNodes().get(dependencyName);
    NodeLogicId nodeLogicId = resolverDefinition.resolverNodeLogicId();
    ResolverCommand resolverCommand;
    Optional<SkipNode> skipRequested =
        this.skipLogicRequested.getOrDefault(requestId, Optional.empty());
    if (skipRequested.isPresent()) {
      resolverCommand = ResolverCommand.skip(skipRequested.get().skipDependencyCommand().reason());
    } else {
      Inputs inputsForResolver = getInputsForResolver(resolverDefinition, requestId);
      resolverCommand =
          nodeDefinition
              .nodeDefinitionRegistry()
              .logicDefinitionRegistry()
              .getResolver(nodeLogicId)
              .resolve(inputsForResolver);
    }
    nodeResults.put(nodeLogicId, resolverCommand);
    DependencyNodeExecutions dependencyNodeExecutions =
        dependencyExecutions
            .computeIfAbsent(requestId, k -> new LinkedHashMap<>())
            .computeIfAbsent(dependencyName, k -> new DependencyNodeExecutions());
    dependencyNodeExecutions.executedResolvers().add(resolverDefinition);
    if (resolverCommand instanceof SkipDependency) {
      if (dependencyValuesCollector.getOrDefault(requestId, ImmutableMap.of()).get(dependencyName)
          == null) {
        /* This is for the case where for some resolvers the input has already been resolved but we
        do need to skip them as well, as our current resolver is skipped.*/
        Set<RequestId> requestIdSet = new HashSet<>();
        requestIdSet.addAll(dependencyNodeExecutions.individualCallResponses().keySet());
        RequestId dependencyRequestId = requestId.append("%s[%s]".formatted(dependencyName, 0));
        /*Skipping Current resolver, as its a skip, we dont need to iterate
         * over fanout requests as the input is empty*/
        requestIdSet.add(dependencyRequestId);
        for (RequestId depRequestId : requestIdSet) {
          SkipNode skipNode =
              new SkipNode(
                  depNodeId,
                  depRequestId,
                  DependantChain.extend(
                      dependantChainByRequest.getOrDefault(
                          requestId, DependantChainStart.instance()),
                      nodeId,
                      dependencyName),
                  (SkipDependency) resolverCommand);
          dependencyNodeExecutions
              .individualCallResponses()
              .putIfAbsent(depRequestId, krystalNodeExecutor.executeCommand(skipNode));
        }
      }
    } else {
      // Since the resolver can return multiple inputs, we have to call the dependency Node
      // multiple times - each with a different request Id.
      // The current resolver  has triggered a fan-out.
      // So we need multiply the total number of requests to the dependency by n where n is
      // the size of the fan-out triggered by this resolver
      ImmutableList<Inputs> inputList = resolverCommand.getInputs();
      long executionsInProgress = dependencyNodeExecutions.executionCounter().longValue();
      Map<RequestId, Inputs> oldInputs = new LinkedHashMap<>();
      for (int i = 0; i < executionsInProgress; i++) {
        RequestId rid = requestId.append("%s[%s]".formatted(dependencyName, i));
        oldInputs.put(
            rid,
            new Inputs(
                dependencyNodeExecutions
                    .individualCallInputs()
                    .getOrDefault(rid, Inputs.empty())
                    .values()));
      }

      long batchSize = max(executionsInProgress, 1);
      int requestCounter = 0;
      for (int j = 0; j < inputList.size(); j++) {
        Inputs inputs = inputList.get(j);
        for (int i = 0; i < batchSize; i++) {
          RequestId dependencyRequestId =
              requestId.append("%s[%s]".formatted(dependencyName, j * batchSize + i));
          RequestId inProgressRequestId;
          if (executionsInProgress > 0) {
            inProgressRequestId = requestId.append("%s[%s]".formatted(dependencyName, i));
          } else {
            inProgressRequestId = dependencyRequestId;
          }
          Inputs oldInput = oldInputs.getOrDefault(inProgressRequestId, Inputs.empty());
          if (requestCounter >= executionsInProgress) {
            dependencyNodeExecutions.executionCounter().increment();
          }
          Inputs newInputs;
          if (j == 0) {
            newInputs = inputs;
          } else {
            newInputs = Inputs.union(oldInput.values(), inputs.values());
          }
          dependencyNodeExecutions.individualCallInputs().put(dependencyRequestId, newInputs);
          dependencyNodeExecutions
              .individualCallResponses()
              .putIfAbsent(
                  dependencyRequestId,
                  krystalNodeExecutor.executeCommand(
                      new ExecuteWithInputs(
                          depNodeId,
                          newInputs.values().keySet(),
                          newInputs,
                          DependantChain.extend(
                              dependantChainByRequest.getOrDefault(
                                  requestId, DependantChainStart.instance()),
                              nodeId,
                              dependencyName),
                          dependencyRequestId)));
        }
        requestCounter += batchSize;
      }
    }
    ImmutableSet<ResolverDefinition> resolverDefinitionsForDependency =
        this.resolverDefinitionsByDependencies.get(dependencyName);
    if (resolverDefinitionsForDependency.equals(dependencyNodeExecutions.executedResolvers())) {
      allOf(
              dependencyNodeExecutions
                  .individualCallResponses()
                  .values()
                  .toArray(CompletableFuture[]::new))
          .whenComplete(
              (unused, throwable) -> {
                enqueueOrExecuteCommand(
                    () -> {
                      Results<Object> results;
                      if (throwable != null) {
                        results =
                            new Results<>(ImmutableMap.of(Inputs.empty(), withError(throwable)));
                      } else {
                        results =
                            new Results<>(
                                dependencyNodeExecutions.individualCallResponses().values().stream()
                                    .map(cf -> cf.getNow(new NodeResponse()))
                                    .collect(
                                        toImmutableMap(
                                            NodeResponse::inputs, NodeResponse::response)));
                      }
                      return new ExecuteWithDependency(
                          this.nodeId, dependencyName, results, requestId);
                    },
                    depNodeId);
              });

      flushDependencyIfNeeded(
          dependencyName,
          dependantChainByRequest.getOrDefault(requestId, DependantChainStart.instance()));
    }
  }

  private void enqueueOrExecuteCommand(
      Supplier<NodeRequestCommand> commandGenerator, NodeId depNodeId) {
    MainLogicDefinition<Object> depMainLogic =
        nodeDefinition.nodeDefinitionRegistry().get(depNodeId).getMainLogicDefinition();
    if (depMainLogic instanceof IOLogicDefinition<Object>) {
      krystalNodeExecutor.enqueueNodeCommand(commandGenerator);
    } else if (depMainLogic instanceof ComputeLogicDefinition<Object>) {
      krystalNodeExecutor.executeCommand(commandGenerator.get());
    } else {
      throw new UnsupportedOperationException(
          "Unknown logicDefinition type %s".formatted(depMainLogic.getClass()));
    }
  }

  private void flushAllDependenciesIfNeeded(DependantChain dependantChain) {
    nodeDefinition
        .dependencyNodes()
        .keySet()
        .forEach(dependencyName -> flushDependencyIfNeeded(dependencyName, dependantChain));
  }

  private void flushDependencyIfNeeded(String dependencyName, DependantChain dependantChain) {
    if (!flushedDependantChain.getOrDefault(dependantChain, false)) {
      return;
    }
    Set<RequestId> requestsForDependantChain =
        requestsByDependantChain.getOrDefault(dependantChain, ImmutableSet.of());
    NodeId depNodeId = nodeDefinition.dependencyNodes().get(dependencyName);
    ImmutableSet<ResolverDefinition> resolverDefinitionsForDependency =
        this.resolverDefinitionsByDependencies.get(dependencyName);
    if (!requestsForDependantChain.isEmpty()
        && requestsForDependantChain.stream()
            .allMatch(
                requestId ->
                    resolverDefinitionsForDependency.equals(
                        this.dependencyExecutions
                            .computeIfAbsent(requestId, k -> new LinkedHashMap<>())
                            .computeIfAbsent(dependencyName, k -> new DependencyNodeExecutions())
                            .executedResolvers()))) {

      krystalNodeExecutor.executeCommand(
          new Flush(depNodeId, DependantChain.extend(dependantChain, nodeId, dependencyName)));
    }
  }

  private Inputs getInputsForResolver(ResolverDefinition resolverDefinition, RequestId requestId) {
    Map<String, InputValue<Object>> allInputs =
        inputsValueCollector.computeIfAbsent(requestId, r -> new LinkedHashMap<>());
    ImmutableSet<String> boundFrom = resolverDefinition.boundFrom();
    Map<String, InputValue<Object>> inputValues = new LinkedHashMap<>();
    for (String boundFromInput : boundFrom) {
      InputValue<Object> voe = allInputs.get(boundFromInput);
      if (voe == null) {
        inputValues.put(
            boundFromInput,
            dependencyValuesCollector
                .computeIfAbsent(requestId, k -> new LinkedHashMap<>())
                .get(boundFromInput));
      } else {
        inputValues.put(boundFromInput, voe);
      }
    }
    return new Inputs(inputValues);
  }

  private void executeDependenciesWhenNoResolvers(RequestId requestId) {
    nodeDefinition
        .dependencyNodes()
        .forEach(
            (depName, depNodeId) -> {
              if (!dependencyValuesCollector
                  .getOrDefault(requestId, ImmutableMap.of())
                  .containsKey(depName)) {
                RequestId dependencyRequestId = requestId.append("%s".formatted(depName));
                CompletableFuture<NodeResponse> nodeResponse =
                    krystalNodeExecutor.executeCommand(
                        new ExecuteWithInputs(
                            depNodeId,
                            ImmutableSet.of(),
                            Inputs.empty(),
                            DependantChain.extend(
                                dependantChainByRequest.get(requestId), nodeId, depName),
                            dependencyRequestId));
                nodeResponse
                    .thenApply(NodeResponse::response)
                    .whenComplete(
                        (response, throwable) -> {
                          enqueueOrExecuteCommand(
                              () -> {
                                ValueOrError<Object> valueOrError = response;
                                if (throwable != null) {
                                  valueOrError = withError(throwable);
                                }
                                return new ExecuteWithDependency(
                                    this.nodeId,
                                    depName,
                                    new Results<>(ImmutableMap.of(Inputs.empty(), valueOrError)),
                                    requestId);
                              },
                              depNodeId);
                        });
              }
            });
  }

  private void executeMainLogic(
      CompletableFuture<NodeResponse> resultForRequest, RequestId requestId) {
    MainLogicDefinition<Object> mainLogicDefinition = nodeDefinition.getMainLogicDefinition();
    MainLogicInputs mainLogicInputs = getInputsForMainLogic(requestId);
    // Retrieve existing result from cache if result for this set of inputs has already been
    // calculated
    CompletableFuture<Object> resultFuture =
        resultsCache.get(mainLogicInputs.nonDependencyInputs());
    if (resultFuture == null) {
      resultFuture =
          executeDecoratedMainLogic(
              mainLogicInputs.allInputsAndDependencies(), mainLogicDefinition, requestId);
      resultsCache.put(mainLogicInputs.nonDependencyInputs(), resultFuture);
    }
    resultFuture
        .handle(ValueOrError::valueOrError)
        .thenAccept(
            value ->
                resultForRequest.complete(
                    new NodeResponse(mainLogicInputs.nonDependencyInputs(), value)));
    mainLogicExecuted.put(requestId, true);
    flushDecoratorsIfNeeded(dependantChainByRequest.get(requestId));
  }

  private CompletableFuture<Object> executeDecoratedMainLogic(
      Inputs inputs, MainLogicDefinition<Object> mainLogicDefinition, RequestId requestId) {
    SortedSet<MainLogicDecorator> sortedDecorators =
        getSortedDecorators(dependantChainByRequest.get(requestId));
    MainLogic<Object> logic = mainLogicDefinition::execute;

    for (MainLogicDecorator mainLogicDecorator : sortedDecorators) {
      logic = mainLogicDecorator.decorateLogic(logic, mainLogicDefinition);
    }
    return logic.execute(ImmutableList.of(inputs)).get(inputs);
  }

  private MainLogicInputs getInputsForMainLogic(RequestId requestId) {
    Inputs inputValues =
        new Inputs(inputsValueCollector.getOrDefault(requestId, ImmutableMap.of()));
    Map<String, Results<Object>> dependencyValues =
        dependencyValuesCollector.getOrDefault(requestId, ImmutableMap.of());
    Inputs allInputsAndDependencies = Inputs.union(dependencyValues, inputValues.values());
    return new MainLogicInputs(inputValues, allInputsAndDependencies);
  }

  private void collectInputValues(RequestId requestId, Set<String> inputNames, Inputs inputs) {
    for (String inputName : inputNames) {
      if (inputsValueCollector
              .computeIfAbsent(requestId, r -> new LinkedHashMap<>())
              .putIfAbsent(inputName, inputs.getInputValue(inputName))
          != null) {
        throw new DuplicateRequestException(
            "Duplicate data for inputs %s of node %s in request %s"
                .formatted(inputNames, nodeId, requestId));
      }
    }
  }

  private NavigableSet<MainLogicDecorator> getSortedDecorators(DependantChain dependantChain) {
    MainLogicDefinition<Object> mainLogicDefinition = nodeDefinition.getMainLogicDefinition();
    Map<String, MainLogicDecorator> decorators =
        new LinkedHashMap<>(
            mainLogicDefinition.getSessionScopedLogicDecorators(nodeDefinition, dependantChain));
    // If the same decoratorType is configured for session and request scope, request scope
    // overrides session scope.
    decorators.putAll(
        requestScopedDecoratorsSupplier.apply(
            new LogicExecutionContext(
                nodeId,
                mainLogicDefinition.logicTags(),
                dependantChain,
                nodeDefinition.nodeDefinitionRegistry())));
    TreeSet<MainLogicDecorator> sortedDecorators =
        new TreeSet<>(logicDecorationOrdering.decorationOrder());
    sortedDecorators.addAll(decorators.values());
    return sortedDecorators;
  }

  private static ImmutableMapView<Optional<String>, List<ResolverDefinition>>
      createResolverDefinitionsByInputs(ImmutableList<ResolverDefinition> resolverDefinitions) {
    Map<Optional<String>, List<ResolverDefinition>> resolverDefinitionsByInput =
        new LinkedHashMap<>();
    resolverDefinitions.forEach(
        resolverDefinition -> {
          if (!resolverDefinition.boundFrom().isEmpty()) {
            resolverDefinition
                .boundFrom()
                .forEach(
                    input ->
                        resolverDefinitionsByInput
                            .computeIfAbsent(Optional.of(input), s -> new ArrayList<>())
                            .add(resolverDefinition));
          } else {
            resolverDefinitionsByInput
                .computeIfAbsent(Optional.empty(), s -> new ArrayList<>())
                .add(resolverDefinition);
          }
        });
    return ImmutableMapView.viewOf(resolverDefinitionsByInput);
  }

  private record DependencyNodeExecutions(
      LongAdder executionCounter,
      Set<ResolverDefinition> executedResolvers,
      Map<RequestId, Inputs> individualCallInputs,
      Map<RequestId, CompletableFuture<NodeResponse>> individualCallResponses) {

    private DependencyNodeExecutions() {
      this(new LongAdder(), new LinkedHashSet<>(), new LinkedHashMap<>(), new LinkedHashMap<>());
    }
  }

  private record MainLogicInputs(Inputs nonDependencyInputs, Inputs allInputsAndDependencies) {}
}
