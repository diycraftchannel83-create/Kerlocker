# Release signing for Kerlocker

The Android release workflow is at `.github/workflows/android-release.yml`.

## Required GitHub Actions secrets for signed release builds

Create these repository secrets under **Settings → Secrets and variables → Actions**:

- `RELEASE_KEYSTORE_BASE64`: Base64-encoded upload keystore (.jks).
- `RELEASE_STORE_PASSWORD`: Keystore password.
- `RELEASE_KEY_PASSWORD`: Key password for the `upload` alias.

The workflow expects the alias `upload`, matching `app/build.gradle.kts`.

### Generate the Base64 value

Linux/macOS:
```bash
base64 -w 0 my-upload-key.jks
```

macOS alternative:
```bash
base64 < my-upload-key.jks | tr -d '\n'
```

Copy the resulting single-line value into `RELEASE_KEYSTORE_BASE64`.
Never commit the keystore or passwords to the repository.

## Build behavior

- Push to `main`, push a `v*` tag, pull request to `main`, or manual dispatch starts CI.
- Produces a release APK and Android App Bundle (AAB).
- With the three signing secrets configured, release outputs are signed using the existing `upload` alias.
- Without signing secrets, the workflow attempts to build unsigned release outputs for CI/testing. Unsigned artifacts cannot be uploaded to Google Play.
- Download APK and AAB separately from the workflow run's **Artifacts** section.

## Create a versioned release build

```bash
git tag v1.0.1
git push origin v1.0.1
```

Update `versionCode` and `versionName` in `app/build.gradle.kts` before publishing each Play Store update.
