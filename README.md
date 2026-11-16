# Klang Jam

An optional, recommended **Plugin API 1** extension for [Klang](https://github.com/lynlioss/klang). Install and enable it in Settings → Plugins. Nothing installs automatically.

Start a Jam from Now Playing → ⋮ → Jam. Up to four devices join with a four-letter/number code, such as `xb7e`. Share `https://jamklang.pages.dev/xb7e`, see every member's randomly generated color/animal name, and identify yourself with **You**. Other devices see the leader's **Leader** label. The leader can enable group controls or disband; members can leave. A lone group expires after five minutes. A vanished leader ends the group after its heartbeat expires.

The panel is rendered natively by Klang with its current Material 3 Expressive typography, shapes, motion and album-derived color scheme. Jam supplies the complete panel model, menu contribution, center Jam/Sync pill and received-song download action. Its Android service owns all session, signaling, transfer and synchronization behavior under a separate UID. No WebRTC or Supabase SDK is added to Klang.

## Backend setup

The client is configured for `https://pbvojjvjboeykhrbjrgo.supabase.co`. Its publishable key is a public client credential, not an administrative credential. Room membership and controls are enforced server-side.

1. Enable **anonymous sign-ins** in Supabase Authentication → Sign In / Providers.
2. Run [the SQL migration](supabase/migrations/202611160001_jam.sql) in the SQL editor. It creates private tables, guarded RPCs and receive-only private Realtime broadcasts. The four-person capacity is enforced with row locking, and joins are limited to ten attempts per user per minute. Only the leader can publish playback; peer control requests are allowed only when group controls are enabled. Do not add a permissive broadcast INSERT policy.
3. Enable **pg_cron** under Database → Extensions, then run the `cron.schedule` statement at the end of the migration. This ends and deletes abandoned/lone rooms even when clients are gone.
4. Keep Realtime enabled. Membership, signaling, track readiness, clock exchanges and playback anchors use server-authored private Realtime broadcasts. The tables need no public read permissions and no Postgres Changes publication.

The publishable key cannot apply migrations or turn on anonymous authentication. Those Dashboard steps must be completed before any device can create a Jam.

## Direct WebRTC and optional relay

Jam works **without TURN** when the devices can reach each other. It uses a configurable STUN server and encrypted, reliable, ordered WebRTC data channels. Every participant connects to the leader; only the leader shares audio. NATs and firewalls on some mobile networks prevent direct connections. The plugin reports that failure; a TURN relay is optional, rather than required.

The Supabase URL and publishable key are configured when the plugin is built and cannot be changed in the app. Jam's plugin settings contain the STUN URL, optional TURN URL, username and masked password. Leave TURN blank for direct connections. A TURN relay carries encrypted WebRTC traffic, rather than plaintext audio. Changing connection settings applies to the next Jam. Production relay credentials should be short-lived; a credential issuer can be added without changing Klang.

## Audio and synchronization

The leader reads only its current or actual next queue item through the host descriptor API. File contents are hashed and transmitted in ordered 16 KiB chunks with bounded send buffering. Receivers first check for an exact hash match among local title/artist candidates; existing files need no transfer. A received file is checked for exact size and SHA-256 by the plugin and independently by Klang. Klang checks container signatures and uses MediaExtractor to reject non-audio tracks before allowing playback or saving. No remote filename, URI or path is accepted. Maximum file size is 256 MiB; temporary storage is bounded.

After a join or track change, the leader waits for every member's validated audio. The **Sync** pill shows connection/transfer waits. Four-timestamp monotonic clock exchanges select the lowest round-trip sample. Playback anchors let a device that downloads two seconds later seek to the correct position; periodic drift correction uses small speed adjustments and seeks for larger errors. Resume uses a future anchor, allowing prepared devices to start together. The actual next item, respecting shuffle/repeat, begins transferring ten seconds before the current track ends. Slow networks still require a synchronization wait at the transition.

Android scheduling, audio decoding and Bluetooth output latency vary by device. This synchronizes media positions; it does not promise sample-accurate acoustic alignment across arbitrary hardware. Multi-device playback and network switching need testing on real devices before claiming a measured timing tolerance.

Received audio lives in app-private temporary caches and is removed on leave, disband, plugin disable, service shutdown and next startup after a process kill. A Download action replaces Favorite for a received song until explicitly saved to Music/Klang. User-saved music remains in the library. Playlist edits during Jam are blocked; choose the leader's queue before creating a session. Play, pause, skip, seek, shuffle and repeat are available through group controls.

## Build and publish

`Build Jam` runs on GitHub-hosted Ubuntu for pushes/PRs and uploads APK/checksums. Tags matching the manifest, for example `v1.0.0`, publish stable releases with `plugin.apk`, `plugin.apk.sha256`, `plugin.json` and `plugin.json.sha256` for Klang's plugin installer.

Stable releases require `JAM_KEYSTORE_B64`, `JAM_KEYSTORE_PASSWORD` and `JAM_KEY_ALIAS` repository secrets, ensuring Android update signing continuity. Optional `SUPABASE_PUBLISHABLE_KEY` secret and `SUPABASE_URL` variable override client defaults. The build regenerates nonhuman dictionaries using the MIT-licensed [unique-names-generator](https://github.com/andreasonny83/unique-names-generator) build dependency.

```sh
npm ci --ignore-scripts
npm run names
npm test
./gradlew :plugin:assembleRelease :plugin:lintRelease :plugin:testDebugUnitTest
```

No app build is necessary to generate dictionaries or run invite-route tests. The host and plugin must both include the new API 1 extension capabilities.

## Invite site and App Links

`web/` contains the Cloudflare Pages invite page and worker. The worker validates four-character routes, escapes metadata, and preserves `/.well-known/assetlinks.json`. `klang://jam/<code>` works independently of Android domain verification. Deep links open the Join panel with the code; they do not join without a tap.

Discord and X previews use the title “Join this Jam on Klang!” and a 1200 × 630
PNG at `/share/v1/<code>.png`. The worker draws each invite code beside the Klang
logo from pre-rendered pixels, so previews need no browser or external image API.
To update the source artwork, install Pillow and run
`python3 scripts/generate-share-assets.py`. Commit the generated `web/assets/`
files with the sources in `scripts/share-assets/`. Change the image URL version
when artwork changes, since image responses are cached as immutable.

For direct HTTPS App Links, generate metadata from the **Klang app's actual signing certificate**, not the Jam certificate:

```sh
python3 scripts/assetlinks.py AA:BB:... # full SHA-256 fingerprint
```

Set repository variable `KLANG_CERT_SHA256`, secrets `CLOUDFLARE_API_TOKEN` and `CLOUDFLARE_ACCOUNT_ID`, then manually run `Jam invite site`. It deploys to the existing `jamklang` Pages project. Browser fallback remains available until verified links are deployed.

## License

GPL-3.0, matching Klang. The color/animal dictionary license is in `UNIQUE-NAMES-LICENSE`. WebRTC and its SDK carry their upstream licenses.
