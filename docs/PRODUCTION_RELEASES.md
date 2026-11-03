# Production Android releases

Version 0.2.0 (internal build 8) is the first release signed with a dedicated production key.
It uses package `io.dualmesh.app`, requires Android 8.0+, and is distributed as a universal APK
through the [fork's GitHub releases](https://github.com/MickTheRus/dualmesh/releases).
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
