package jo.edu.yu.yu_chatbot.document;

import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * Component responsible for splitting large texts into smaller, manageable chunks
 * optimized for vector embedding and retrieval. Implements overlapping to preserve context.
 */
@Component
public class TextChunker {

    private static final int CHUNK_SIZE = 1500;
    private static final int CHUNK_OVERLAP = 200;
    private static final int MAX_TEXT_LENGTH = 500_000;

    public List<String> chunk(String text) {
        if (text == null || text.trim().isEmpty()) {
            return List.of();
        }

        String safeText = text.trim();
        if (safeText.length() > MAX_TEXT_LENGTH) {
            safeText = safeText.substring(0, MAX_TEXT_LENGTH);
        }

        List<String> chunks = new ArrayList<>();
        int start = 0;
        int textLength = safeText.length();

        while (start < textLength) {
            int end = Math.min(start + CHUNK_SIZE, textLength);

            // Attempt to find a natural break point (newline or space) near the end of the chunk
            // to avoid splitting sentences, paragraphs, or table rows abruptly.
            if (end < textLength) {
                int lastNewline = safeText.lastIndexOf('\n', end);
                int lastSpace = safeText.lastIndexOf(' ', end);

                // If a newline is found within the last 300 characters of the chunk, split there
                if (lastNewline > start + (CHUNK_SIZE - 300)) {
                    end = lastNewline;
                }
                // Otherwise, fallback to the last space within the last 100 characters
                else if (lastSpace > start + (CHUNK_SIZE - 100)) {
                    end = lastSpace;
                }
            }

            chunks.add(safeText.substring(start, end).trim());

            if (end >= textLength) {
                break;
            }

            // Step back to create the overlap for the next chunk, ensuring context continuity
            start = end - CHUNK_OVERLAP;
        }

        return chunks;
    }
}