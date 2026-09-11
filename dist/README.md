# Prebuilt APK

`foldphase-release.apk` — minified, signed, `targetSdk 36`, ~2.4 MB.

Committed deliberately so it can be downloaded directly onto the phone from github.com
without a computer, Android Studio, or adb. See `docs/INSTALL.md`.

The debug APK is **not** committed: it is 61 MB, and its only advantage — unminified stack
traces — is not useful without a debugger attached anyway.

Rebuild it yourself with `./gradlew assembleRelease`; the output lands in
`app/build/outputs/apk/release/`.

Signed with a throwaway key generated for this project (`app/local-release.jks`, not in
version control). It is not a distribution identity, which is why Android will call this an
app from an unknown source.
