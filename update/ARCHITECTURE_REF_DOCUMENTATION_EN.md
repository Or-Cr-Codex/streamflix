# Streamflix Architectural Refactoring Documentation

This documentation describes the technical evolution of the project, highlighting the differences between the original structure and the current optimized architecture.

**IMPORTANT:** All changes described in this documentation are based on the **stable Streamflix 1.7.96 release**.

## 1. Original Project Analysis (Initial State)
Before the intervention, the project presented several architectural challenges that affected performance and maintainability:

*   **Network Management (OkHttp/Retrofit):** Each provider managed its own network instances in isolation. This fragmented DNS (DoH) configuration and made it difficult to apply global security policies or timeouts.
*   **Sequential Execution:** Network requests to populate the Home Page or title details occurred serially. The app waited for one request to finish before starting the next, causing visible bottlenecks for the user.
*   **Lifecycle and Memory Leaks:** Initialization of core components (Preferences, Database) often depended on Activity Contexts, risking memory leaks during screen rotation or section changes.

## 2. Architectural Evolution (Current State)

### 🚀 Performance: Universal Parallelism
Heavy use of `coroutineScope` and `async/await` has been introduced across all providers.
- **Before:** The Home Page loaded section by section.
- **After:** All sections are requested simultaneously. The total loading time is now equal to the single slowest request. In `Cine24hProvider.kt`, home categories are now also loaded in parallel.

### 🏗️ Infrastructure: Centralized NetworkClient
Created `NetworkClient.kt` to standardize all outgoing communication.
- **Unified User-Agent (Pixel 7):** To prevent Cloudflare blocks, a modern mobile User-Agent (`NetworkClient.USER_AGENT`) is forced and shared between OkHttp and WebView. This pushes anti-bot systems to serve simpler and more compatible challenges.
- **Reactive Cookie Jar:** A single manager instantly synchronizes sessions between the internal browser and system API calls, performing a forced `flush()` after every successful interaction.

### 🛡️ Anti-Bot Mitigation: Virtual Cursor & Global Key Interception (Android TV)
Introduced an advanced system to overcome Cloudflare on non-touch devices (TV).
- **Global Key Interception:** In `WebViewResolver.kt`, the main container intercepts remote control keys via `dispatchKeyEvent`. This prevents the WebView from "stealing" focus and ensures the cursor remains always controllable by the user.
- **Virtual Cursor (Red Crosshair):** A high-visibility crosshair (`elevation = 100f`) allows the user to precisely point at the verification checkbox.
- **Precision Mouse Emulation:** Upon clicking the OK button, the system simulates a real mouse sequence (`Hover Move` -> `Mouse Down` -> `Mouse Up`). This signal is interpreted by Cloudflare as authentic human interaction, overcoming blocks that ignore simple simulated touches.
- **Full-Screen Bypass UI & Multi-language Support:** The verification window is now full-screen (`NoActionBar_Fullscreen`) with an anchored `RelativeLayout` layout. Cursor instructions are localized in all app languages (IT, ES, AR, FR, DE, EN).

## 3. Technical Limits and Challenges: Android TV 9 and below (Obsolete Browser)
**WARNING:** On systems with **Android TV 9.0 or lower**, the app would be structurally unable to function with protected providers using standard methods due to the obsolescence of the integrated browser engine.
- **Legacy Chrome (v66 or less):** Chrome versions integrated into these OS versions are now too old (often from 2018). Cloudflare identifies these engines as insecure or potential bots, making any automatic or standard D-Pad unlock attempt null.
- **Lack of Native Focus:** The Cloudflare verification checkbox is not focusable. Without the crosshair system and physical mouse simulation introduced in this refactoring, the app would remain infinitely stuck on the verification page.
- **Unique Solution:** Software mouse emulation and manual crosshair pointing represent the only technical way to allow content viewing on dated TV hardware.

## 4. Improvements Summary

| Area | Previous State | Current State | Impact |
| :--- | :--- | :--- | :--- |
| **Base Version** | - | **Streamflix 1.7.96** | Stable starting point |
| **TV Interaction** | Remote Blocked | Virtual Cursor & Global Intercept | Total control and guaranteed unlock |
| **Bypass UI** | Cropped Layout | Full-Screen RelativeLayout | Localized and visible instructions |
| **Speed** | Sequential | Parallel (async/await) | Simultaneous category loading |
| **Anti-Bot** | Complex TV Challenges | UA Mobile Pixel 7 Sync | Simpler and faster challenges |
| **Network** | Long Timeouts | Adaptive Timeouts (5s check) | Immediate transition to bypass |

---
*Documentation updated: Android TV 9 Legacy WebView Optimization and Cursor Guide Localization (2026).*
