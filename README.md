# ExpenseTracker

ExpenseTracker is an Android app that removes manual expense entry from UPI/payment receipts.

## 🚀 USP (Why this app is different)
You **do not need to manually type expenses**.
You **do not need to open the app to add an expense**.
You **do not need to share receipt images into the app** for normal usage.

Instead (default flow):
1. Use the **Quick Settings tile** to control overlay/accessibility state when entering or leaving payment apps.
2. Use the floating overlay capture button while using payment apps.
3. The screenshot is analyzed by **Azure OpenAI (GPT-4.1-mini)**.
4. Extracted fields are shown back to you for validation/editing.
5. Final expense data is stored in your own **Azure Storage Account (Azure Tables)** for long-term persistence.
6. Open the app mainly to view beautiful analytics and manage expenses.

Optional fallback flow (when needed):
- Share a receipt image from another app to ExpenseTracker.
- ExpenseTracker runs the same AI extraction + editable confirmation + Azure Table save pipeline.

---

## ✨ Core Capabilities
- Floating, draggable overlay capture button (Accessibility Service based)
- Quick Settings tile to quickly enable/disable overlay accessibility flow
- One-tap screenshot capture from foreground screen context
- AI extraction using Azure OpenAI Responses API (GPT-4.1-mini deployment)
- Editable confirmation card before final save
- Azure Table Storage as long-term source of truth
- Dashboard with:
  - total spend
  - top categories
  - app-wise distribution
  - month/year trends
  - interactive drill-down
- Expense edit and delete support
- Share-to-ExpenseTracker support (`image/*` share intent) for receipts when overlay capture is not convenient

---

## 🧠 High-Level Flow
### Quick Settings Tile Control (Payment Compatibility)
1. Add the ExpenseTracker tile from Quick Settings edit panel.
2. If tile is active, accessibility overlay flow is enabled.
3. Tap tile before opening strict payment apps to disable service quickly.
4. Tap tile again later to re-enable capture flow (opens Accessibility Settings when needed).

### Overlay Capture (Primary)
1. `PaymentOverlayAccessibilityService` shows overlay.
2. Tap capture button.
3. `ScreenCaptureManager` captures screenshot via MediaProjection service.
4. `AzureOpenAiExpenseClient` sends image bytes to your deployed model.
5. Extracted fields are merged/validated in overlay card.
6. Data is persisted to Azure Tables through `AzureTableExpenseClient`.
7. App UI fetches from Azure and renders dashboard + expense lists.

### Share Receipt (Fallback, when needed)
1. In any app, tap **Share** on a receipt image and choose ExpenseTracker.
2. `MainActivity` receives `ACTION_SEND` (`image/*`).
3. The shared image is analyzed using `AzureOpenAiExpenseClient`.
4. An editable confirmation dialog is shown.
5. On save, expense data is synced to Azure Tables via `AzureTableExpenseClient`.

---

## 🏗️ Tech Stack
- Kotlin + Jetpack Compose
- Accessibility Service + Overlay Window
- MediaProjection + Foreground Service for screenshot capture
- Azure OpenAI Responses REST API
- Azure Table Storage REST API
- Material 3 UI

---

## 🔐 Configuration (Secrets)
This project reads secrets in the following priority order:
1. Gradle property
2. Environment variable
3. External file (`.gradle/gradle.properties` or `local.properties`)

Add these keys (recommended in **`.gradle/gradle.properties`**, not committed):

```properties
AZURE_OPENAI_ENDPOINT=https://<your-resource>.services.ai.azure.com/openai/v1
AZURE_OPENAI_API_KEY=<your-azure-openai-key>
AZURE_OPENAI_MODEL=gpt-4.1-mini

AZURE_TABLE_ENDPOINT=https://<your-storage-account>.table.core.windows.net
AZURE_TABLE_NAME=<your-table-name>
AZURE_TABLE_SAS_TOKEN=<your-sas-token-without-leading-?>
```

> Note: If secrets are missing, the app treats Azure setup as incomplete.

---

## ▶️ Run the App
1. Open in Android Studio.
2. Sync Gradle.
3. Build and run `app` module on a physical device.
4. Grant required permissions in onboarding/setup:
   - Accessibility service enable
   - Screen capture consent
   - Notifications (Android 13+)

---

## 📊 Data Stored in Azure Table
Each expense is saved as a single entity with:
- `PartitionKey` (month bucket, e.g. `yyyyMM`)
- `RowKey` (unique record key)
- `SavedAtMillis`, `LocalId`
- Extracted analyzer fields (normalized where needed)

Excluded fields (current rule):
- `PayerBankName`

---

## 📱 User Experience Summary
- Quick Settings tile gives one-swipe control over overlay/accessibility behavior.
- Overlay is available for quick capture when tile/service is enabled.
- Before strict payment flows, disable from tile to reduce accessibility blocking prompts.
- When needed, shared receipt images are also supported via Android share sheet.
- You validate/edit extracted fields before save.
- App home focuses on visualization and analysis.
- Detailed expense management remains available (edit/delete/drill-down).

---

## ⚠️ Security Notes
- Never commit real Azure keys/SAS tokens to Git.
- Prefer storing secrets in local Gradle properties or environment variables.
- Use minimal-scope SAS permissions and expiry.

---

## 📂 Important Files
- `app/src/main/java/com/adityanile/expensetracker/MainActivity.kt` (dashboard + shared receipt `ACTION_SEND` flow)
- `app/src/main/java/com/adityanile/expensetracker/PaymentOverlayAccessibilityService.kt`
- `app/src/main/java/com/adityanile/expensetracker/OverlayQuickSettingsTileService.kt`
- `app/src/main/java/com/adityanile/expensetracker/OverlayTogglePreferences.kt`
- `app/src/main/java/com/adityanile/expensetracker/ScreenCaptureManager.kt`
- `app/src/main/java/com/adityanile/expensetracker/AzureOpenAiExpenseClient.kt`
- `app/src/main/java/com/adityanile/expensetracker/AzureTableExpenseClient.kt`
- `app/src/main/java/com/adityanile/expensetracker/AzureOpenAiSettings.kt`
- `app/src/main/java/com/adityanile/expensetracker/AzureTableSettings.kt`

---

## 📌 Current Product Positioning
ExpenseTracker is designed as an **AI-assisted background expense capture system**:
- Capture happens from overlay (without opening app flows).
- Intelligence comes from Azure OpenAI model inference.
- Trust comes from user validation before save.
- Durability comes from your own Azure Table Storage.
- Insight comes from an interactive expense analytics dashboard.
