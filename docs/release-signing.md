# Release signing key — what it is and why it matters

Short version: it is not about the Play Store. It is the app's identity as
far as Android is concerned. You can publish on GitHub forever and still
need it, because Android uses it to decide whether an APK may replace the
one already installed.

## What actually happens when an APK is installed

Every APK is signed with a private key. The key produces a certificate that
travels inside the APK (`META-INF/`). Android stores a copy of that
certificate the first time the app is installed, keyed by package name.

On every later install of the same package name, Android compares:

```
new APK certificate  ==  certificate stored for dev.opensource.attenora ?
```

- Same → normal update. Settings, limits, usage history, reel counts survive.
- Different → `INSTALL_FAILED_UPDATE_INCOMPATIBLE`. Android refuses. The only
  way through is uninstall, which deletes the app's data, then install fresh.

Nothing in that check involves Google. It is the same on a phone that has
never been online.

## Why this came up now

The package name changed from `dev.opensource.focusguard` to
`dev.opensource.attenora`, and the key changed with it, so the build on the
phone (old key) and the GitHub release (new key) are two different
identities. Hence the update error. One uninstall clears it, permanently.

Also: the old key had been committed to the public repo, so its private half
was downloadable. Anyone could have signed a malicious "Attenora update" that
a phone would accept as genuine. That key is retired; this release is the
first signed by the new one.

## When it becomes expensive to get wrong

Only when someone else has the app installed:

| Event | Cost |
|---|---|
| Same key, new version | Users tap install, data kept. This is the goal. |
| Key lost | You can never update those installs. Everyone uninstalls and loses their history, or you ship a new package name and they start over. |
| Key leaked | Someone can publish an update your users' phones will accept. |
| Key expired | Same as lost for new installs until you re-sign, so validity is set to 30 years (2056). |

For a version on your own phone only, none of this matters — any key installs
fine. The moment the APK is on GitHub, it matters for every future release.

## Where it lives

```
C:\Users\Veenum\Documents\Attenora-release\
    attenora-release.jks          the key (4096-bit RSA, alias "attenora")
    KEYSTORE-CREDENTIALS.txt      the password, DN and validity notes
```

- `keystore.properties` in the repo points at it and is **gitignored**. Its
  `storeFile` uses forward slashes: a Windows backslash path is an escape
  sequence in `.properties` and breaks signing.
- Never commit `.jks`, `.keystore`, or `keystore.properties`.
- Certificate SHA-256 (public, safe to quote):
  `738e6bd84160fa46da691cf655cd914ce4808538457665c86da82d9d612da34f`

## Rules

1. Back the folder up twice: a password manager and one offline copy.
2. Sign every future release with this same file. Do not generate a new one
   "to be tidy".
3. If it is ever lost, say so in the release notes and tell users to export
   their CSV before uninstalling. Do not quietly ship a new key.
4. If it is ever leaked, treat every install as untrusted, rotate, and tell
   users to uninstall and reinstall from the new release.

## Verifying a build before publishing

```powershell
.\gradlew.bat testDebugUnitTest assembleRelease checkApkSize
.\.android-sdk\build-tools\35.0.0\apksigner.bat verify --print-certs `
    app\build\outputs\apk\release\app-release.apk
```

The DN must read `CN=Attenora, OU=Release, O=Attenora`. If it says
`CN=FocusGuard Test Key`, the build picked up the old keystore and must not
be published.
