# Integrated Audit Report — SyncStageReceiver

Audit date: 2026-04-23
Scope: deep audit of the 3 connected repos — `SyncStageReceiver` (this repo), `SyncStageController`, `MFG-admin` — covering end-to-end cycle, modules, logic, performance, data, memory leaks, and automation gaps.

> Naming note: despite the "SyncStage" branding, this is NOT the SyncStage audio-session SDK. The system is a **metro-train station video-trigger platform**: Controller detects trains via GPS/BLE/UHF-RFID and commands Receivers (Xiaomi TV boxes mounted in trains/stations) to play synchronized videos. MFG-admin is the fleet/provisioning portal (Next.js + Firebase).

---

## 1. System architecture (cross-repo)

```
                       +-------------------+
                       |    MFG-admin      |   (Next.js 15 + Firebase)
                       |  - Firestore      |
                       |  - Cloud Fns      |
                       |  - Storage (APKs) |
                       +---------+---------+
                                 |
         authKey  ───►  exchangeAuthKeyForToken (callable)  ───►  custom token
                                 |
         +-----------------------+------------------------+
         |                                                |
         v                                                v
+------------------+       LAN (TCP/UDP/HTTP)     +------------------+
|  Controller      |  <─────────────────────────▶ |   Receiver       |
|  (Android/Kotlin)|  :12345 cmd, :8080 stream,   | (Android/Kotlin) |
|  GPS/BLE/UHF     |   UDP 239.0.0.1:8888,        | ExoPlayer kiosk  |
|  Hilt + Firestore|   NSD _syncstage._tcp        |                  |
+------------------+                              +------------------+
```

Both Android apps authenticate to Firestore using the custom token from MFG-admin and read/write a shared schema (`trains`, `devices`, `playlists`, `videos`, `receiver_statuses`, `controller_logs`, `playback_logs`, `trigger_logs`, `device_update_logs`, `app_updates`, `authKeys`, `metroLines`, `rfid_reader_logs`, `simple_*_reports`).

---

## 2. End-to-end cycle — combined flow

| # | Step | Owner | Status |
|---|------|-------|--------|
| 1 | Admin creates train + devices | MFG-admin `src/actions/{trains,devices}.ts` | OK |
| 2 | Admin generates `authKey` | MFG-admin `src/actions/authKeys.ts:22` | ⚠ no expiry / no one-time-use |
| 3 | Device boots → calls `exchangeAuthKeyForToken` | `functions/index.ts:16` | ⚠ same key reuse collapses multiple devices to one UID |
| 4 | Device reads its config via `trainId` claim | Controller + Receiver | ⚠ no device-id handoff; Receiver must fabricate its own deviceId |
| 5 | Controller advertises NSD `_syncstage._tcp`; Receiver listens on :12345 | Controller `network/DeviceDiscovery.kt`, Receiver `NetworkServiceAdvertiser.kt:26-75` | OK but no retry if NSD registration fails |
| 6 | Controller pushes `SYNC_PLAYLIST` → Receiver downloads, SHA-256 verifies, commits | Receiver `SyncHandler.kt:51-146` | OK |
| 7 | Trigger (BLE/GPS/UHF) → Controller sends `PLAY_TIMELINE` multicast + TCP | Controller `services/NetworkService.kt:~297` | OK |
| 8 | Receiver plays, watchdog self-heals ExoPlayer stalls | Receiver `PlayerManager.kt:279-340` | OK |
| 9 | Receiver reports playback → Controller → Firestore | Receiver `CommandReceiverService.kt:205-224` → Firestore `playback_logs` | OK |
| 10 | Admin deploys OTA update | MFG-admin `src/actions/updates.ts:27` + Controller `domain/OtaUpdateManager.kt` | ⚠ no server-side state machine; stuck `CONNECTING`/`INSTALLING` never reconciled |
| 11 | Device heartbeat / offline detection | nowhere | ⚠ **missing entirely** — `devices.online` is never flipped false |
| 12 | Revoke authKey / disable device | MFG-admin `deleteAuthKey` | ⚠ does not call `revokeRefreshTokens`; prior tokens valid for up to 1h |
| 13 | End-session / stop playback loop | — | ⚠ **no command defined**; playback loops forever |

---

## 3. Receiver-specific findings

### 3.1 Architecture
- Foreground Service `CommandReceiverService` + bound `MainActivity`, plus `SyncHandler`, `PlayerManager`, `MulticastReceiver`, `StreamingServer`, `WifiReconnectManager`, `LocalPlaybackLogger`, `KioskManager`, `BootReceiver`.
- Three communication channels: TCP direct (:12345), UDP multicast (239.0.0.1:8888), P2P HTTP localhost :8080 for streaming.
- Watchdog-driven ExoPlayer recovery (`PlayerManager.kt:279-340`) with nuclear rebuild on persistent stalls.

### 3.2 Critical issues (fix now)
| # | File:line | Issue |
|---|-----------|-------|
| R1 | `app/.../WifiReconnectManager.kt:33-34` | **Hardcoded WiFi SSID `T2L2MFG` + password in source**. Leaked in APK and git history. Move to encrypted config / Firebase Remote Config. |
| R2 | `PlayerManager.kt:1025-1032` | Unchecked Gson deserialization of saved playlist → crash on malformed JSON. Wrap in try/catch with fallback. |
| R3 | `CommandReceiverService.kt:180-256` | Feedback sender created per-client but not thread-safe; concurrent writes from playback callbacks + sync progress can interleave. Lock `clientOutput.println()` calls. |
| R4 | `SyncHandler.kt:60-146` | `activeSyncJob` race: new `SYNC_PLAYLIST` during cancel() can leave both old + new jobs writing. Use mutex or `AtomicReference`. |
| R5 | `PlayerManager.kt:404-438` | `rebuild()` releases old ExoPlayer before building new; if build throws, `exoPlayer` is null → NPE on next call. Build first, then release. |

### 3.3 Memory-leak risks
| # | File:line | Issue |
|---|-----------|-------|
| RL1 | `CommandReceiverService.kt:97, 324` | `scheduleWakeLockRenewal` postDelayed has no cancel path before `job.cancel()` in onDestroy. |
| RL2 | `PlayerManager.kt:44, 280, 315` | `playbackReportRunnable`, `watchdogRunnable`, `missingFileRetryRunnable` only cancelled by `releasePlayer()`. If PlayerManager discarded otherwise, runnables fire on dead state. |
| RL3 | `MulticastReceiver.kt:20, 30-58` | `CoroutineScope(IO + SupervisorJob)` never cancelled unless `stop()` called explicitly. |
| RL4 | `WifiReconnectManager.kt:82, 98` | `NetworkCallback` only unregistered in `stop()`; if call is skipped the callback leaks with Context reference. |
| RL5 | `MainActivity.kt:156-158` | `playbackHandler`/`syncHandler` hold Context via FileHandler/PlayerManager; null out in `onDestroy`. |

### 3.4 Performance
- `SyncHandler.kt:164-181` — retry backoff can stall 12+ s per file; no per-download timeout.
- `PlayerManager.kt:889-898` — `buildValidMediaItems()` stats every file sequentially; O(n) filesystem calls.
- `PlayerManager.kt:856-883` / `:791` — `System.gc()` on UI thread blocks playback. Remove or move off UI thread.
- `CommandReceiverService.kt:260-306` — 20s blocking `readLine()` per client.

### 3.5 Incomplete / automation gaps
- No provisioning contract with MFG-admin — WiFi & device ID must be hardcoded / generated locally.
- `PlayerManager.kt:1014-1036` 24 h resume limit — reboot after a day loses playback.
- `NetworkServiceAdvertiser.kt:26-75` — NSD register failure is not retried.
- No persistent sync checkpoint — interrupted sync re-downloads everything.
- `TimeManager.kt:28-48` — TrueTime failure silently falls back to local clock with no drift watchdog.

### 3.6 Minor
- Use namespaced intent action `${packageName}.COMMAND_RECEIVED` (`MainActivity.kt:202-210`).
- `StreamingServer.kt:33-36` path-traversal check should whitelist filenames, not just canonical path.
- `KioskManager.kt:32-40` `setGlobalSetting` result not verified.

---

## 4. Cross-system critical issues

The following are the blocker items that affect the **whole** system:

1. **MFG-admin committed a live Firebase service-account private key** (`studio-2234528777-bb9bd-firebase-adminsdk-fbsvc-2aa772d4dd.json`). Anyone with read access owns the entire Firebase project and thus every device. **Rotate immediately in GCP, purge from git history (BFG / git-filter-repo), rotate again.**
2. **Privilege escalation in Firestore rules** — `firestore.rules` lets any signed-in user update `users/{uid}.role`, self-promoting to Admin. Plus most server actions (`addTrain`, `deleteTrain`, `addDevice`, `generateAuthKey`, `toggleUserRole`, `createUser`, `resetUserPassword`) lack `requireAdmin(uid)`.
3. **Storage rules `allow write: if request.auth.uid != null`** on `/apks/{apkId}` lets any provisioned device replace any APK → supply-chain compromise of every Controller/Receiver.
4. **`receiver_statuses` + log collections writable by any signed-in device** without ownership check — one compromised device can corrupt the fleet view or blow the Firestore bill.
5. **Hardcoded WiFi credentials in Receiver** (R1 above).
6. **No device-heartbeat function** — `devices.online` never flipped false; fleet view is permanently inaccurate.
7. **No OTA state-machine watchdog** — `device_update_logs` stuck in `CONNECTING`/`INSTALLING` forever when a device dies mid-install. Index on `status + nextRetryAt` exists but no worker consumes it.
8. **`exchangeAuthKeyForToken`** has no one-time, expiry, or rate-limit check — an exfiltrated key is valid forever.
9. **`deleteAuthKey` does not call `revokeRefreshTokens`** — "revocation" is a soft 1-h expire.
10. **`next.config.mjs` ships with `typescript.ignoreBuildErrors` + `eslint.ignoreDuringBuilds`** — 40+ TS errors go to prod silently.

---

## 5. Prioritized action plan (receiver scope)

**P0 (do first)**
- Extract WiFi SSID/password to Remote Config or first-boot bootstrap payload.
- Thread-safe client output in `CommandReceiverService`.
- `try/finally` around `MediaMetadataRetriever` / any released resource.

**P1 (next)**
- Fix `PlayerManager.rebuild()` order (build new, then release old).
- Persistent sync checkpoint (SQLite `sync_state` table).
- Cancel `CoroutineScope` + handlers in every teardown path; add `SafeHandler` wrapper.
- NSD register retry-on-network-available.

**P2**
- Remove `System.gc()` from UI thread.
- Per-file download timeout in `SyncHandler`.
- Whitelist filenames in `StreamingServer`.
- 24 h resume cap → indefinite or config-driven.

**P3**
- Expose health telemetry (`ReceiverHealthMonitor`) to Controller → Firestore for alerting.
- androidTest covering boot → sync → play → reboot → resume.

---

## 6. References

Full per-repo findings are duplicated in the same-named `AUDIT_REPORT.md` on branches `claude/audit-integrated-repos-SyWQI` of:
- `Kmatat/SyncStageReceiver-Claude`
- `Kmatat/SyncStageController-claude`
- `Kmatat/MFG-admin`
