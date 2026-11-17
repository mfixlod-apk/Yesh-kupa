# Repository status

## Repository

- GitHub repository: mfixlod-apk/Yesh-kupa
- Default branch: main
- Visibility: public
- Android source code and a build workflow are now committed.

## Current committed MVP

- Kotlin Android application with a Hebrew RTL interface.
- Local SQLite storage on the device.
- Product creation and name search.
- Shopping cart with quantity controls.
- Sale recording with sale lines.
- Daily sales total.
- GitHub Actions workflow to build and upload a debug APK.

## Build verification

The Android APK workflow has been triggered on pushes to main. Its first complete result must be checked in the Actions tab before the APK can be described as build-verified. A successful run publishes the file as the `yesh-kupa-debug-apk` artifact.

## Known limitations

- No barcode scanning, stock movement, purchase costs or gross-profit calculation yet.
- No thermal-printer or cash-drawer integration yet.
- No cloud sync, multi-user accounts or subscriptions.
- No certified Israeli accounting-document issuance. Do not use this MVP as a replacement for approved invoicing software.

## Next engineering steps

1. Verify the CI build and install the APK on a physical Android device.
2. Add barcode scanning and a fast POS search flow.
3. Add stock, purchase cost and gross-profit tracking.
4. Add sales history, returns and audit controls.
5. Add thermal receipt printing.
6. Add secure backup and sync.
7. Integrate with an approved document provider after legal and technical review.

Never commit production secrets, signing keys, service-role keys or customer data.
