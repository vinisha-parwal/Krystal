package com.flipkart.krystal.vajram.exec.test_vajrams.hellofriends;

import static com.flipkart.krystal.vajram.inputs.SingleValue.empty;

import com.flipkart.krystal.vajram.RequestBuilder;
import com.flipkart.krystal.vajram.VajramRequest;
import com.flipkart.krystal.vajram.inputs.SingleValue;
import com.google.common.collect.ImmutableMap;
import java.util.HashMap;
import java.util.Map;

// Auto-generated and managed by Krystal
public record HelloFriendsRequest(String userId, int numberOfFriends) implements VajramRequest {

  public static Builder builder() {
    return new Builder();
  }

  @Override
  public ImmutableMap<String, SingleValue<?>> asMap() {
    Map<String, SingleValue<?>> map = new HashMap<>();
    map.put("user_id", new SingleValue<>(userId()));
    map.put("number_of_friends", new SingleValue<>(numberOfFriends()));
    return ImmutableMap.copyOf(map);
  }

  static HelloFriendsRequest fromMap(ImmutableMap<String, SingleValue<?>> values) {
    return HelloFriendsRequest.builder()
        .userId((String) values.getOrDefault("user_id", empty()).value().orElse(null))
        .numberOfFriends(
            (int) values.getOrDefault("number_of_friends", empty()).value().orElse(null))
        .build();
  }

  public static class Builder implements RequestBuilder<HelloFriendsRequest> {

    private String userId;
    private int numberOfFriends;

    Builder() {}

    public Builder userId(String userId) {
      this.userId = userId;
      return this;
    }

    public Builder numberOfFriends(int numberOfUsers) {
      this.numberOfFriends = numberOfUsers;
      return this;
    }

    public HelloFriendsRequest build() {
      return new HelloFriendsRequest(userId, numberOfFriends);
    }
  }
}
