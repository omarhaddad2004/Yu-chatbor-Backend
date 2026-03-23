package jo.edu.yu.yu_chatbot.rag;

import jo.edu.yu.yu_chatbot.embedding.EmbeddingClient;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.util.List;

@Service
@RequiredArgsConstructor
@Slf4j
public class SemanticSearchService {

    private final EmbeddingClient embeddingClient;
    private final VectorStoreService vectorStoreService;

    /**
     * OPTIMIZED: Added caching for search results
     * Same question = instant response (no Elasticsearch call)
     */
    @Cacheable(
            value = "searchResults",
            key = "#question.toLowerCase().trim() + '-' + #topK"
    )
    public List<RetrievedChunk> search(String question, int topK) throws IOException {

        boolean containsArabic = question != null &&
                question.codePoints().anyMatch(c -> c >= 0x0600 && c <= 0x06FF);

        boolean looksLikePersonQuery = containsArabic &&
                (question.contains("د.") ||
                        question.contains("دكتور") ||
                        question.contains("الدكتور") ||
                        question.length() < 50);

        if (looksLikePersonQuery) {
            log.info("SemanticSearch: using TEXT search for Arabic name query: '{}'", question);
            List<RetrievedChunk> textResults = vectorStoreService.searchByText(question, topK);
            if (!textResults.isEmpty()) {
                return textResults;
            }
            log.info("SemanticSearch: no good TEXT results, falling back to embedding search.");
        }

        float[] embedding = embeddingClient.embed(question);

        log.info("SemanticSearch: embedding length={} for question='{}'",
                embedding.length, question);

        if (embedding.length == 0) {
            log.warn("SemanticSearch: empty embedding, returning no chunks");
            return List.of();
        }

        List<RetrievedChunk> chunks = vectorStoreService.searchSimilar(question, embedding, topK);

        log.info("SemanticSearch: got {} chunks from vector search", chunks.size());

        return chunks;
    }
}