package com.flipkart.krystal.krystex.resolution;

import java.util.List;

public record DependencyResolutionRequest(
    String dependencyName, List<ResolverDefinition> resolverDefinitions) {}