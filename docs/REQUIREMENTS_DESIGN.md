# Home Express Platform - Requirements & Design (v1.0)

This document reflects the implementation status in the codebase as of the latest commit. It is aligned with PROJECT_TRACKING.csv (iter1–iter3).

## 1. Context & Objectives
- Digitize end-to-end home moving: AI intake, quotations, negotiation, contract, payment (deposit + remaining), execution tracking, settlement, wallet, and payout.
- User groups: Customers, Transport providers, Managers. Managers oversee verification, compliance, operations, finance, and platform configuration.
- Tech stack: Spring Boot REST API, MySQL (Flyway), Redis, JWT auth, SMTP (JavaMailSender), OpenAI for intake parsing, Goong Maps for geocoding/distance (Haversine fallback), PayOS and manual bank transfer for payments, SSE for real-time events.

## 2. Personas & Roles
- Guest: can register, request OTP, and browse locations/maps.
- Customer (USER role CUSTOMER): creates/manages bookings, reviews quotations, negotiates, accepts, signs, pays, tracks, confirms completion, raises incidents/disputes.
- Transport (USER role TRANSPORT): completes KYC/KYB, configures rate cards, manages vehicles, quotes/negotiates, executes jobs, views settlements/wallet, requests payouts, manages settings.
- Manager/Admin (USER role MANAGER): transport verification, booking oversight and timelines, pricing/catalog, finance ops (settlement/payout), intake QA, platform dashboard and settings.

## 3. Implementation Summary by Iteration
- Iteration 1
  - Auth (register/login/OTP/reset), profile, locations, maps.
  - Booking create/get/update; basic transport KYC data structures.
- Iteration 2
  - AI Intake (OpenAI + heuristic fallback); customer SSE; notifications.
  - Quotation workflow (list/detail, accept via stored proc, counter-offer); contract creation.
  - Payments (deposit initiation, remaining + tip for CASH/BANK_TRANSFER); evidence upload.
  - Transport modules: rate card, vehicles, dashboard quoting; estimation service for customers.
  - Admin: transport verification, intake session review with SSE logs, booking timeline view, category management.
- Iteration 3
  - Finance: settlements, wallet with ledger, payouts (transport + admin approval), commission calculation.
  - Admin dashboards and platform stats.
  - Analytics/Reports: partial (category performance, earnings stats); more pending.
  - Review moderation: frontend UI and client hooks present; backend moderation endpoints TBD.

## 4. Core Business Rules (as implemented)
- BR-AU-001: OTPs are 6 digits, expire in 5 minutes; rate-limited. Stored in otp_codes. Email delivery via EmailService(JavaMailSender). (OtpService, EmailService)
- BR-AC-001: JWT-based RBAC via SecurityConfig. Public: /api/v1/auth/**, /api/v1/locations/**, /api/v1/map/**, /api/payments/webhook/**, /api/v1/estimation/**, /uploads/**, Swagger.
- BR-BK-001: Booking status transitions are validated; each change logged (booking_status_history) with actor and reason. SSE emitted to customer on changes. (BookingService, CustomerEventService)
- BR-BK-002: Distance via Goong; fallback Haversine when needed. (GoongMapService, MapService)
- BR-AI-001: Intake sessions store AI/heuristic results; admin can QA and rerun (SSE logs). (IntakeController, AdminIntakeSessionController/Service)
- BR-QT-001: Only READY_TO_QUOTE transports (derived from valid rate cards) can quote. (RateCardService)
- BR-QT-002: Quotations have validity and can expire (scheduled). Acceptance runs stored proc sp_accept_quotation. (QuotationService, QuotationRepository)
- BR-NT-001: Counter-offers with expiry; only pending and non-expired can be actioned; SSE notifications to customer. (CounterOfferService)
- BR-CT-001: Contract numbers are date-prefixed with sequence (e.g., CNT-YYYYMMDD-0001). Deposit requirement computed from constant DEFAULT_CONTRACT_DEPOSIT_PERCENTAGE. (ContractService, BookingConstants)
- BR-PM-001: Payments support CASH and BANK_TRANSFER (CustomerPaymentController) and PayOS link for deposit (PaymentController + PayOSService). Deposit percentage in PaymentService defaults to 30% (config), while ContractService uses its own constant—alignment pending.
- BR-ST-001: Settlement computes platform fee via CommissionService (default 5%); net_to_transport = total_collected - gateway_fee - platform_fee + adjustments. (SettlementService)
- BR-ST-002: Settlement lifecycle: PENDING → READY → IN_PAYOUT/ON_HOLD → PAID/CANCELLED. Wallet credited on READY; payout debits wallet; rollback on failures. (SettlementService, WalletService, PayoutService)
- BR-WL-001: Wallet transactions immutable with running balance and reference types; integrity recalculation available. (WalletService)
- BR-AU-002: Audit log for critical actions available via AuditController/AuditService.

## 5. Functional Scope (by actor)
### Guest / Customer
- Auth: register/login/OTP/reset. (AuthController, OtpService)
- Profile: view/update, change password, settings (notification preferences). (UserController, CustomerSettingsController, NotificationPreferenceService)
- Bookings: create/update/get; items view; estimates; cancel with guards; confirm completion. (BookingController, BookingItemController, BookingEstimateService)
- Intake: AI/heuristic text parsing and session handling. (IntakeController)
- Quotations: list/detail; accept; counter-offer respond. (BookingController, QuotationController, CustomerQuotationController)
- Contract: preview/sign. (ContractController)
- Payments: deposit init, remaining + tip; PayOS deposit link; status polling; bank info. (CustomerPaymentController, PaymentController, PaymentService)
- Evidence upload for bookings; notifications; SSE booking events. (BookingController, NotificationController, CustomerSSEController)
- Disputes & incidents: create/list/get/update (manager updates); message thread for disputes. (CustomerDisputeController, IncidentController)

### Transport Provider
- KYC/KYB profile, verification by admin. (AdminTransportController)
- Rate cards CRUD and readiness; vehicle CRUD; settings. (TransportRateCardController, RateCardController, VehicleController, TransportSettingsController)
- Quoting workflow: submit quotations; dashboard stats; recent quotations. (TransportDashboardController, QuotationService)
- Operations: accept/execute jobs; start/complete job with evidence/notes. (TransportJobController)
- Finance: settlements view; wallet and transactions; payout request/export. (TransportSettlementController, WalletService, TransportPayoutController)
- SSE: minimal endpoint for transport events. (TransportEventController)

### Manager / Admin
- Transport verification/ready-to-quote decisions. (AdminTransportController)
- Booking oversight timeline. (AdminBookingController)
- Intake session QA/reprocess with SSE logs. (AdminIntakeSessionController)
- Catalog/pricing/category management. (CategoryManagementController, CategoryPricingController)
- Finance ops: settlements review/queue; payouts create/list. (AdminSettlementController, AdminPayoutController)
- Platform dashboard and user admin. (AdminDashboardController, PlatformController, AdminUsersController via UserService)

## 6. System Design Notes
- API style: REST/JSON, JWT bearer. CORS configured; OPTIONS permitted. Public endpoints: auth, locations, map, estimation, uploads, payments webhook, Swagger.
- Persistence: MySQL with Flyway. Redis used for sessions/intake/aux caches. Testcontainers configured in tests.
- Integrations
  - OpenAI: IntakeAIParsingService with graceful fallback to IntakeTextParsingService.
  - Goong Maps: GoongMapService for autocomplete/details/reverse/distance matrix; DTO mapping and VN code resolution.
  - SMTP: EmailService using JavaMailSender for OTP and password flows.
  - PayOS: PayOSService for deposit links and webhook verification; return/cancel URLs; amount floor enforced.
- Scheduling: quotation expiry cleanup; intake/session cleanup; product model cleanup at 03:00; login attempts/session cleanup via config cron; finance batch scheduler present.
- Observability: audit_log for actions; notifications with user preferences and quiet hours; SSE streams for customers and admin intake QA.

## 7. Data Model Snapshot (key tables)
- Identity: users, customers, managers; user_tokens, user_sessions, login_attempts, otp_codes.
- Transport: transports, vehicles, transport_documents, transport_settings.
- Catalog/Pricing: categories, category_pricing, rate_cards, rate_card_items, additional_services, product_models.
- Booking Core: bookings, booking_items, booking_status_history, booking_timeline_events, booking_audit_log, contracts, counter_offers, quotations.
- Evidence & Ops: evidence, incidents, disputes, dispute_messages.
- Finance: payments, booking_settlements, transport_wallets, transport_wallet_transactions, transport_payouts, transport_payout_items, commission_rules.
- Reference: vn_provinces, vn_districts, vn_wards, vn_banks.
- Platform: audit_log, notifications, notification_preferences, admin_settings.

## 8. API Surface Overview (by module)
- Auth: /api/v1/auth/** (register, login, otp, reset).
- Locations: /api/v1/locations/**; Map: /api/v1/map/**.
- Estimation: /api/v1/estimation/auto.
- Booking: /api/v1/bookings (POST/GET/PUT), /{id}/quotations, /{id}/estimates, /{id}/evidence, /{id}/confirm-completion; Items: /api/v1/bookings/{id}/items (GET).
- Quotations: /api/v1/quotations (CRUD, /{id}/accept); Customer counter-offers: /api/v1/customer/counter-offers/{id}/respond.
- Customer payments: /api/v1/customer/payments/** (deposit/remaining/status/summary); Payment webhook: /api/payments/webhook/payos.
- Customer disputes: /api/v1/customer/bookings/{id}/disputes; messages: /api/v1/customer/disputes/{id}/messages.
- Notifications: /api/v1/notifications (list/detail/read/delete); preferences update.
- Transport: /api/v1/transport/** (dashboard stats, quotations submit/list, rate-cards, jobs start/complete, settlements, payouts request, settings).
- Admin: /api/v1/admin/transports (verify/search), /admin/bookings/{id}/timeline, /admin/settlements/**, /admin/payouts/**, /admin/sessions/**, /admin/categories, /admin/dashboard/stats, /api/v1/admin/platform/**.
- SSE: /api/v1/customer/bookings/{id}/events; /api/v1/transport/events; /api/v1/admin/sessions/{id}/events.

## 9. End-to-End Flow (happy path)
1) Customer creates booking (+items via intake) →
2) Transports submit quotations → Customer negotiates/accepts →
3) Contract auto-created and signed → Deposit initiated (PayOS or bank/cash) →
4) Transport executes job (status updates via SSE/evidence) →
5) Remaining payment (+tip) → Customer confirms completion →
6) Settlement READY → Wallet credited → Payout requested/approved → Paid.

## 10. Gaps / Open Items
- Align deposit percentage between ContractService (DEFAULT_CONTRACT_DEPOSIT_PERCENTAGE; comments indicate 50%) and PaymentService (config default 30%).
- Reviews: Frontend submission dialog and admin moderation UI exist; backend endpoints for creating/moderating reviews are not present yet.
- Analytics/Reports: extend beyond current category performance and earnings stats.
- Exception queue and failed workflow inspection: planned, not implemented.
- Public API documentation: generate per-controller schemas and publish Postman/Insomnia collection.
