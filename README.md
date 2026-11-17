# ReceiptShield 🛡️

A 100% offline-first Android application designed to cryptographically stamp and verify receipts, invoices, and documents without needing an internet connection.

## Features
- **Zero Internet Required:** Fully functional offline cryptographic hashing and QR verification.
- **Efficient & Lightweight:** Chunked stream-based SHA-256 file hashing to preserve device memory.
- **Tamper-Proof:** Instant detection if a document or payment screenshot has been altered after creation.

## CI/CD Pipeline
Automated builds and APK generation are powered by [CircleCI](https://circleci.com/). Every push to the repository triggers a clean Gradle build and stores the output debug APK as a workflow artifact.
