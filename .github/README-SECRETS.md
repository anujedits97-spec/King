# APK build setup

The workflow `.github/workflows/build-apk.yml` builds the app on every push to `main`
(debug APK) and on every `v*` tag (signed release APK, attached to a GitHub release).

Download the APK from the Actions tab: open the run, then "Artifacts" > `myra-apk`.

## Optional: signed release builds

Add these under repo Settings > Secrets and variables > Actions:

- `MYRA_KEYSTORE_B64`: your keystore file encoded as base64
  (Linux/Mac: `base64 -w0 myra.keystore`; Windows PowerShell:
  `[Convert]::ToBase64String([IO.File]::ReadAllBytes("myra.keystore"))`)
- `MYRA_KEYSTORE_PASSWORD`
- `MYRA_KEY_ALIAS`
- `MYRA_KEY_PASSWORD`

Without these, tagged builds produce an unsigned release APK.
