package com.homeexpress.home_express_api.repository;

import com.homeexpress.home_express_api.entity.Transport;
import com.homeexpress.home_express_api.entity.VerificationStatus;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

@Repository
public interface TransportRepository extends JpaRepository<Transport, Long> {
    
    // tim theo business license
    Optional<Transport> findByBusinessLicenseNumber(String licenseNumber);
    
    // tim theo verification status
    List<Transport> findByVerificationStatus(VerificationStatus status);
    Page<Transport> findByVerificationStatus(VerificationStatus status, Pageable pageable);

    @Query("SELECT t FROM Transport t LEFT JOIN t.user u WHERE " +
           "(:status IS NULL OR t.verificationStatus = :status) AND " +
           "(:search IS NULL OR :search = '' OR " +
           "LOWER(t.companyName) LIKE LOWER(CONCAT('%', :search, '%')) OR " +
           "LOWER(t.businessLicenseNumber) LIKE LOWER(CONCAT('%', :search, '%')) OR " +
           "LOWER(u.email) LIKE LOWER(CONCAT('%', :search, '%')))")
    Page<Transport> searchTransports(@Param("status") VerificationStatus status, @Param("search") String search, Pageable pageable);
    
    // check ton tai
    boolean existsByBusinessLicenseNumber(String licenseNumber);
    boolean existsByTaxCode(String taxCode);
    boolean existsByNationalIdNumber(String nationalId);
    
    // tim transports da approved, sap xep theo rating
    List<Transport> findByVerificationStatusOrderByAverageRatingDesc(VerificationStatus status);

    long countByVerificationStatus(VerificationStatus status);
    
    // tim transport theo city
    List<Transport> findByCity(String city);
    
    // tim transport theo user ID
    Optional<Transport> findByUser_UserId(Long userId);
}
