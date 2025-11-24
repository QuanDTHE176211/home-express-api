package com.homeexpress.home_express_api.repository;

import java.time.LocalDateTime;
import java.util.Optional;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.stereotype.Repository;

import com.homeexpress.home_express_api.entity.User;
import com.homeexpress.home_express_api.entity.UserRole;

@Repository
public interface UserRepository extends JpaRepository<User, Long>, JpaSpecificationExecutor<User> {

    @EntityGraph(attributePaths = {"customer", "transport", "manager"})
    Page<User> findAll(Specification<User> spec, Pageable pageable);

    Optional<User> findByEmail(String email);

    Optional<User> findByPhone(String phone);

    boolean existsByEmail(String email);

    boolean existsByPhone(String phone);

    long countByRole(UserRole role);

    long countByIsActive(boolean isActive);

    long countByIsVerified(boolean isVerified);

    long countByCreatedAtBetween(LocalDateTime startDate, LocalDateTime endDate);

    Page<User> findByRole(UserRole role, Pageable pageable);
}
