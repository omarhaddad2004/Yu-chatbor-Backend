package jo.edu.yu.yu_chatbot.embedding;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.util.Arrays;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Client responsible for interacting with the Gemini LLM API.
 * Implements load balancing (True Round-Robin) across multiple API keys to avoid rate limits,
 * supporting both synchronous and streaming responses with automatic failover.
 */
@Component
@Slf4j
public class GeminiLlmClient implements LlmClient {

    private final WebClient webClient;
    private final List<String> apiKeys;
    private final AtomicInteger currentKeyIndex = new AtomicInteger(0);

    @Value("${gemini.chat.model:gemini-1.5-flash-latest}")
    private String model;

    public GeminiLlmClient(WebClient webClient, @Value("${gemini.api.keys}") String keysMetadata) {
        this.webClient = webClient;
        this.apiKeys = Arrays.stream(keysMetadata.split(","))
                .map(String::trim)
                .filter(key -> !key.isEmpty())
                .toList();
        log.info("Gemini Keys Loaded: {} keys available for load balancing.", apiKeys.size());
    }

    @Override
    public String generateAnswer(String prompt) {
        for (int i = 0; i < apiKeys.size(); i++) {
            // Ensure true Round-Robin by incrementing the index on every call
            // Math.abs prevents negative index out of bounds if AtomicInteger overflows
            int index = Math.abs(currentKeyIndex.getAndIncrement() % apiKeys.size());
            String currentKey = apiKeys.get(index);

            try {
                return callGemini(prompt, currentKey);
            } catch (Exception e) {
                log.warn("API Key at index {} failed. Error: {}. Attempting failover...", index, e.getMessage());
            }
        }
        log.error("ALL Gemini API Keys are exhausted or failed!");
        return "عذراً، جميع المحركات مشغولة حالياً، يرجى المحاولة لاحقاً.";
    }

    private String callGemini(String prompt, String apiKey) {
        GeminiChatRequest request = createRequest(prompt);

        GeminiChatResponse response = webClient.post()
                .uri("https://generativelanguage.googleapis.com/v1beta/models/" + model + ":generateContent")
                .header("x-goog-api-key", apiKey)
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(request)
                .retrieve()
                .bodyToMono(GeminiChatResponse.class)
                .block();

        if (response == null || response.candidates() == null || response.candidates().length == 0) {
            throw new RuntimeException("Empty response received from Gemini API");
        }
        return response.candidates()[0].content().parts()[0].text();
    }

    @Override
    public Flux<String> streamAnswer(String prompt) {
        GeminiChatRequest request = createRequest(prompt);

        // Utilizing defer so that the retry operator evaluates the dynamic state (next key) on each attempt
        return Flux.defer(() -> {
                    int index = Math.abs(currentKeyIndex.getAndIncrement() % apiKeys.size());
                    String currentKey = apiKeys.get(index);
                    log.debug("Attempting Stream with API Key at index {}", index);

                    return webClient.post()
                            .uri("https://generativelanguage.googleapis.com/v1beta/models/" + model + ":streamGenerateContent?alt=sse")
                            .header("x-goog-api-key", currentKey)
                            .contentType(MediaType.APPLICATION_JSON)
                            .bodyValue(request)
                            .retrieve()
                            .bodyToFlux(GeminiChatResponse.class)
                            .map(response -> {
                                if (response != null && response.candidates() != null && response.candidates().length > 0) {
                                    if (response.candidates()[0].content() != null && response.candidates()[0].content().parts() != null && response.candidates()[0].content().parts().length > 0) {
                                        return response.candidates()[0].content().parts()[0].text();
                                    }
                                }
                                return "";
                            });
                })
                .onErrorResume(e -> {
                    log.warn("Stream failed on current key. Triggering failover... Error: {}", e.getMessage());
                    // Pass the error downstream so the retry operator can catch it
                    return Mono.error(e);
                })
                // Retry based on the number of available fallback keys
                .retry(Math.max(0, apiKeys.size() - 1))
                .onErrorResume(e -> {
                    log.error("ALL Gemini API Keys failed during Streaming!");
                    return Flux.just(" [عذراً، يبدو أن هناك ضغطاً كبيراً على الخوادم. يرجى إعادة المحاولة لاحقاً.] ");
                });
    }

    private GeminiChatRequest createRequest(String prompt) {
        List<SafetySetting> safetySettings = List.of(
                new SafetySetting("HARM_CATEGORY_HARASSMENT", "BLOCK_NONE"),
                new SafetySetting("HARM_CATEGORY_HATE_SPEECH", "BLOCK_NONE"),
                new SafetySetting("HARM_CATEGORY_SEXUALLY_EXPLICIT", "BLOCK_NONE"),
                new SafetySetting("HARM_CATEGORY_DANGEROUS_CONTENT", "BLOCK_NONE")
        );
        return new GeminiChatRequest(
                new Content[]{new Content(new Part[]{new Part(prompt)})},
                safetySettings
        );
    }

    // DTOs
    public record GeminiChatRequest(Content[] contents, List<SafetySetting> safetySettings) {}
    public record SafetySetting(String category, String threshold) {}
    public record Content(Part[] parts) {}
    public record Part(String text) {}
    public record GeminiChatResponse(Candidate[] candidates) {
        public record Candidate(Content content) {}
    }
}