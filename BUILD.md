# Build Guide

## Prerequisites

- JDK 17+
- Android SDK with build-tools
- `JAVA_HOME` and `ANDROID_HOME` set

## Keystore

For release builds, a JKS keystore is located at the project root:

| Item | Value |
|---|---|
| File | `s3ss10n.jks` (project root) |
| Alias | `s3ss10n` |
| Store password | `android` |
| Key password | `android` |
| Algorithm | RSA 2048 |
| Validity | 100 years |

### How the keystore was generated

```bash
keytool -genkeypair \
  -keystore s3ss10n.jks \
  -alias s3ss10n \
  -keyalg RSA \
  -keysize 2048 \
  -validity 36500 \
  -storepass android \
  -keypass android \
  -dname "CN=niki914, OU=s3ss10n, O=github.com/niki914, L=Unknown, ST=Unknown, C=CN"
```

### Signing config

The file `keystore.properties` (gitignored) is read by `app/build.gradle.kts`:

```properties
storeFile=../s3ss10n.jks
storePassword=android
keyAlias=s3ss10n
keyPassword=android
```

If `keystore.properties` is missing, the release build type still compiles but skips signing.

## Build commands

### Debug APK

```bash
./gradlew :app:assembleDebug
```

Output: `app/build/outputs/apk/debug/app-debug.apk`

### Release APK (signed)

```bash
./gradlew :app:assembleRelease
```

Output: `app/build/outputs/apk/release/app-release.apk`

### Install to device

```bash
./gradlew :app:installDebug
```
