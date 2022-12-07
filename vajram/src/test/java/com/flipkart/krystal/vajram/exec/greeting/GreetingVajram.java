package com.flipkart.krystal.vajram.exec.greeting;

import static com.flipkart.krystal.datatypes.JavaType.java;
import static com.flipkart.krystal.datatypes.StringType.string;
import static com.flipkart.krystal.vajram.VajramID.vajramID;
import static com.flipkart.krystal.vajram.exec.greeting.GreetingVajram.ID;

import com.flipkart.krystal.vajram.NonBlockingVajram;
import com.flipkart.krystal.vajram.VajramDef;
import com.flipkart.krystal.vajram.VajramLogic;
import com.flipkart.krystal.vajram.inputs.*;
import com.google.common.collect.ImmutableList;

import java.lang.System.Logger;
import java.util.List;
import java.util.logging.Level;

/**
 * Given a userId, this Vajram composes and returns a 'Hello!' greeting addressing the user by name
 * (as declared by the user in their profile).
 */
@VajramDef(ID) // Unique Id of this Vajram
// SyncVajram means that this Vajram does not directly perform any blocking operations.
public abstract sealed class GreetingVajram extends NonBlockingVajram<String>
    permits GreetingVajramImpl {
  public static final String ID = "com.flipkart.greetingVajram";

  // Static declaration of all the inputs of this Vajram.
  // This includes inputs provided by clients of this vajram,
  // design-time dependencies of this vajram, as well as
  // objects like loggers and metrics collectors injected by the runtime.
  @Override
  public List<VajramInputDefinition> getInputDefinitions() {
    return List.of(
        Input.builder()
            // Local name for this input
            .name("user_id")
            // Data type - used for code generation
            .type(string())
            // If this input is not provided by the client, throw a build time error.
            .mandatory()
            .build(),
        Dependency.builder()
            // Data type of resolved dependencies is inferred from the
            // dependency vajram's Definition
            .name("user_info")
            // GreetingVajram needs UserService's Response to compose the Greeting
            // which it can get from the UserServiceVajram
            // (which is an Async Vajram as it makes network calls.
            .dataAccessSpec(vajramID(UserServiceVajram.ID))
            // If this dependency fails, fail this Vajram
            .isMandatory()
            .build());
  }

  // Resolving (or providing) inputs of dependencies
  // is the responsibility of this Vajram (inputs of a vajram are resolved by its client Vajrams).
  // In this case the UserServiceVajram needs a user_id to retrieve user info from User Service.
  // So it's GreetingVajram's responsibility to provide that input.
  @Override
  public ImmutableList<InputResolver> getSimpleInputResolvers() {
    return ImmutableList.of(new ForwardingResolver(
            "user_id",
            "user_info",
            "user_id",
            (userId) -> new UserServiceVajramRequest((String) userId)));
  }
  @Resolve(value = "user_info", inputs = "user_service_request")
  public UserServiceVajramRequest userIdForUserService(@BindFrom("user_id") String userId) {
    return new UserServiceVajramRequest(userId);
  }


  // This is the core business logic of this Vajram
  // Sync vajrams can return any object. AsyncVajrams need to return {CompletableFuture}s
  @VajramLogic
  public String createGreetingMessage(GreetingVajramInputUtils.EnrichedRequest request) {
    String userId = request.userId();
    String greeting = "Hello " + request.userInfo().userName() + "! Hope you are doing well!";
//    request.log().log(Level.INFO, "Greeting user " + userId);
//    request.analyticsEventSink().pushEvent("event_type", new GreetingEvent(userId, greeting));
    return greeting;
  }
}
