package jo.edu.yu.yu_chatbot.embedding;

import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Primary;
import org.springframework.http.MediaType;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import java.util.Collections;
import java.util.List;
import java.util.stream.Collectors;

@Component
@Slf4j
public class OllamaEmbeddingClient implements EmbeddingClient {

    @Value("${ollama.base-url:http://localhost:11434}")
    private String ollamaUrl;

    @Value("${ollama.model:bge-m3}")
    private String modelName;

    private RestClient restClient;

    @PostConstruct
    public void init() {
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(15000);
        factory.setReadTimeout(120000);

        this.restClient = RestClient.builder()
                .requestFactory(factory)
                .build();

        try {
            generateEmbedding("warmup");
        } catch (Exception e) {
            log.warn("Warm up failed: {}", e.getMessage());
        }
    }

    @Override
    public float[] embed(String text) {
        return generateEmbedding(text);
    }

    @Override
    public List<float[]> batchEmbed(List<String> texts) {
        if (texts == null || texts.isEmpty()) return Collections.emptyList();

        return texts.parallelStream()
                .map(this::generateEmbedding)
                .collect(Collectors.toList());
    }

    private float[] generateEmbedding(String text) {
        String url = ollamaUrl + "/api/embeddings";
        String safeText = text.replace("\n", " ").trim();
        OllamaRequest request = new OllamaRequest(modelName, safeText);

        try {
            OllamaResponse response = restClient.post()
                    .uri(url)
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(request)
                    .retrieve()
                    .body(OllamaResponse.class);

            if (response != null && response.embedding() != null) {
                float[] vector = new float[response.embedding().size()];
                for (int i = 0; i < response.embedding().size(); i++) {
                    vector[i] = response.embedding().get(i).floatValue();
                }
                return vector;
            }
        } catch (Exception e) {
            log.error("Ollama Error for text segment: '{}'. Cause: {}",
                    safeText.substring(0, Math.min(safeText.length(), 30)), e.getMessage());
            return new float[1024];
        }
        return new float[1024];
    }

    record OllamaRequest(String model, String prompt) {}
    record OllamaResponse(List<Double> embedding) {}
}