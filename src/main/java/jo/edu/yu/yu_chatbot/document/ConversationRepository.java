package jo.edu.yu.yu_chatbot.document;

import jo.edu.yu.yu_chatbot.chat.Conversation;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public interface ConversationRepository extends JpaRepository<Conversation, UUID> {

    @Query("SELECT c FROM Conversation c WHERE c.user.id = :userId ORDER BY c.updatedAt DESC")
    List<Conversation> findByUserIdExplicit(@Param("userId") Long userId);

    @Query("SELECT c FROM Conversation c LEFT JOIN FETCH c.user WHERE c.id = :id")
    Optional<Conversation> findByIdWithUser(@Param("id") UUID id);
}