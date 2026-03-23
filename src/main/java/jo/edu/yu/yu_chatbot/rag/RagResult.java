package jo.edu.yu.yu_chatbot.rag;

import lombok.Builder;
import lombok.Data;

import java.util.List;

@Data
@Builder
public class RagResult {
    private String answer;
    private List<RetrievedChunk> sources;
}
