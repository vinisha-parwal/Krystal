package com.flipkart.krystal.vajram.exec.greeting;

import com.flipkart.krystal.vajram.RequestBuilder;
import com.flipkart.krystal.vajram.VajramRequest;
import com.google.common.collect.ImmutableMap;

import java.util.Optional;

// Auto-generated and managed by Krystal
public record UserServiceVajramRequest(String userId) implements VajramRequest {

  public static Builder builder() {
    return new Builder();
  }

  @Override
  public ImmutableMap<String, Optional<Object>> asMap() {
    return ImmutableMap.<String, Optional<Object>>builder()
            .put("user_id", Optional.ofNullable(userId()))
            .build();  }

  public static final class Builder implements RequestBuilder<UserServiceVajramRequest> {

    private String userId;

    public Builder userId(String userId) {
      this.userId = userId;
      return this;
    }

    @Override
    public UserServiceVajramRequest build() {
      return new UserServiceVajramRequest(this.userId);
    }

    private Builder() {}
  }
}
