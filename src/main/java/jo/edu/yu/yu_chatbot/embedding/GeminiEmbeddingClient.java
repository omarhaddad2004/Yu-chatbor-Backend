package jo.edu.yu.yu_chatbot.embedding;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import java.net.URI;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Client responsible for generating vector embeddings using the Gemini API.
 * Implements true Round-Robin load balancing across multiple API keys to prevent rate limits.
 */
@Component
@Slf4j
public class GeminiEmbeddingClient implements EmbeddingClient {

    private final String embeddingModel;
    private final int maxDimensions;
    private final RestClient restClient;
    private final List<String> apiKeys;
    private final AtomicInteger currentKeyIndex = new AtomicInteger(0);

    public GeminiEmbeddingClient(
            @Value("${gemini.api.keys}") String keysMetadata,
            @Value("${gemini.embedding.model:gemini-embedding-001}") String embeddingModel,
            @Value("${gemini.embedding.max-dimensions:2048}") int maxDimensions) {

        this.embeddingModel = embeddingModel;
        this.maxDimensions = maxDimensions;
        this.restClient = RestClient.create();

        this.apiKeys = Arrays.stream(keysMetadata.split(","))
                .map(String::trim)
                .filter(key -> !key.isEmpty())
                .toList();

        log.info("=== GEMINI MULTI-KEY EMBEDDING CLIENT ===");
        log.info("Model: {}", this.embeddingModel);
        log.info("Loaded Keys Count: {}", this.apiKeys.size());
    }

    @Override
    public float[] embed(String text) {
        List<float[]> result = batchEmbed(List.of(text));
        return result.isEmpty() ? new float[0] : result.get(0);
    }

    @Override
    public List<float[]> batchEmbed(List<String> texts) {
        if (texts == null || texts.isEmpty()) {
            return Collections.emptyList();
        }

        if (apiKeys.isEmpty()) {
            log.error("No API keys configured for Gemini Embedding Client.");
            return Collections.emptyList();
        }

        List<BatchRequestItem> items = new ArrayList<>();
        for (String text : texts) {
            String safeText = text.replace("\n", " ").trim();
            // Truncate text to fit within typical token limits
            if (safeText.length() > 6000) {
                safeText = safeText.substring(0, 6000);
            }

            items.add(new BatchRequestItem(
                    "models/" + embeddingModel,
                    new Content(List.of(new Part(safeText)))
            ));
        }
        BatchEmbedRequest batchBody = new BatchEmbedRequest(items);

        int maxAttempts = Math.max(2, apiKeys.size());

        for (int i = 0; i < maxAttempts; i++) {
            // Apply true Round-Robin utilizing Math.abs to prevent overflow exceptions
            int index = Math.abs(currentKeyIndex.getAndIncrement() % apiKeys.size());
            String currentApiKey = apiKeys.get(index);

            String rawUrl = "https://generativelanguage.googleapis.com/v1beta/models/"
                    + embeddingModel + ":batchEmbedContents?key=" + currentApiKey;

            try {
                BatchEmbedResponse response = restClient.post()
                        .uri(URI.create(rawUrl))
                        .contentType(MediaType.APPLICATION_JSON)
                        .body(batchBody)
                        .retrieve()
                        .body(BatchEmbedResponse.class);

                if (response != null && response.embeddings() != null) {
                    List<float[]> results = new ArrayList<>();
                    for (EmbeddingResult item : response.embeddings()) {
                        List<Double> values = item.values();

                        // Apply dimensionality truncation if necessary
                        int finalSize = Math.min(values.size(), maxDimensions);
                        float[] vector = new float[finalSize];
                        for (int k = 0; k < finalSize; k++) {
                            vector[k] = values.get(k).floatValue();
                        }
                        results.add(vector);
                    }
                    return results;
                }

            } catch (Exception e) {
                // Handle rate limits gracefully by failing over to the next key
                if (e.getMessage() != null && (e.getMessage().contains("429") || e.getMessage().contains("Too Many Requests"))) {
                    log.warn("Rate limit hit for API Key index {}. Attempting failover to the next key...", index);
                } else {
                    log.error("Fatal Batch Error during embedding generation: {}", e.getMessage());
                    break;
                }
            }
        }

        log.error("ALL KEYS EXHAUSTED OR FAILED! Skipping this embedding batch.");
        return Collections.emptyList();
    }

    // Records (DTOs)
    record BatchEmbedRequest(List<BatchRequestItem> requests) {}
    record BatchRequestItem(String model, Content content) {}
    record Content(List<Part> parts) {}
    record Part(String text) {}
    record BatchEmbedResponse(List<EmbeddingResult> embeddings) {}
    record EmbeddingResult(List<Double> values) {}
}