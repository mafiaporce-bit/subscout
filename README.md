# SubScout (Android)

Finds every subscription you are paying for and puts them in one list, with the price, how often
it charges and the next charge date.

Where it looks:
- **Email** (Gmail and other IMAP mailboxes, with an app password): receipts, renewals, trial and
  cancellation emails, including Google Play, Apple and PayPal receipts. Read-only.
- **Bank alerts**: card-charge notifications (bank SMS, Google / Samsung Wallet) as they arrive.

Everything stays on the phone. The only connection the app makes is to the mail server.

## Get the APK
Every push to `main` builds the app (GitHub Actions). On your phone open
**Releases → SubScout (latest build)**, download `SubScout.apk` and tap it to install.

## Project
- `app/src/main/assets/index.html`: the whole screen plus the detection engine (one file)
- `app/src/main/java/com/jpsums/subscout/Mail.kt`: reads the mailbox
- `app/src/main/java/com/jpsums/subscout/AlertService.kt`: notices card-charge alerts
- `app/src/main/java/com/jpsums/subscout/Bridge.kt`: what the screen can ask the phone to do
- `.github/workflows/build-apk.yml`: the APK build

Copyright © 2026 Zenix_dev@company. All rights reserved. Developed by JPSums
