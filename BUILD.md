# Vigil Android 1.1

Open this directory in Android Studio. The app keeps the existing package name com.vigil.android. Its Gradle wrapper uses Gradle 9.5 and the app compiles against Android SDK 37. Use a full JDK 21 and install the requested Android SDK/build tools.

## APKs supplied with this project

The apk/ directory contains the compiled debug APK for a test handset. It targets the original Vigil server. Deploy the updated backend there before testing, or build against your staging hostname as described below. The release APK in this directory is unsigned and is intended for your existing release-signing process.

## Server and signing

The default server is https://vigil.ai.in/. To point a build to your staging server, set this in local.properties and rebuild:

```properties
vigil.baseUrl=https://your-vigil-staging.example/
```

Use the release keystore and signing details from your existing app when distributing an update over an installed production version. Supply signing.storeFilePath, signing.storePassword, signing.keyAlias and signing.keyPassword through the local.properties file on your build machine. The keystore path can be absolute. Keep that file and your release key private.

```sh
./gradlew :app:assembleDebug
./gradlew :app:assembleRelease
```

On Windows, use gradlew.bat. The debug APK output is in app/build/outputs/apk/debug/. An unsigned release output can be signed by your release process. A debug-signed build has a different certificate from a production installation. Use a separate test device or profile for the debug build.

## Mobile workflows

- Hotel Staff sign in with the eight-character username and the password created in the admin portal. Select the date, enter the lead guest and party details, attach up to two document images per guest, and submit.
- Patrol Officer uses the Face match tab next to patrol history to sync reference records and review assigned verifications. A manual sync downloads changes. After sync, matching works offline. The camera and liveness check run on the device. Review the candidate and confirm at the site. Pending confirmations use the same officer's session when connectivity returns. Notification delivery requires permission and depends on Android background scheduling. If the reference changed before upload, sync and repeat the verification.
- Traffic Police opens the assigned duty, grants precise location and notifications, and switches on while the app is visible. Keep the ongoing duty notification enabled. Location sampling targets five-minute intervals and follows the assigned duty window. GPS and connectivity affect when a fix reaches the server. The app also shows congestion alerts and a challan camera.
- Test camera, offline matching, delayed uploads, background GPS, and in-app notifications on the device models selected for deployment. Verify recognition and liveness thresholds with your field team using representative lighting, cameras and enrolment images before making operational decisions.
