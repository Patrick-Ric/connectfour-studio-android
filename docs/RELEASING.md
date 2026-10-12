# Releasing a new version

F-Droid builds the app from source and ships the **developer-signed** APK of
the GitHub release after checking that its own build is bit-identical
(reproducible build, `Binaries:` + `AllowedAPKSigningKeys:` in
`docs/fdroid-metadata.yml`). So every release needs a signed APK on GitHub.

1. Raise `versionCode` and `versionName` in `app/build.gradle.kts` and add the
   changelog `fastlane/metadata/android/{en-US,de-DE}/changelogs/<versionCode>.txt`.
2. Commit, then tag and push: `git tag -a vX.Y.Z -m "…" && git push origin main vX.Y.Z`
3. Clean release build (JDK 21):
   `JAVA_HOME=/usr/lib/jvm/java-21-openjdk ./gradlew clean assembleRelease`
4. Sign the unsigned APK **directly**, with `--alignment-preserved` and **without**
   `zipalign` (otherwise F-Droid cannot copy the signature onto its build):

   ```bash
   PATH=/usr/lib/jvm/java-21-openjdk/bin:$PATH ~/Android/Sdk/build-tools/36.0.0/apksigner sign --alignment-preserved --ks ~/connectfour-release.jks --ks-key-alias cfs --out ConnectFourStudio-X.Y.Z.apk app/build/outputs/apk/release/app-release-unsigned.apk
   ```

   Signing certificate SHA-256:
   `0290aabc0607b5ba98d1fbf67e8f03b166d04f2d6545634f99030278c60546a1`
5. GitHub release `vX.Y.Z` with `ConnectFourStudio-X.Y.Z.apk` and `SHA256SUMS`
   (the file name must match the `Binaries:` URL pattern).
6. F-Droid picks up new tags automatically (`AutoUpdateMode: Version`,
   `UpdateCheckMode: Tags`). While the inclusion merge request
   (https://gitlab.com/fdroid/fdroiddata/-/merge_requests/51839) is still open,
   update `versionName`, `versionCode`, `commit` (full hash), `CurrentVersion`
   and `CurrentVersionCode` there instead.

Optional local check with fdroidserver: `fdroid lint`, `fdroid rewritemeta`,
`fdroid build -v -l io.github.patrickric.connectfourstudio` (needs
`gradlew-fdroid` from https://gitlab.com/fdroid/gradlew-fdroid).
