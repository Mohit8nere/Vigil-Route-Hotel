# Vigil Android 1.1

Open this directory in Android Studio. The app keeps the existing package name com.vigil.android. Its Gradle wrapper uses Gradle 9.5 and the app compiles against Android SDK 37. Use a full JDK 21 and install the requested Android SDK/build tools.

## Server and signing

Android version 1.1.0 uses version code 4. Devices with version code 5 or later cannot install this as an in-place downgrade.

The supplied debug configuration defaults to http://192.168.1.134:8026/. Release defaults to https://vigil.ai.in/ and requires HTTPS for network access. To point a build to your staging server, set this in local.properties and rebuild:

```properties
vigil.baseUrl=https://your-vigil-staging.example/
```

Use the release keystore and signing details from your existing app when distributing an update over an installed production version. Supply signing.storeFilePath, signing.storePassword, signing.keyAlias and signing.keyPassword through the local.properties file on your build machine. The keystore path can be absolute. Keep that file and your release key private.

```sh
./gradlew :app:testDebugUnitTest :app:assembleDebug
./gradlew :app:connectedDebugAndroidTest  # requires a connected test handset or emulator
./gradlew :app:assembleRelease
```

On Windows, use gradlew.bat. The debug APK output is in app/build/outputs/apk/debug/. An unsigned release output can be signed by your release process. A debug-signed build has a different certificate from a production installation. Use a separate test device or profile for the debug build.

## Mobile workflows

- Hotel Staff sign in with the eight-character username and the password created in the admin portal. Select the date, enter the lead guest and party details, attach up to two document images per guest, and submit.
- Patrol Officer uses the Face match tab next to patrol history to sync reference records and review assigned verifications. A manual sync downloads changes. After sync, matching works offline. The camera and liveness check run on the device. Review the candidate and confirm at the site. Pending confirmations use the same officer's session when connectivity returns. Notification delivery requires permission and depends on Android background scheduling. If the reference changed before upload, sync and repeat the verification.
- Traffic Police opens the assigned duty, grants precise location and notifications, and switches on while the app is visible. Keep the ongoing duty notification enabled. Location sampling targets five-minute intervals and follows the assigned duty window. GPS and connectivity affect when a fix reaches the server. The app also shows congestion alerts and a challan camera.
- Test camera, offline matching, delayed uploads, background GPS, and in-app notifications on the device models selected for deployment. Verify recognition and liveness thresholds with your field team using representative lighting, cameras and enrolment images before making operational decisions.


## Field and chat workflows

Patrol and traffic accounts can switch between patrol routes and traffic duties. The duty menu exposes VIP assignments. Assigned surveys use the location and upload checks defined by the server. Volunteer accounts open their own duty workspace.

Chat uses the configured server for its authenticated API, WebSocket tickets and attachments. Photos, PDF files up to 2 MiB, and voice notes of up to 110 seconds can be sent. Outgoing messages are queued in Room and retried by WorkManager when connectivity returns. The visible conversation automatically records reads. Redis and the ASGI WebSocket route must be available for immediate event delivery. REST reconciliation runs in the foreground and Android schedules background catch-up approximately every 15 minutes when network and power conditions allow. Notification permission is required on Android 13 and later. Release builds use the no-op Chucker dependency and skip installing the interceptor.


## Upgrading a shared handset

The original app could store a patrol profile ID and a login account ID under the same numeric chat cache key. On the first upgrade, Room retains the original conversations, messages, outbox and sync rows in the app-private database in a separate negative-ID namespace. Delivered messages reload using the signed-in account's server permissions. Pre-upgrade unsent entries have status LEGACY_HELD. The app cannot prove who wrote every old draft on a shared phone and does not automatically transmit these entries. Existing media files associated with those entries are retained where available. An officer who still needs to send an old item should confirm the recipient and compose it again with their own account and original attachment. Keep app data intact if the department needs to review an old draft through its approved device support process. New queued messages use the login account identifier and resume normal offline retry.
