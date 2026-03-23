package jo.edu.yu.yu_chatbot.document;

import jo.edu.yu.yu_chatbot.chat.Message;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.UUID;

public interface MessageRepository extends JpaRepository<Message, Long> {

    List<Message> findByConversation_IdOrderByCreatedAtAsc(UUID conversationId);
}