package jo.edu.yu.yu_chatbot.embedding;

import reactor.core.publisher.Flux;

public interface LlmClient {

    String generateAnswer(String prompt);

    Flux<String> streamAnswer(String prompt);

}