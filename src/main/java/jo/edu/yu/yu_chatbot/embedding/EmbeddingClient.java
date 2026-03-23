package jo.edu.yu.yu_chatbot.embedding;

import java.util.List;

public interface EmbeddingClient {
    float[] embed(String text);

    List<float[]> batchEmbed(List<String> texts);
}
