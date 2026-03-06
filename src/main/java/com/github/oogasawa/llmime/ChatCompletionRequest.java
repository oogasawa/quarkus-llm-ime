package com.github.oogasawa.llmime;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.JsonInclude;
import java.util.List;

@JsonInclude(JsonInclude.Include.NON_NULL)
public record ChatCompletionRequest(
    String model,
    List<Message> messages,
    @JsonProperty("max_tokens") int maxTokens,
    double temperature,
    Integer n
) {
    public ChatCompletionRequest(String model, List<Message> messages, int maxTokens, double temperature) {
        this(model, messages, maxTokens, temperature, null);
    }

    public record Message(String role, String content) {}
}
