# Production Android releases

Version 0.2.0 (internal build 8) is the first release signed with a dedicated production key.
It uses package `io.dualmesh.app`, requires Android 8.0+, and is distributed as a universal APK
through the [upstream GitHub releases](https://github.com/logans-stuff/dualmesh/releases).
This is a directly distributed release, not a Google Play publication. The app remains experimental.

## Switching from development builds

The production certificate is different from 0.1.0 and debug builds. Android rejects an ordinary
update signed with a different certificate. Uninstalling the old app permits a fresh production
install but deletes its local messages and preferences. Save anything needed before uninstalling;
the release process never uninstalls the existing app or migrates its data automatically.
Subsequent production APKs must use this same production key and a higher version code.
See [Android's signing documentation](https://developer.android.com/studio/publish/app-signing).

## Private signing material

On the release machine, the key and password properties are stored outside the checkout:

```text
~/.local/share/dualmesh/signing/dualmesh-production.jks
~/.local/share/dualmesh/signing/keystore.properties
```

The directory is accessible only to its owner; both files use owner read/write permissions.
Keep a secure off-machine backup of both files. Losing the key prevents ordinary updates to
existing production installs. Never upload either file as a release asset or commit it.
Only the public certificate and fingerprint may be distributed.

Production certificate SHA-256 fingerprint:

```text
2F:D5:39:C5:FA:33:8B:A1:1D:ED:59:AC:93:58:32:5A:C9:9B:F0:89:49:A3:1B:B3:54:5C:C2:53:2B:61:A8:AE
```

The properties file has these fields (use actual private values locally):

```properties
storeFile=/absolute/path/to/dualmesh-production.jks
storePassword=YOUR_PRIVATE_PASSWORD
keyAlias=dualmesh
keyPassword=YOUR_PRIVATE_PASSWORD
```

## Build and verify

Use Java 21 and the configured Android SDK:

```shell
DUALMESH_SIGNING_PROPERTIES="$HOME/.local/share/dualmesh/signing/keystore.properties" \
  ./gradlew testDebugUnitTest assembleRelease lintDebug lintRelease
```

The signed artifact is `app/build/outputs/apk/release/app-release.apk`. With the environment
variable absent, the release remains unsigned for existing CI jobs. Invalid or incomplete supplied
signing properties fail the build; there is no fallback to debug signing.

Before publishing, verify the APK signature with the SDK's `apksigner verify --verbose --print-certs`,
check its version/package and non-debuggable manifest, and run `zipalign -c -P 16 4`.
Compare the signing certificate fingerprint with the published production certificate, confirm
that screenshot fixtures are absent, and generate `SHA256SUMS.txt` for the final assets.
Publish the APK, public certificate, fingerprint, and checksums from the exact tagged commit.

## Upstream release automation

Merging into `logans-stuff/dualmesh` main runs the Android APKs workflow. The build job tests
and lints the merged source and produces an unsigned release APK. The production job signs that
same run's artifact, verifies the package/version, non-debuggable flag, fixture exclusion, production
certificate and ZIP alignment, then publishes `v<versionName>` in the **upstream** repository.
The fork is not used as an artifact source. The existing development prerelease remains separate.
Already published production versions are left unchanged; bump both version fields for a new release.
A manual Android APKs workflow run on main can retry publication after signing secrets are configured.

A maintainer with upstream repository access must configure these GitHub Actions repository secrets:

- `ANDROID_RELEASE_KEYSTORE_BASE64`: base64-encoded bytes of the existing production JKS file.
- `ANDROID_RELEASE_KEYSTORE_PASSWORD`: its password (also used as the key password, alias `dualmesh`).

Use the same production key whose fingerprint is recorded above. The key owner must provide it to
the maintainer through an agreed secure channel, never in PR comments, issues, or release assets.
The workflow pins the public fingerprint and rejects another key, including a debug certificate.
Secrets are used only in the production job on upstream main, never in pull-request builds.
The decoded key exists in a restricted temporary directory and is removed even if signing fails.

Until those secrets are configured, build artifacts and the development prerelease can still be
created, but the production job fails with setup instructions. A merge alone cannot create a
production-signed APK without the private key. The current contributor account has read-only
upstream access and cannot configure these secrets or directly publish an upstream release.
