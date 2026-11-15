# ExpenseTracker

ExpenseTracker is an Android app that removes manual expense entry from UPI/payment receipts.

## 🚀 USP (Why this app is different)
You **do not need to manually type expenses**.
You **do not need to open the app to add an expense**.
You **do not need to share receipt images into the app**.

Instead:
1. Use the floating overlay capture button while using payment apps.
2. The screenshot is analyzed by **Azure OpenAI (GPT-4.1-mini)**.
3. Extracted fields are shown back to you for validation/editing.
4. Final expense data is stored in your own **Azure Storage Account (Azure Tables)** for long-term persistence.
5. Open the app mainly to view beautiful analytics and manage expenses.

---

## ✨ Core Capabilities
- Floating, draggable overlay capture button (Accessibility Service based)
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

---

## 🧠 High-Level Flow
1. `PaymentOverlayAccessibilityService` shows overlay.
2. Tap capture button.
3. `ScreenCaptureManager` captures screenshot via MediaProjection service.
4. `AzureOpenAiExpenseClient` sends image bytes to your deployed model.
5. Extracted fields are merged/validated in overlay card.
6. Data is persisted to Azure Tables through `AzureTableExpenseClient`.
7. App UI fetches from Azure and renders dashboard + expense lists.

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
- Overlay is available for quick capture.
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
- `app/src/main/java/com/adityanile/expensetracker/MainActivity.kt`
- `app/src/main/java/com/adityanile/expensetracker/PaymentOverlayAccessibilityService.kt`
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
