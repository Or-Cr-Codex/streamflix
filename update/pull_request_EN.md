# Streamflix Improvement Log

This file keeps track of the changes and improvements made to the project.

**IMPORTANT:** All changes described in this log are based on the **stable Streamflix 1.7.96 release**.

///////////////

# Pull Request: Virtual Cursor, Global Key Interception, and Precision Mouse Emulation for Android TV

In this update, I have definitively resolved the anti-bot unlock issue (Cloudflare) on Android TV, overcoming the physical limits of the remote control and the constraints of old integrated Chrome versions.

**CRITICAL TECHNICAL NOTE:** On systems with **Android TV 9.0 or lower**, Cloudflare bypass **would never work** using the standard integrated browser (WebView) due to the obsolete Chrome engine (v66 or lower). Cloudflare identifies these browsers as insecure or bots, blocking any automatic interaction. The changes introduced here represent the only technical solution to maintain compatibility with these devices.

### What I did

#### 1. Virtual Cursor (Red Crosshair)
Introduced a high-visibility red crosshair controllable via the remote control arrows (D-Pad).
- **Manual Pointing**: The user can precisely position the crosshair over the Cloudflare verification checkbox.
- **Multi-language Support**: Cursor instructions have been localized in Italian, Spanish, Arabic, French, German, and English to guide users in every region.

#### 2. Resolving Android TV Legacy Limits
Implemented specific solutions for the dated Chrome engine typical of Android TV 9 and earlier versions:
- **Precision Mouse Emulation**: The system now simulates a real mouse sequence (`Hover` -> `Down` -> `Up`). This is indispensable because old Chrome versions ignore software touches unless preceded by a hover event and identified as `SOURCE_MOUSE`.
- **Global Key Interception**: The main container now captures all D-Pad events, preventing the WebView from "stealing" focus and ensuring the cursor remains always active and smooth.

#### 3. Full-Screen UI and Relative Layout
Redesigned the unlock interface to eliminate image cropping issues common on TV screens with non-standard scaling.
- **Anchored RelativeLayout**: The instruction bar is fixed at the top, while the WebView occupies all remaining space, guaranteeing visibility of the verification checkbox.

#### 4. Strategic Synchronization (UA & Cookie)
- **UA Pixel 7 Sync**: Synchronized the modern mobile User-Agent between WebView and OkHttp to induce Cloudflare to serve simpler challenges and keep the session valid post-unlock.
- **Reactive Cookie Flush**: Instant synchronization of the `cf_clearance` cookie to make Home Page population immediate.

### Notes for the User
- **On TV**: When the black screen appears, use the arrows to move the crosshair onto the checkbox and press OK. If it doesn't unlock immediately, move it a few pixels and try again.

### Modified Files
- `app/src/main/java/com/streamflixreborn/streamflix/providers/Cine24hProvider.kt`
- `app/src/main/java/com/streamflixreborn/streamflix/utils/WebViewResolver.kt`
- `app/src/main/java/com/streamflixreborn/streamflix/utils/NetworkClient.kt`
- `app/src/main/res/values/strings.xml` (and localized variants)
