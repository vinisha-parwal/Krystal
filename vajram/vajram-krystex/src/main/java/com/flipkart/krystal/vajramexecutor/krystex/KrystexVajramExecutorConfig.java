package com.flipkart.krystal.vajramexecutor.krystex;

import com.flipkart.krystal.krystex.kryon.KryonExecutorConfig;
import com.flipkart.krystal.krystex.kryon.KryonExecutorConfig.KryonExecutorConfigBuilder;
import com.flipkart.krystal.vajramexecutor.krystex.inputinjection.InputInjectionProvider;
import lombok.Builder;
import org.checkerframework.checker.nullness.qual.Nullable;

public record KrystexVajramExecutorConfig(
    String requestId,
    KryonExecutorConfigBuilder kryonExecutorConfigBuilder,
    @Nullable InputInjectionProvider inputInjectionProvider) {

  @Builder
  public KrystexVajramExecutorConfig {
    if (kryonExecutorConfigBuilder == null) {
      kryonExecutorConfigBuilder = KryonExecutorConfig.builder();
    }
  }
}
