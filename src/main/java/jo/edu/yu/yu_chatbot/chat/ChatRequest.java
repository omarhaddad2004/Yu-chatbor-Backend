package jo.edu.yu.yu_chatbot.chat;

import jakarta.validation.constraints.NotBlank;
import lombok.Data;

@Data
public class ChatRequest {
    @NotBlank
    private String question;
}
