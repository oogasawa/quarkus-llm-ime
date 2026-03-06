package com.github.oogasawa.llmime;

import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;
import org.eclipse.microprofile.rest.client.inject.RegisterRestClient;

/**
 * REST client for vLLM OpenAI-compatible API.
 */
@RegisterRestClient(configKey = "vllm")
@Path("/v1")
public interface VllmClient {

    @POST
    @Path("/chat/completions")
    @Consumes(MediaType.APPLICATION_JSON)
    @Produces(MediaType.APPLICATION_JSON)
    ChatCompletionResponse chatCompletions(ChatCompletionRequest request);
}
