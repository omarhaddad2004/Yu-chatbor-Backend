package jo.edu.yu.yu_chatbot.config;

import org.elasticsearch.client.RestClient;
import org.elasticsearch.client.RestHighLevelClient;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.apache.http.HttpHost;

@Configuration
public class ElasticSearchConfig {

    @Bean
    public RestHighLevelClient client() {
        return new RestHighLevelClient(
                RestClient.builder(new HttpHost("localhost", 9200, "http")));
    }
}
