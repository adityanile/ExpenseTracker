# Architectural Decisions

## ADR-001: Replace Azure Content Understanding with Azure OpenAI `gpt-4.1-mini`
- **Date:** 2026-11-15
- **Status:** Accepted

### Context
The app extracts expense details from payment screenshots captured through the overlay flow.

The previous approach used Azure Content Understanding. In practice, it showed inconsistent extraction behavior across different payment apps, which reduced reliability of downstream expense processing.

### Decision
Use Azure OpenAI REST API with the deployed `gpt-4.1-mini` model for structured extraction instead of Azure Content Understanding.

### Rationale
- `gpt-4.1-mini` returns responses faster in this app flow.
- Extraction output is more consistent across multiple payment applications.
- Consistency is prioritized for accurate personal expense tracking.

### Trade-offs
- Token cost increased by roughly **3x** compared to the previous analyzer-based approach.
- Current cost is still manageable for personal use.

### Cost/Risk Mitigation
- Continue using a personal model instance to keep control over usage and billing.
- Use strict structured response format and constrained prompt/schema to reduce unnecessary output tokens.

### Consequences
- Better end-user reliability and less app-to-app variance in extracted fields.
- Higher but acceptable operational cost profile for the current personal-use scope.

## ADR-002: Quick Settings Tile controls Accessibility Service state for payment compatibility
- **Date:** 2026-11-15
- **Status:** Accepted

### Context
Several payment apps (for example, Google Pay) can block transaction confirmation when a third-party Accessibility Service is active.

The previous toggle only hid the overlay bubble, but the Accessibility Service process remained enabled. This did not satisfy anti-fraud checks in many payment flows.

### Decision
Implement an Android Quick Settings tile as the primary runtime control for the capture feature:
- When tile is tapped in active state, disable the capture service by sending an app-local broadcast and calling `disableSelf()` from `PaymentOverlayAccessibilityService`.
- Persist overlay preference state in shared preferences (`OverlayTogglePreferences`) so service behavior remains consistent.
- When tile is tapped in inactive state, open Accessibility Settings to let the user explicitly re-enable the service.

### Rationale
- Payment compatibility depends on service state, not only overlay visibility.
- Quick Settings provides the fastest user path before/after checkout.
- Re-enable flow through system settings keeps user consent explicit and platform-compliant.

### Trade-offs
- Re-enabling is not one-tap due to Android security model for Accessibility Services.
- Users need to learn a small workflow: disable before payment, re-enable after payment.

### Consequences
- Fewer payment blocks caused by the app during sensitive checkout flows.
- Slightly higher UX friction after payment when restoring capture mode.
- Architecture now separates "overlay visibility" preference from actual "service enabled" system state.
