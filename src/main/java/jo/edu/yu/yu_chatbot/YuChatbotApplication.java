package jo.edu.yu.yu_chatbot;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication
@EnableScheduling
public class YuChatbotApplication {

    public static void main(String[] args) {
        SpringApplication.run(YuChatbotApplication.class, args);
    }
}
