package com.flipkart.krystal.krystex.commands;

import com.flipkart.krystal.krystex.node.DependantChain;
import com.flipkart.krystal.krystex.request.RequestId;
import java.util.Map;

public sealed interface BatchCommand<T extends NodeRequestCommand> extends NodeCommand
    permits DependencyCallbackBatch, NodeInputBatch {

  Map<RequestId, T> subCommands();

  DependantChain dependantChain();
}