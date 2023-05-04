package com.flipkart.krystal.krystex.node;

import java.util.Optional;

public record DefaultDependantChain(
    Optional<NodeId> nodeId, String dependencyName, DependantChain dependantChain)
    implements DependantChain {

  public DefaultDependantChain(String dependencyName, DependantChain dependantChain) {
    this(Optional.empty(), dependencyName, dependantChain);
  }

  @Override
  public String toString() {
    return "%s.%s".formatted(dependantChain().toString(), dependencyName());
  }

  @Override
  public boolean contains(NodeId nodeId) {
    return this.nodeId.map(n -> n.equals(nodeId)).orElse(false) || dependantChain.contains(nodeId);
  }

  @Override
  public int hashCode() {
    return defaultDependantChain().hashCode();
  }

  @Override
  public boolean equals(Object obj) {
    return this.hashCode() == obj.hashCode();
  }

  private String defaultDependantChain() {
    return (this.dependantChain.toString() + ":" + this.dependencyName);
  }
}
