# Fly Away Indicator: separate Admin AI and Customer AI

This work is isolated on branch `feature/admin-customer-ai`. The existing root Android project and its source files/workflow are left untouched.

## Added projects
- `admin-ai/`: separate administrator APK with customer-name records, one-time code issuance, configurable default duration (14 days), status list, extend/shorten, deactivate, and reactivate controls.
- `customer-ai/`: separate customer APK that contains copies of the existing monitoring/recording/analysis implementation behind an authorization gate. Original app source files are not edited.
- `authorization-server/`: server-side code consumption, activation-time expiry, status verification, default period, extension from existing expiry, and manual deactivation.
- `.github/workflows/build-admin-customer.yml`: builds both APKs independently.

## Required before customer use
The Android apps intentionally fail closed until a deployed HTTPS authorization service URL is supplied as the GitHub repository variable `AUTH_API_BASE_URL`. Deploy `authorization-server/` to a Node.js host with persistent storage and set a strong `ADMIN_API_KEY` secret. Then set `AUTH_API_BASE_URL` to that HTTPS service URL in GitHub Actions variables and rerun the workflow. Never put the admin server key in source code or a public repository.

## Important limitations of this first implementation
- The new apps are a separate first implementation and have not yet been verified on the user's Tecno P703.
- The server must be deployed and tested before authorization, expiration, revocation, and customer management work end-to-end.
- The optional local passcode is a convenience lock, not a replacement for server authorization.
- The existing app and its original build workflow are preserved; do not merge these changes into `main` until builds and end-to-end tests pass.
