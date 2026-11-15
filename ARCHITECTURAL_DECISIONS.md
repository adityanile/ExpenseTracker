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
