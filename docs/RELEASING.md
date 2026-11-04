# Releasing and hosting the repository

Everything is static and hosted by GitHub. There is no server.

## One-time setup

1. **Create the signing key** (once, ever):

   ```sh
   scripts/generate_signing_key.sh signingkey.jks
   ```

   Back up `signingkey.jks` and its password somewhere safe and offline. Mihon only installs updates
   signed with the same key; losing it means every user must uninstall and reinstall.
   `*.jks` is git-ignored — never commit it.

2. **Add repository secrets** (Settings → Secrets and variables → Actions):

   | Secret | Value |
   |---|---|
   | `SIGNING_KEY` | `base64 < signingkey.jks \| tr -d '\n'` |
   | `ALIAS` | the key alias (default `libraryofalexandria`) |
   | `KEY_STORE_PASSWORD` | keystore password |
   | `KEY_PASSWORD` | key password (same as above with the script) |

3. **Allow the workflow to push**: Settings → Actions → General → Workflow permissions →
   *Read and write permissions*.

## Releasing

1. Bump `extVersionCode` in `extension/build.gradle.kts` (the version becomes `1.6.<code>`;
   hosts only offer an update when this increases).
2. Commit, then tag and push: `git tag v1.6.2 && git push origin main v1.6.2`.
3. `release.yml` runs the tests, builds and signs the APK and JAR, refuses debug-signed output,
   computes the certificate fingerprint, generates the index with `scripts/publish_repo.py`, force-pushes
   the result to the `repo` branch and attaches the APK/JAR to a GitHub release.

The repository URL users add is:

```
https://raw.githubusercontent.com/KarsTheUltimateCreation/library-of-alexandria/repo/index.pb
```

(`repo.json` and `index.min.json` in the same directory also work on Mihon; `index.pb` is what current
Tachimanga documentation asks for.)

## What gets published (`repo` branch)

| File | Purpose |
|---|---|
| `index.pb` | gzipped protobuf index (tachiyomix schema + `jarUrl` field 501, as Keiyoushi publishes) |
| `index.json` | the same index as JSON |
| `repo.json` | legacy repo metadata; its `index_v2` points at `index.pb` |
| `index.min.json` | legacy index for older forks |
| `apk/…apk`, `jar/…jar`, `icon/<pkg>.png` | artifacts |

`publish_repo.py` encodes the protobuf without dependencies; its codec reproduces Keiyoushi's live
`index.pb` byte-for-byte when re-encoding their `index.json` (1,454 extensions), which is how it was validated.

## Local builds

```sh
echo "sdk.dir=/path/to/android/sdk" > local.properties   # needs platforms;android-37.0
./gradlew :core:test                                     # unit tests (offline)
LOA_LIVE=1 ./gradlew :core:test --tests '*Live*'         # live tests against real sites
./gradlew :extension:assembleRelease                     # APK + JAR (debug-signed without signingkey.jks)
```
