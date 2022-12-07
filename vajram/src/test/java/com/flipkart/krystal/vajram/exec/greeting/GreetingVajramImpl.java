package com.flipkart.krystal.vajram.exec.greeting;

import com.flipkart.krystal.vajram.ExecutionContext;
import com.flipkart.krystal.vajram.RequestBuilder;
import com.flipkart.krystal.vajram.inputs.InputCommand;
import com.flipkart.krystal.vajram.inputs.InputResolver;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableSet;

import java.util.Collection;
import java.util.Optional;
import java.util.Set;
import java.util.logging.Logger;

// Auto-generated and managed by Krystal
public final class GreetingVajramImpl extends GreetingVajram {

  @Override
  public ImmutableList<String> executeNonBlocking(ExecutionContext executionContext) {
    try {
      GreetingVajramRequest _request =
              new GreetingVajramRequest(((Optional<String>)executionContext.getValue("user_id")).get());
      UserInfo userInfo = executionContext.getValue("user_info");
//      Logger log = executionContext.getValue("log");
//      AnalyticsEventSink analyticsEventSink = executionContext.getValue("analytics_event_sink");
      return ImmutableList.of(
              createGreetingMessage(
                      new GreetingVajramInputUtils.EnrichedRequest(_request, userInfo)));
    }catch (Exception e){
      System.out.println(e.getCause());
    }
    return null;
  }

//  @Override
//  public Collection<InputResolver> getSimpleInputResolvers(){
//
//  }

  @Override
  public ImmutableList<RequestBuilder<?>> resolveInputOfDependency(
      String dependency, ImmutableSet<String> resolvableInputs, ExecutionContext executionContext) {
    switch (dependency) {
      case "user_info" -> {
        if (Set.of("user_id").equals(resolvableInputs)) {
          UserServiceVajramRequest userServiceVajramRequest =
              super.userIdForUserService(
                      executionContext.<Optional<String>>getValue("user_id").get());
          return ImmutableList.of(UserServiceVajramRequest.builder().userId(userServiceVajramRequest.userId()));
        }
      }
    }
    throw new RuntimeException();
  }
}
