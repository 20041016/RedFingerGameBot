# Verification Accessibility Fix4

- Added passive AccessibilityService; removed pause-on-detection behavior.
- Root attempts to enable the app accessibility component while preserving existing enabled services.
- Network diagnosis and expand-error-details controls are clicked through visible Accessibility nodes.
- The state machine advances from expand only after Accessibility reports "隐藏错误详情".
- The mqqapi URI is extracted only from visible Accessibility text, not OCR blocks.
- Clipboard copy and shared-storage append remain gated on a complete visible URI.
- No face/identity check is completed, bypassed, or submitted by the app.
- Android build and cloud-device runtime still require external verification.
