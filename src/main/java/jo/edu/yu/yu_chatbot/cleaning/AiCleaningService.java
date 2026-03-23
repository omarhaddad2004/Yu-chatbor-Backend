package jo.edu.yu.yu_chatbot.cleaning;

import jo.edu.yu.yu_chatbot.embedding.LlmClient;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
public class AiCleaningService {

    private final LlmClient llmClient;

    @Value("${cleaning.ai-enabled:false}")
    private boolean aiEnabled;

    public String cleanWithAi(String original) {
        if (!aiEnabled || original == null || original.isBlank()) {
            return original;
        }

        String prompt = """
                You receive text copied from Yarmouk University official content.
                Your task:
                - Fix broken sentences and spacing.
                - Merge lines into readable paragraphs.
                - Remove obvious noise (duplicate headers/footers, page numbers) if clearly not content.
                - DO NOT change facts, numbers, names, or meaning.
                - DO NOT summarize or shorten too much. Keep almost all content.
                Return ONLY the cleaned text.
                                
                Text:
                """ + original;

        return llmClient.generateAnswer(prompt);
    }
}
