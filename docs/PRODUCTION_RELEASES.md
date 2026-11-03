# Versioned APK releases and optional production signing

The repository builds Android APKs without private production signing keys. Version 0.2.0 has
internal build number 8, package `io.dualmesh.app`, and requires Android 8.0 or newer.
Canonical builds are published in [upstream Releases](https://github.com/logans-stuff/dualmesh/releases).

## Build without signing secrets

Use Java 21 and Android SDK 37, as described in the README:

```shell
./gradlew testDebugUnitTest lintDebug lintRelease assembleDebug assembleRelease
```

With `DUALMESH_SIGNING_PROPERTIES` unset, this produces:

- `app/build/outputs/apk/debug/app-debug.apk`: installable, automatically development-signed by Android tooling.
- `app/build/outputs/apk/release/app-release-unsigned.apk`: the release variant, ready for a distributor to sign.

An unsigned release APK cannot be installed until signed, but creating it requires no private key.
Tests and lint also need no production signing material.

## Publication after merge

The Android APKs workflow builds and tests pull requests and main. After a successful upstream
main build, separate jobs update the development prerelease and publish versioned build assets
under `v<versionName>`. For 0.2.0 these assets are:

- `dualmesh-0.2.0-debug.apk` (installable)
- `dualmesh-0.2.0-release-unsigned.apk` (sign before installation)
- `BUILD-INFO.txt` (version, merged commit, workflow URL, and variant descriptions)
- `SHA256SUMS.txt`

The publishing job uses the existing built-in `GITHUB_TOKEN` with `contents: write` and the APK
artifacts from the same successful workflow run. It does not download binaries from a fork or
require repository signing secrets. The release notes explicitly identify development signing
and the unsigned release variant; the version tag does not imply a production signing certificate.

Existing published versions remain unchanged. Bump both version fields in `app/build.gradle.kts`
for a new release. A manual Android APKs run on main retries an unpublished version. A draft from
a different commit must be resolved by a maintainer rather than silently overwritten.

## Optional dedicated signing

A distributor can additionally sign the release variant with a dedicated private key. This is a
separate distribution choice and never a prerequisite for the default CI builds or publication.
The existing opt-in Gradle configuration supports an external properties file:

```shell
DUALMESH_SIGNING_PROPERTIES=/private/path/keystore.properties ./gradlew assembleRelease
```

```properties
storeFile=/absolute/path/to/production.jks
storePassword=YOUR_PRIVATE_PASSWORD
keyAlias=YOUR_KEY_ALIAS
keyPassword=YOUR_PRIVATE_PASSWORD
```

This produces `app/build/outputs/apk/release/app-release.apk`. Keep key/password files private and
back them up securely; never commit them or attach them as release assets. Android update
compatibility depends on signing certificates. Changing certificates can require a fresh install,
and uninstalling deletes local app data. See [Android signing documentation](https://developer.android.com/studio/publish/app-signing).

The separately signed 0.2.0 APK previously published on the contributor fork uses a different
certificate from ordinary debug builds. It is not the source of the upstream CI artifacts.
The contributor's optional signing files remain outside the checkout at
`~/.local/share/dualmesh/signing/`; nothing in upstream CI requires access to them.
