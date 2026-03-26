package org.acme.mcp;

import org.acme.mcp.model.JsonRpcRequest;
import org.jboss.resteasy.reactive.client.SseEvent;
import io.smallrye.mutiny.Multi;

import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;

public interface McpWeatherClient {

    @GET
    @Path("/mcp/sse")
    @Produces(MediaType.SERVER_SENT_EVENTS)
    Multi<SseEvent<String>> connectToSse();

    @POST
    @Path("")
    @Consumes(MediaType.APPLICATION_JSON)
    Response sendMessage(JsonRpcRequest request);
}