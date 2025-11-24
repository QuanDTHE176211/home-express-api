# Test Plan for Home Moving Service Application

## 1. SCOPE & OBJECTIVES

**Primary Goals:**
- Achieve full coverage of the main user flows across three roles (Customer, Provider, Admin): booking creation → AI-powered intake → quotation → transport selection → payment (deposit + 70% remaining) → settlement → payout
- Validate all external integrations: OpenAI API, Goong Maps API, SMTP email service, payout gateway (stubbed), Redis cache, MySQL database
- Verify security and authorization: role-based access control (RBAC), data privacy enforcement, JWT authentication, CORS configuration
- Ensure non-functional requirements: query performance for critical endpoints, SSE (Server-Sent Events) connection stability, payment idempotency

## 2. TEST LAYER MATRIX

**Unit Tests** (Pure logic, no I/O dependencies):
- `PaymentService`: state transitions, net amount calculations, commission calculations
- `SettlementService`: eligibility rules, net amount calculations
- `IntakeTextParsingService`: heuristic parsing algorithms
- `IntakeAIParsingService`: AI response refinement logic
- `CommissionService`: fee calculation formulas
- Distance calculation: Haversine formula fallback when API unavailable

**Integration Tests** (Spring context with real DB/Redis via Testcontainers):
- `@DataJpaTest`: Repository layer queries, aggregations, and sum calculations
- `@SpringBootTest`: Service layer with real DB + Redis; use MockWebServer/WireMock to stub OpenAI, Goong, SMTP, and payout gateway
- `@WebMvcTest`: Request validation, authorization filters, basic security checks

**Contract/API Tests:**
- Validate JSON schema compliance, HTTP status codes (2xx, 4xx, 5xx)
- Verify SSE payload structure and retry mechanisms

**End-to-End/Smoke Tests** (REST API):
- Complete user journey scenarios for all three roles (Customer, Provider, Admin)

**Non-Functional Spot Checks:**
- Performance testing for heavy endpoints: intake parsing, distance matrix calculations, booking list queries
- SSE connection stability and reconnection handling

## 3. FUNCTIONAL TEST SCENARIOS

### Intake AI Module:
- **Happy path**: OpenAI returns valid JSON schema, correctly maps to `ParsedItem` domain objects
- **Fallback scenarios**: Missing API key / timeout / 400 error → fallback to heuristic parsing; test Redis cache hit/miss
- **Input variations**: Vietnamese text with/without diacritics, quantity parsing, disassembly flags, fragile item flags

### Maps/Distance Module:
- Autocomplete, place details, geocoding with MockWebServer responses
- Distance matrix API success + Haversine fallback when API fails

### OTP/Email Module:
- OTP generation and verification (valid cases)
- Invalid OTP, expired OTP, already-used OTP scenarios
- SMTP send success/authentication failure

### Payment Module:
- Deposit payment initialization (CASH/BANK_TRANSFER payment methods)
- Idempotency key enforcement (prevent duplicate payments)
- Payment confirmation flow
- Payment status mapping for frontend display
- SSE event emission on payment state changes
- Notification persistence to database
- Remaining payment + tip handling
- Double confirmation protection (idempotency)
- Reject confirmation when payment status is invalid

### Settlement Module:
- **Eligibility checks**: Full payment received, no incidents recorded, booking status valid
- **Breakdown calculations**: deposit + remaining + tip - gateway fees (currently 0), collection mode (cash/online/mixed)
- Set status to `READY` when all conditions met
- Keep status as `PENDING` or `ON_HOLD` when payment incomplete or incidents exist

### Payout Module:
- Batch creation for transport providers
- Map settlements to payout line items
- **Gateway stub modes**:
  - `SUCCESS`: Complete payout successfully
  - `FAIL_RETRYABLE`: Set settlement to `ON_HOLD` or keep `READY` for retry
  - `FAIL_FINAL`: Rollback `payoutId`, set settlement to `ON_HOLD`, reverse wallet debit
- Wallet debit reversal on failure

### SSE (Server-Sent Events) Module:
- Connection establishment and keep-alive
- Receive events for booking status changes, quotations, payments
- Handle disconnection/timeout/reconnection

### Authentication & Authorization:
- **Customer role**: Can only view and confirm their own bookings
- **Transport role**: Can only access their assigned jobs
- **Admin role**: Full access to all resources
- CORS and JWT filter: Reject requests without token or with expired tokens

### File Upload Module:
- Accept valid file types for evidence/document scans
- Enforce file size limits
- Reject invalid file types

### Notifications Module:
- User preference toggle (enable/disable notifications)
- Action URL generation based on user role

## 4. TEST DATA & FIXTURES

**Testcontainers Setup:**
- MySQL and Redis containers
- Schema initialization via Flyway migrations in test environment

**Seed Data:**
- 3 users with different roles (Customer, Transport Provider, Admin)
- Sample bookings in various states: DRAFT, CONFIRMED, COMPLETED
- 1 contract record
- Sample payments: PENDING, COMPLETED
- Sample settlements: PENDING, READY, ON_HOLD
- Sample OTP records

**Mock Payloads:**
- OpenAI: Valid and invalid JSON schema responses
- Goong API: Successful and error responses
- SMTP: Success and failure responses
- Payout gateway: SUCCESS, FAIL_RETRYABLE, FAIL_FINAL stub modes

## 5. TOOLS & EXECUTION

**Testing Frameworks:**
- JUnit 5 + Spring Boot Test
- Testcontainers (MySQL/Redis)
- MockMvc/WebTestClient for REST API testing
- MockWebServer/WireMock for external service mocking
- Awaitility for SSE and async operation testing

**CI/CD Pipeline:**
- Run unit and integration tests in parallel
- Run selective E2E smoke tests (due to execution time constraints)

## 6. COMPLETION CRITERIA & REPORTING

**Coverage Requirements:**
- Each module must have ≥1 unit test for core logic
- Main flows must have end-to-end integration tests (booking → payment → settlement)
- All external dependencies must have mock tests with at least one error case per integration
- SSE must have connection and payload validation tests
- Authorization must have deny/allow tests for all 3 roles

**Quality Gates:**
- No blocking regressions
- Core module coverage (payment/settlement/intake) achieves acceptable branch coverage (target: 60-70% for critical modules)

## 7. RISKS & MITIGATION

**Risk: OpenAI schema changes**
- Mitigation: Test with fixed mock schemas, add tests for schema parsing errors

**Risk: Payment/settlement synchronization issues**
- Mitigation: Verify idempotency enforcement, test invalid state transitions

**Risk: Payout rollback failures**
- Mitigation: Test FAIL_RETRYABLE and FAIL_FINAL scenarios to ensure no money loss or stuck states
