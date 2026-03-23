package jo.edu.yu.yu_chatbot.Reindex;

import jo.edu.yu.yu_chatbot.document.ChunkEntity;
import jo.edu.yu.yu_chatbot.document.ChunkRepository;
import jo.edu.yu.yu_chatbot.embedding.EmbeddingClient;
import jo.edu.yu.yu_chatbot.rag.VectorStoreService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.util.List;

@Service
@RequiredArgsConstructor
@Slf4j
public class ReindexService {

    private final ChunkRepository chunkRepository;
    private final EmbeddingClient embeddingClient;
    private final VectorStoreService vectorStoreService;

    public void reindexAllChunks() throws IOException {
        List<ChunkEntity> chunks = chunkRepository.findAll();
        log.info("ReindexService: reindexing {} chunks", chunks.size());

        for (ChunkEntity chunk : chunks) {
            String text = chunk.getText();
            if (text == null || text.isBlank()) {
                continue;
            }

            float[] embedding = embeddingClient.embed(text);
            vectorStoreService.upsertEmbedding(chunk.getId(), embedding);
        }

        log.info("ReindexService: finished reindexing");
    }
}
