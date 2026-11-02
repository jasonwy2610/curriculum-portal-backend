# UI update - Buttons, Toasts and user-friendly errors

## What changed
- `frontend/src/main.jsx`
  - Added a single app-wide toast event bus and one `ToastViewport` component.
  - Pages and modal workflows reuse the same toast system through the existing `notify(...)` callbacks.
  - API errors are converted into Vietnamese user-facing messages before they reach the UI.
  - Removed persistent page-level API error banners; API failures now appear in the shared toast viewport.
  - Handles login failures with the same toast viewport.
- `frontend/src/style.css`
  - Updated primary, outline, text and icon buttons with larger hit areas, clearer borders, hover/active/focus/disabled states.
  - Added the shared top-right toast appearance, variants, close controls and auto-dismiss progress indicator.
- `src/main/java/vn/edu/huflit/portal/exception/GlobalExceptionHandler.java`
  - Handles duplicate-key database errors as HTTP 409 with Vietnamese guidance.
  - Avoids exposing raw MongoDB/Java exception details to end users for unexpected server errors.
  - Keeps detailed exception stack traces in backend logs.

## Toast behavior
- Success: 3.5 seconds
- Info: 4 seconds
- Warning: 5 seconds
- Error: 6.5 seconds
- Users can dismiss any toast manually. Up to five recent messages are kept in the viewport.

## Duplicate curriculum example
If MongoDB rejects a second curriculum with the same major/cohort, the API now returns a friendly message such as:
"Khóa K2026 đã có chương trình đào tạo thuộc ngành này. Vui lòng kiểm tra CTĐT hiện có hoặc chỉnh sửa chương trình đã tạo."

## Run
Frontend:
```bash
cd frontend
npm install
npm run dev
```
Backend:
```bash
./mvnw spring-boot:run
```

## Validation note
The source changes were applied. A frontend production build and Maven compile could not be completed in this environment because frontend dependencies were not installed and Maven wrapper download access was unavailable. Please run the commands above locally and send any build error output if one appears.
