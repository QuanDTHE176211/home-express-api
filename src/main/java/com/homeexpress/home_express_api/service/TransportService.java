package com.homeexpress.home_express_api.service;

import com.homeexpress.home_express_api.entity.Notification;
import com.homeexpress.home_express_api.entity.Transport;
import com.homeexpress.home_express_api.entity.User;
import com.homeexpress.home_express_api.entity.VerificationStatus;
import com.homeexpress.home_express_api.exception.BadRequestException;
import com.homeexpress.home_express_api.exception.ResourceNotFoundException;
import com.homeexpress.home_express_api.repository.TransportRepository;
import com.homeexpress.home_express_api.repository.UserRepository;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;
import lombok.RequiredArgsConstructor;

@RequiredArgsConstructor
@Service
public class TransportService {

    private final TransportRepository transportRepository;

    private final UserRepository userRepository;
    
    private final NotificationService notificationService;

    public List<Transport> getAllTransports() {
        return transportRepository.findAll(Sort.by("createdAt").descending());
    }

    public Page<Transport> getTransportsByVerificationStatus(VerificationStatus status, int page, int size) {
        Pageable pageable = PageRequest.of(page, size, Sort.by("createdAt").descending());
        return transportRepository.findByVerificationStatus(status, pageable);
    }

    public Page<Transport> searchTransports(VerificationStatus status, String search, int page, int limit) {
        // Controller passes 1-based page, convert to 0-based
        int pageIndex = Math.max(0, page - 1);
        Pageable pageable = PageRequest.of(pageIndex, limit, Sort.by("createdAt").descending());
        return transportRepository.searchTransports(status, search, pageable);
    }

    public List<Transport> getTransportsByStatus(VerificationStatus status) {
        return transportRepository.findByVerificationStatus(status);
    }

    public Transport getTransportById(Long transportId) {
        return transportRepository.findById(transportId)
                .orElseThrow(() -> new ResourceNotFoundException("Transport", "id", transportId));
    }

    @Transactional
    public Transport verifyTransport(Long transportId, Long verifiedByUserId, String notes) {
        Transport transport = getTransportById(transportId);
        User verifier = userRepository.findById(verifiedByUserId)
                .orElseThrow(() -> new ResourceNotFoundException("User", "id", verifiedByUserId));

        transport.setVerificationStatus(VerificationStatus.APPROVED);
        transport.setVerifiedAt(LocalDateTime.now());
        transport.setVerifiedBy(verifier);
        transport.setVerificationNotes(notes);

        Transport savedTransport = transportRepository.save(transport);
        
        // Send approval notification
        notificationService.createNotification(
            transport.getUser().getUserId(),
            Notification.NotificationType.TRANSPORT_VERIFIED,
            "Tài khoản vận chuyển đã được phê duyệt",
            "Chúc mừng! Tài khoản công ty vận chuyển của bạn đã được xác minh và phê duyệt.",
            Notification.ReferenceType.TRANSPORT,
            transportId,
            Notification.Priority.HIGH
        );
        
        return savedTransport;
    }

    @Transactional
    public Transport rejectTransport(Long transportId, Long rejectedByUserId, String notes) {
        if (notes == null || notes.trim().isEmpty()) {
            throw new BadRequestException("Rejection reason is required");
        }

        Transport transport = getTransportById(transportId);
        User rejector = userRepository.findById(rejectedByUserId)
                .orElseThrow(() -> new ResourceNotFoundException("User", "id", rejectedByUserId));

        transport.setVerificationStatus(VerificationStatus.REJECTED);
        transport.setVerifiedAt(LocalDateTime.now());
        transport.setVerifiedBy(rejector);
        transport.setVerificationNotes(notes);

        Transport savedTransport = transportRepository.save(transport);
        
        // Send rejection notification with reason
        String message = "Tài khoản công ty vận chuyển của bạn đã bị từ chối. ";
        if (notes != null && !notes.isEmpty()) {
            message += "Lý do: " + notes + ". ";
        }
        message += "Vui lòng cập nhật thông tin và gửi lại yêu cầu xác minh.";
        
        notificationService.createNotification(
            transport.getUser().getUserId(),
            Notification.NotificationType.TRANSPORT_REJECTED,
            "Yêu cầu xác minh bị từ chối",
            message,
            Notification.ReferenceType.TRANSPORT,
            transportId,
            Notification.Priority.HIGH
        );
        
        return savedTransport;
    }
}

