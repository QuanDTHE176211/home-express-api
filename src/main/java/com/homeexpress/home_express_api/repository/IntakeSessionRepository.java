package com.homeexpress.home_express_api.repository;

import com.homeexpress.home_express_api.entity.IntakeSession;
import com.homeexpress.home_express_api.entity.User;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.stereotype.Repository;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

@Repository
public interface IntakeSessionRepository extends JpaRepository<IntakeSession, String> {
    
    Optional<IntakeSession> findBySessionIdAndStatus(String sessionId, String status);
    
    List<IntakeSession> findByUserAndStatus(User user, String status);
    
    List<IntakeSession> findByUser_UserIdAndStatus(Long userId, String status);
    
    @Query("SELECT s FROM IntakeSession s WHERE s.expiresAt < :now AND s.status = 'active'")
    List<IntakeSession> findExpiredSessions(LocalDateTime now);
    
    @Modifying
    @Query("UPDATE IntakeSession s SET s.status = 'expired' WHERE s.expiresAt < :now AND s.status = 'active'")
    int expireOldSessions(LocalDateTime now);
    
    @Modifying
    @Query("DELETE FROM IntakeSession s WHERE s.status = 'expired' AND s.updatedAt < :before")
    int deleteExpiredSessions(LocalDateTime before);

    long countByStatus(String status);

    @Query("SELECT AVG(s.averageConfidence) FROM IntakeSession s WHERE s.status = :status")
    Double getAverageConfidenceByStatus(String status);

    @Query("SELECT MIN(s.createdAt) FROM IntakeSession s WHERE s.status = :status")
    LocalDateTime getOldestCreatedAtByStatus(String status);
}
