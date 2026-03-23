package jo.edu.yu.yu_chatbot.rag;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.stream.Collectors;

/**
 * Component responsible for filtering retrieved chunks to ensure they meet
 * strict domain constraints and minimum confidence score thresholds.
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class ScopeGate {

    @Value("${rag.allowed-domain:}")
    private String allowedDomain;

    @Value("${rag.min-score:0.0}")
    private double minScore;

    /**
     * Filters a list of retrieved chunks based on domain and score rules.
     */
    public List<RetrievedChunk> filter(List<RetrievedChunk> chunks) {
        if (chunks == null || chunks.isEmpty()) {
            return List.of();
        }

        return chunks.stream()
                .filter(this::isDomainAllowed)
                .filter(this::isScoreHighEnough)
                .collect(Collectors.toList());
    }

    private boolean isDomainAllowed(RetrievedChunk chunk) {
        if (allowedDomain == null || allowedDomain.isBlank()) {
            return true;
        }

        String url = chunk.getSourceUrl();
        if (url == null) {
            return false;
        }

        // Direct string matching is utilized here for optimal performance
        // avoiding the overhead of URI object instantiation and parsing.
        return url.toLowerCase().contains(allowedDomain.toLowerCase());
    }

    private boolean isScoreHighEnough(RetrievedChunk chunk) {
        return chunk.getScore() >= minScore;
    }
}