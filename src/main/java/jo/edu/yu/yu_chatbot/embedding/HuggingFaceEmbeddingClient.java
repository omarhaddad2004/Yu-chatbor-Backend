package jo.edu.yu.yu_chatbot.embedding;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Primary;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import java.net.URI;
import java.util.*;

/**
 * Client responsible for communicating with the Hugging Face Inference API
 * to generate vector embeddings for text chunks.
 */
@Component
@Slf4j
@Primary
public class HuggingFaceEmbeddingClient implements EmbeddingClient {

    @Value("${huggingface.api-token}")
    private String apiToken;

    @Value("${huggingface.model:BAAI/bge-m3}")
    private String model;

    private static final int DIMENSIONS = 1024;
    private final RestClient restClient;

    public HuggingFaceEmbeddingClient() {
        // Extended timeouts are necessary to prevent ReadTimeoutException
        // especially when the Hugging Face model is cold-starting.
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(15000);
        factory.setReadTimeout(120000);

        this.restClient = RestClient.builder()
                .requestFactory(factory)
                .build();
    }

    @Override
    public float[] embed(String text) {
        List<float[]> result = batchEmbed(List.of(text));

        // Return an empty array instead of a zero-filled array on failure
        // to allow upstream services (like VectorStoreService) to safely skip processing.
        return (result == null || result.isEmpty()) ? new float[0] : result.get(0);
    }

    @Override
    public List<float[]> batchEmbed(List<String> texts) {
        if (texts == null || texts.isEmpty()) {
            return Collections.emptyList();
        }

        try {
            String url = "https://router.huggingface.co/hf-inference/models/" + model + "/pipeline/feature-extraction";

            Map<String, Object> body = new HashMap<>();
            body.put("inputs", texts);
            body.put("options", Map.of("wait_for_model", true, "use_cache", true));

            Object response = restClient.post()
                    .uri(URI.create(url))
                    .header(HttpHeaders.AUTHORIZATION, "Bearer " + apiToken)
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(body)
                    .retrieve()
                    .body(Object.class);

            List<float[]> results = new ArrayList<>();
            if (response instanceof List<?> outerList) {
                for (Object item : outerList) {
                    if (item instanceof List<?> vectorList) {
                        float[] vector = new float[DIMENSIONS];
                        for (int i = 0; i < Math.min(vectorList.size(), DIMENSIONS); i++) {
                            vector[i] = ((Number) vectorList.get(i)).floatValue();
                        }
                        results.add(vector);
                    }
                }
            }
            return results;
        } catch (Exception e) {
            log.error("Hugging Face API Error: {}", e.getMessage());
            return Collections.emptyList();
        }
    }
}