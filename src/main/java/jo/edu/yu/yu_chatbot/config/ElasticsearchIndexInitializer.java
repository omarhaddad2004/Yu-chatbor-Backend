package jo.edu.yu.yu_chatbot.config;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.elasticsearch.client.RequestOptions;
import org.elasticsearch.client.RestHighLevelClient;
import org.elasticsearch.client.indices.CreateIndexRequest;
import org.elasticsearch.client.indices.GetIndexRequest;
import org.elasticsearch.common.xcontent.XContentType;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
@Slf4j
public class ElasticsearchIndexInitializer {

    private final RestHighLevelClient client;

    @Value("${gemini.embedding.max-dimensions:1024}")
    private int embeddingDim;

    @EventListener(ApplicationReadyEvent.class)
    public void ensureIndexExists() {
        String indexName = "chunk_vectors";

        try {
            boolean exists = client.indices().exists(new GetIndexRequest(indexName), RequestOptions.DEFAULT);

            if (exists) {
                log.info("Elasticsearch index '{}' already exists. Skipping creation.", indexName);
                return;
            }

            log.info("Index not found. Creating '{}' with dims={} and updated mapping...", indexName, embeddingDim);

            CreateIndexRequest request = new CreateIndexRequest(indexName);

            String mapping = """
                {
                  "mappings": {
                    "properties": {
                      "embedding": {
                        "type": "dense_vector",
                        "dims": %d 
                      },
                      "text": {
                        "type": "text"
                      },
                      "sourceUrl": {
                        "type": "keyword"
                      },
                      "documentTitle": {
                        "type": "text"
                      },
                      "summary": {
                        "type": "text"
                      },
                      "heading": {
                        "type": "text"
                      },
                      "keywords": {
                        "type": "text",
                        "boost": 2.0
                      },
                      "importance": {
                        "type": "float"
                      },
                      "faculty": {
                        "type": "keyword"
                      },
                      "contentType": {
                        "type": "keyword"
                      },
                      "chunkIndex": {
                        "type": "integer"
                      }
                    }
                  }
                }
                """.formatted(embeddingDim);

            request.source(mapping, XContentType.JSON);
            client.indices().create(request, RequestOptions.DEFAULT);
            log.info("Elasticsearch index '{}' created successfully with advanced mapping (dims={})", indexName, embeddingDim);

        } catch (Exception e) {
            log.error("Failed to initialize Elasticsearch index: {}", e.getMessage());
        }
    }
}