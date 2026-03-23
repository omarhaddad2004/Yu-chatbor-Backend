package jo.edu.yu.yu_chatbot.cleaning;

import org.springframework.stereotype.Service;

@Service
public class TextCleaningService {

    public String basicClean(String text) {
        if (text == null) return "";

        // Normalize line breaks
        String cleaned = text.replace("\r\n", "\n")
                .replace("\r", "\n");

        cleaned = cleaned.replaceAll("[ \\t]+", " ");

        // Collapse multiple newlines
        cleaned = cleaned.replaceAll("\\n{3,}", "\n\n");

        // Trim
        cleaned = cleaned.trim();

        return cleaned;
    }
}
