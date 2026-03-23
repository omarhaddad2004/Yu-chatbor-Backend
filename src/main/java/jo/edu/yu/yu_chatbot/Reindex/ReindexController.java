package jo.edu.yu.yu_chatbot.Reindex;


import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.io.IOException;

@RestController
@RequestMapping("/api/index")
@RequiredArgsConstructor
public class ReindexController {

    private final ReindexService reindexService;

    @PostMapping("/reindex")
    public ResponseEntity<String> reindex() throws IOException {
        reindexService.reindexAllChunks();
        return ResponseEntity.ok("Reindex completed");
    }
}