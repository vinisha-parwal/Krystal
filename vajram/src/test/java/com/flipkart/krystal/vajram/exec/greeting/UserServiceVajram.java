package com.flipkart.krystal.vajram.exec.greeting;

import static com.flipkart.krystal.datatypes.StringType.string;
import static com.flipkart.krystal.vajram.exec.greeting.UserServiceVajram.ID;

import com.flipkart.krystal.vajram.*;
import com.flipkart.krystal.vajram.inputs.Input;
import com.flipkart.krystal.vajram.inputs.ResolutionSources;
import com.flipkart.krystal.vajram.inputs.Resolve;
import com.flipkart.krystal.vajram.inputs.VajramInputDefinition;
import java.util.List;
import java.util.concurrent.CompletableFuture;

@VajramDef(ID)
public abstract class UserServiceVajram extends NonBlockingVajram<UserInfo> {

  public static final String ID = "com.flipkart.userServiceVajram";
  public static final String USER_ID = "user_id";

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
            .build());
  }

  @Resolve(value = "user_id", inputs = {})
  public String getUserId(){
    return "vinisha";
  }
  @VajramLogic
  public UserInfo callUserService(String userId){
    // Make a call to user service and get user info
    return new UserInfo(userId);
  }
}
