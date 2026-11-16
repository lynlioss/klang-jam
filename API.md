# Plugin API 1 extension protocol

All messages are Android `Messenger` messages. `data.token` is supplied by Klang
and echoed by the plugin. `data.json` is a UTF-8 JSON object string bounded at
64 KiB. The reply endpoint is `Message.replyTo`. Klang authenticates the installed
service UID; Jam verifies that its caller UID belongs to Klang. Code never runs
inside the host process.

| Direction | what | JSON |
|---|---:|---|
| Host → plugin | 1 | `{apiVersion:1, settings:{...}}`; configures/attaches |
| Host → plugin | 2 | `{action:"open"/"link"/panel action, ...fieldValues}` |
| Host → plugin | 3 | Playback snapshot: current/actual next metadata, position/duration, elapsed monotonic timestamp, ready/playing, shuffle/repeat |
| Host → plugin | 4 | Detach/end session |
| Plugin → host | 10 | UI state: `panel`, `badge`, `trackAction`, `interceptControls` |
| Plugin → host | 11 | `{requestId, operation, ...}` capability request |
| Host → plugin | 12 | `{requestId, ...result}` or `{requestId,error}`; optional `data.audio` ParcelFileDescriptor |

Panel nodes use `type`, `label`, and node-specific fields: `supporting`, `action`,
`id`, `value`, `input`, `maxLength`, `secret`, `enabled`, `checked`, `badge`,
`style`, `shareUrl`, `eyebrow`, `placement` and `presentation`. Up to two buttons
with `placement: "footer"` stay below the scrolling content. A button with
`presentation: "icon"` uses its label for accessibility and a tooltip.
Panel size is limited to forty nodes. Native UI takes
color, shape and motion from Klang's current content theme.

`badge:{visible,label,loading}` contributes the animated center control.
`trackAction:{trackId,action,label}` replaces the current Favorite control with a
Download action. `interceptControls` routes playback/queue commands through the
plugin, including media notification controls. A peer's group controls are
server-checked before the leader executes them. Queue edits are rejected in Jam.

| Operation | Capability | Contract |
|---|---|---|
| `audio.read` | `audio.read` | `trackId` must be the current or actual next local audio; read-only descriptor |
| `audio.find` | `audio.receive` | `sha256,title,artist`; hash-match a bounded list of local candidates, no arbitrary URI |
| `audio.receive` | `audio.receive` | `sha256,bytes`; host-owned write pipe, exact-size/checksum/container validation |
| `audio.commit` | `audio.receive` | `sha256`; await validated receiver output |
| `audio.save` | `audio.receive` | `sha256,title`; atomic publish to Music/Klang |
| `audio.release` | `audio.receive` | `keep:[hashes]`; delete no-longer-needed temporary audio |
| `playback.apply` | `playback.control` | Validated `sha256` or matched local hash; metadata, `positionMs`, `playing`, bounded `speed` |
| `playback.command` | `playback.control` | `command:play/pause/next/previous/seek/shuffle/repeat` plus command fields |
| `session.clear` | — | Restore previous paused receiver queue; cancel receipts and delete temporary files |

Commands never accept remote filesystem paths, content URIs, executables, MIME
claims or unbounded allocations. Host validation is independent of plugin transfer
validation. The protocol allows future extensions to own their network stack,
session behavior and panel content without adding service-specific code to Klang.
