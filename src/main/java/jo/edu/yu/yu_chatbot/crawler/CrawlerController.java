package jo.edu.yu.yu_chatbot.crawler;

import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import java.util.List;

@RestController
@RequestMapping("/api/crawler")
@RequiredArgsConstructor
public class CrawlerController {

    private final WebsiteCrawlerRunner crawlerRunner;

    @PostMapping("/run")                 // POST /api/crawler/run
    public ResponseEntity<String> runCrawler(@RequestBody List<String> urls) {
        new Thread(() -> {
            crawlerRunner.ingestUrls(urls);
        }).start();

        return ResponseEntity.ok("Crawler started in background! Check your IntelliJ logs to see progress.");
    }
}
