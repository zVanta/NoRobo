# RobocallGuard — Android Call-Screening Starter

A minimal Android app that screens incoming calls and blocks robocalls/spam
using Android's `CallScreeningService`.

## What it does
- Requests the "Caller ID & spam" role (Android 10+, API 29+).
- Screens every call to your native dialer.
- Applies a simple rule engine: allowlist, blocklist, prefix rules, regex rules.
- Blocks matched callers; everything else passes through by default.

## Prerequisites
- JDK 17
- Android SDK (Platform 34 + build-tools 34.x)
- Gradle 8.5+ (or generate the wrapper, below)

## Build (VS Code)
1. Create `local.properties` in the project root with:
      sdk.dir=/path/to/Android/Sdk
   (or set ANDROID_HOME / ANDROID_SDK_ROOT in your environment)
2. Generate the Gradle wrapper once:
      gradle wrapper --gradle-version 8.7
3. Build the debug APK:
      ./gradlew assembleDebug
   Output: app/build/outputs/apk/debug/app-debug.apk

## Install on a device
    adb install app/build/outputs/apk/debug/app-debug.apk
Then open the app and tap "Grant Call Screening Role".

## Customize the rules
Edit `CallRules.kt`:
- allowlist       -> numbers that always ring through
- blocklist       -> exact numbers to always reject
- blockedPrefixes -> area/country-code prefixes to reject (e.g. "1800")
- blockedPatterns -> regex rules against the raw digits

## Important limitations (read this)
- Only intercepts calls through the NATIVE phone dialer. It will NOT see calls
  arriving inside VoIP softphone apps (e.g. magicJack, WhatsApp, Google Voice).
- Number-based only: it cannot listen to or analyze call audio, so it cannot
  detect a scam by "script." Spoofed numbers that rotate each call can slip through.
- Only ONE call-screening app can be active at a time; enabling this disables
  other screeners (Hiya, Truecaller, etc.) for the role.
- Emergency calls are not routed through screening.
- For Google Play, call-screening apps must request ROLE_CALL_SCREENING and
  disclose the capability; this starter is for personal/dev use.

## Suggested next steps (good DeepSeek prompts)
- Auto-allowlist from your contacts (needs READ_CONTACTS).
- "Allowlist-only" mode: block all unknown numbers and send to voicemail.
- Crowd-sourced spam database lookup (Hiya/Nomorobo API) inside onScreenCall.
- Local call log + reporting UI.
- Regex rules for your specific spammer prefixes.
