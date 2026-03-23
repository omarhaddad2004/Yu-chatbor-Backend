package jo.edu.yu.yu_chatbot.crawler;

import lombok.Builder;
import lombok.Data;

import java.util.Set;

@Data
@Builder
public class CrawledPage {
    private String url;
    private String title;
    private String content;
    private String contentType;
    private Set<String> links;

}
