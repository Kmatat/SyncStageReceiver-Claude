# Code Audit — SyncStageReceiver
Scope: functional bugs and performance per module. Security/config/docs excluded by request.

System context: Controller (GPS/BLE/UHF trigger source) pushes commands over LAN (TCP :12345, UDP multicast 239.0.0.1:8888, HTTP :8080). Receiver is a kiosk Android app on Xiaomi TV boxes playing ExoPlayer content with watchdog self-healing.

---

## Module map
| Module | Role |
|---|---|
| `MainActivity` | Bound Activity, lifecycle glue, auto-recovery loop |
| `CommandReceiverService` | Foreground service, TCP server :12345, wake lock, NSD, multicast |
| `PlayerManager` | ExoPlayer wrapper, playlist, watchdog, timeline sync |
| `SyncHandler` | Downloads/verifies/commits playlist files |
| `FileHandler` | Filesystem ops, cleanup |
| `MulticastReceiver` | UDP 239.0.0.1:8888 listener |
| `StreamingServer` | Local HTTP :8080 (peer streaming) |
| `NetworkServiceAdvertiser` | NSD `_syncstage._tcp` register |
| `WifiReconnectManager` | WiFi watchdog + reconnect |
| `LocalPlaybackLogger` | File-backed playback event log |
| `KioskManager` | Device-owner kiosk setup |
| `TimeManager` | TrueTime NTP sync |
| `BootReceiver` | Cold-boot entry |

---

## Functional errors

### PlayerManager
- **`PlayerManager.kt:404-438` — `rebuild()` releases the old ExoPlayer before constructing the new one.** If the new `ExoPlayer.Builder(...).build()` throws (low memory, decoder init), `exoPlayer` is left null → NPE on the next `playPlaylist()`. Build first, swap, then release old.
- **`PlayerManager.kt:1025-1032` — unchecked `Gson.fromJson` on saved playlist state.** Malformed JSON (truncated on power loss) throws `JsonSyntaxException` that isn't caught → crash on resume. Wrap in try/catch, drop the state file on parse failure.
- **`PlayerManager.kt:~290` — `MediaMetadataRetriever.release()` not in `finally`.** Any `setDataSource` throw leaks the retriever and holds the file handle.
- **`PlayerManager.kt:44, 280, 315` — Handler runnables (`playbackReportRunnable`, `watchdogRunnable`, `missingFileRetryRunnable`) start in `init()` but only stop inside `releasePlayer()`.** If the PlayerManager is replaced or the owning scope dies without calling `releasePlayer()`, runnables keep firing on stale state.
- **`PlayerManager.kt:655-742` — `timelineSyncRunnable` (2 s) has no cancel path if `PLAY_TIMELINE` is superseded by `STOP`/new `PLAY`.** Two concurrent timeline-sync loops can correct the position against each other.

### CommandReceiverService
- **`CommandReceiverService.kt:180-256` — `clientOutput.println(...)` writes are not guarded across code paths.** A `synchronized(socketLock)` exists for some send paths (lines 328-340) but `sendFeedback()` launched in a coroutine writes without the same lock, so concurrent writes from playback callbacks + sync progress can interleave / tear feedback lines.
- **`CommandReceiverService.kt:97, 324` — `scheduleWakeLockRenewal()` calls `handler.postDelayed(...)` recursively; the matching `removeCallbacks` happens after `job.cancel()` in `onDestroy` (line 359).** Ordering means a pending renewal can fire between cancel and removeCallbacks, acquiring a WakeLock on a dead service.
- **`CommandReceiverService.kt:260-306` — `clientInput.readLine()` blocks up to the 20 s socket timeout.** A half-open TCP connection holds a server thread for 20 s per client before detection.
- **`CommandReceiverService.kt:143-151` — `MulticastReceiver.start()` is called without wrapping socket creation in a try/catch.** A `SocketException` (no permission / no connectivity) propagates out of `onCreate` and aborts service start with no fallback.

### SyncHandler
- **`SyncHandler.kt:60-146` — race on `activeSyncJob`.** New `SYNC_PLAYLIST` calls `activeSyncJob?.cancel()` and starts a new job, but cancel is cooperative — the old job may still be inside `commit()` when the new job writes, corrupting the playlist folder. Protect with a `Mutex`, or move cancel + start under `AtomicReference`/`Job.cancelAndJoin`.
- **`SyncHandler.kt:82-85` — random 0-3 s JITTER delay applies to the *first* sync as well.** First sync after boot starts up to 3 s late for no benefit (thunder-herd only matters on repeat sync). Skip jitter on first run.
- **`SyncHandler.kt:51-146` — `.tmp` files are not verified as fully written before move.** If the download stream is cut between the last `write()` and `close()`, the truncated `.tmp` can pass basic length checks. Verify SHA-256 *after* close() and *before* the atomic rename.

### FileHandler
- **`FileHandler.kt:54-97` — `cleanupOldFiles()` checks `file.delete()` result but does not retry transient failures** (FAT32 locks, antivirus scanners on some OEMs). Add a single 200 ms retry.

### MulticastReceiver
- **`MulticastReceiver.kt:20, 30-58` — `CoroutineScope(IO + SupervisorJob())` never cancelled unless `stop()` is invoked.** When the owning service is rebuilt by Android without `stop()` being reached, the multicast socket + scope survive into a second process lifetime, doubling multicast processing.

### WifiReconnectManager
- **`WifiReconnectManager.kt:56-70, 91` — `NetworkCallback` registered in `start()` only unregistered in `stop()`.** Any early-exit path in MainActivity skips the unregister and the callback keeps a Context reference.

### MainActivity
- **`MainActivity.kt:213-225` — auto-resume 5 s timer vs Controller `PLAY` race.** If the Controller sends `PLAY` 5-6 s after Receiver boot, both the local resume and the remote PLAY fire against PlayerManager. Use a monotonic state version counter or a single `startPlayback(reason)` entry point that wins/loses deterministically.
- **`MainActivity.kt:156-158` — `playbackHandler` / `syncHandler` are not nulled in `onDestroy`.** Handlers retain references to FileHandler/PlayerManager; if a sync is in-flight the stale `FeedbackSender` writes to a closed socket.
- **`MainActivity.kt:343-398` — auto-recovery loop (10 s) calls `service.restart()` when the server socket is dead.** `restart()` has no back-off; pathological network downs cause a hot restart loop.
- **`MainActivity.kt:400-413` — `onDestroy()` calls `unregisterReceiver` without try/catch.** If receiver was already unregistered (e.g. by config-change replay) this throws `IllegalArgumentException` and crashes shutdown.

### NetworkServiceAdvertiser
- **`NetworkServiceAdvertiser.kt:26-75` — NSD registration listener set to null on error with no retry.** First-boot race (device has no IP yet) leaves the device undiscoverable. Retry on `ConnectivityManager.NetworkCallback.onAvailable`.

### StreamingServer
- **`StreamingServer.kt:33-36` — path check via `canonicalPath` only.** Accepts any filename inside the folder including partial `.tmp` or previously synced files not in the active playlist. Whitelist against the currently-synced filename set.

### TimeManager
- **`TimeManager.kt:28-48` — TrueTime failure silently falls back to local offset** (`offsetMs = 0`) and retries every 60 s. No watchdog detects clock drift > N minutes vs last successful TrueTime. Add a `lastGoodAt`/`drift` signal that can veto playback starts requiring timeline accuracy.

---

## Performance issues

- **`SyncHandler.kt:164-181` — retry backoff `RETRY_DELAY_MS * (attempt+1)` stalls up to ~12 s per file (`2+4+6` + inter-file 100 ms).** No per-download timeout. A single hung connection blocks the entire sync.
- **`PlayerManager.kt:889-898` — `buildValidMediaItems()` iterates and stats each file (`file.exists()`, `file.length()`) sequentially on Main-dispatch path.** For 100+ files this is O(n) filesystem calls before the first frame. Batch-stat or cache in `SyncHandler`.
- **`PlayerManager.kt:791, 856-883` — `reloadPlaylistIfFilesAvailable()` triggers `System.gc()` on the UI thread.** GC pauses stutter playback on low-end TV boxes. Remove.
- **`LocalPlaybackLogger.kt:119-137` — single-thread executor takes a file lock per write.** Bursty playback events back up the queue; log writes starve. Replace with a size-capped ring buffer that flushes once per second.
- **`CommandReceiverService.kt:260-306` — 20 s blocking `readLine()`** (see functional section — also a perf issue since it ties up one thread per half-open client).
- **`PlayerManager.kt:279-340` — watchdog timer uses a 2 s `Handler` loop with 2 consecutive `stuck` reads to fire.** On slow 10-25 fps decoders, 4 s gap can be legitimate. Make the stuck-detect compare current position delta against playbackSpeed instead of absolute time.
- **`CommandReceiverService.kt:154-322` — single-threaded TCP accept loop.** One slow client blocks command delivery to others. Move per-client I/O to a bounded `Executors.newFixedThreadPool`.
- **`MulticastReceiver.kt:12-103` — dedup hash cache unbounded.** Cache grows each unique multicast packet. Cap size and evict LRU.

---

## Prioritized fix order
1. `PlayerManager.rebuild()` order, `MediaMetadataRetriever` in `finally`, Gson try/catch.
2. `CommandReceiverService` output lock + postDelayed cancel order.
3. `SyncHandler` mutex around activeSyncJob + SHA-256 after close.
4. Remove UI-thread `System.gc()`.
5. Per-download timeout in `SyncHandler` + start without jitter.
6. NSD retry on network-available.
7. Handler / CoroutineScope / NetworkCallback teardown audits.
8. Watchdog heuristic tied to playback speed.
