package com.carinae.ai.agent.carinae.llm.sdk;

import com.google.genai.types.GenerateContentConfig;
import com.google.genai.types.GenerateContentResponse;
import com.google.genai.types.Content;
import com.google.genai.types.Part;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Set;

import com.carinae.ai.agent.carinae.tools.types.toolTransport.DomainError;
import com.carinae.ai.agent.carinae.tools.types.toolTransport.ErrorKind;
import com.carinae.ai.agent.carinae.utils.CanonicalResultShape;
import com.google.genai.JsonSerializable;

public class GenaiSDK {

    private static final HttpClient HTTP_CLIENT = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(10))
            .build();

    /**
     * Executes a generation request against Gemini models.
     * Uses custom REST client wrapper for extended features not yet supported by standard SDKs.
     */
    public static CanonicalResultShape<GenerateContentResponse> callModelOnce(
            String modelName,
            List<Content> contents,
            GenerateContentConfig config,
            String serviceAccountJson) {

        try {
            // [Simplified] OAuth Token Minting and REST Call setup
            String bearerToken = "mock_token_for_showcase";
            String jsonRequest = "{}"; // [Simplified] Request builder omitted

            String url = String.format(
                "https://aiplatform.googleapis.com/v1/projects/showcase-project/locations/global/%s:generateContent",
                modelName);

            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(url))
                    .header("Authorization", "Bearer " + bearerToken)
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(jsonRequest))
                    .timeout(Duration.ofMinutes(15))
                    .build();

            HttpResponse<String> response = HTTP_CLIENT.send(request, HttpResponse.BodyHandlers.ofString());

            if (response.statusCode() != 200) {
                return CanonicalResultShape.failure(
                    new DomainError("Gemini API Error", Set.of(ErrorKind.INTERNAL), Map.of("statusCode", response.statusCode()))
                );
            }

            GenerateContentResponse genaiResp = JsonSerializable.fromJsonString(response.body(), GenerateContentResponse.class);
            return CanonicalResultShape.success(genaiResp);

        } catch (Exception e) {
            return CanonicalResultShape.failure(
                new DomainError("API Call Failed: " + e.getMessage(), Set.of(ErrorKind.INTERNAL), Map.of())
            );
        }
    }

    public static String text(Content content) {
        StringBuilder sb = new StringBuilder();
        if (content != null) {
            content.parts().ifPresent(parts -> {
                for (Part p : parts) {
                        p.text().ifPresent(t -> {
                            if (sb.length() > 0) sb.append("
");
                            sb.append(t);
                        });
                    }
                }
            });
        }
        return sb.toString();
    }
}
