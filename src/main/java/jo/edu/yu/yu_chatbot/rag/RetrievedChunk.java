package jo.edu.yu.yu_chatbot.rag;

import lombok.Builder;
import lombok.Data;

@Data
@Builder
public class RetrievedChunk {
    private Long chunkId;
    private String text;
    private String summary;
    private String heading;
    private String sourceUrl;
    private String documentTitle;
    private double score;
    private Float importance;
}