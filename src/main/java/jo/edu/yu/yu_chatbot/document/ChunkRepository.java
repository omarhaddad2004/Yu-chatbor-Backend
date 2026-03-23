package jo.edu.yu.yu_chatbot.document;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface ChunkRepository extends JpaRepository<ChunkEntity, Long> {

    @Query("SELECT c FROM ChunkEntity c JOIN FETCH c.document WHERE c.id = :id")
    Optional<ChunkEntity> findByIdWithDocument(@Param("id") Long id);

    @Query("SELECT c FROM ChunkEntity c JOIN FETCH c.document " +
            "WHERE c.document.id = :docId AND c.chunkIndex BETWEEN :start AND :end " +
            "ORDER BY c.chunkIndex ASC")
    List<ChunkEntity> findChunksInWindow(
            @Param("docId") Long docId,
            @Param("start") int start,
            @Param("end") int end
    );

    // ✅ إضافة جديدة
    List<ChunkEntity> findByDocumentId(Long documentId);
}