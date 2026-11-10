# AB Site Audit – Android app

The whole app is `app/src/main/assets/index.html` (the same file as the browser version).
The Android part (`MainActivity.java`) adds camera/microphone access, the phone file picker
(Files, OneDrive, Gallery), saving straight to Downloads and Pictures, PDF open/share,
and the phone's own voice typing.

## Build the APK on GitHub (nothing to install)
1. Create a free account at github.com and make a new **private** repository.
2. Upload every file and folder from this zip (keep the folder structure, including `.github`).
3. Open the repository's **Actions** tab – the "Build APK" job runs automatically (about 5 minutes).
4. When it shows a green tick, open the run and download **AB-Site-Audit-APK** (a zip containing `AB-Site-Audit.apk`).

## Or build with Android Studio on a PC
Open this folder in Android Studio, let it finish syncing, then choose
*Build › Build App Bundle(s) / APK(s) › Build APK(s)*. Both debug and release builds are signed
with the key in `app/siteaudit.keystore`, so either installs and updates cleanly.

## Install on the phone
Copy the APK to the phone and tap it. Android will ask to allow installing from that app
(Files / Chrome / OneDrive) – allow it once. Samsung may also show "Auto Blocker" – turn it off
in Settings › Security and privacy › Auto Blocker to install, then turn it back on.

## Updating
Change `index.html`, raise `versionCode` in `app/build.gradle`, rebuild, and install over the top.
Because every build is signed with the same key (`app/siteaudit.keystore`), your audits are kept.
**Do not uninstall the app** – uninstalling deletes the audits stored in it. Export backups regularly.
Keep the repository private: it contains the signing key.
