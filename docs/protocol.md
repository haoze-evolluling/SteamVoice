# SteamVoice v4 protocol

Android receivers register `_steamvoice._udp.local.` through NSD (TXT `role=speaker`, stable `settings_device_id`, codec/format parameters) and listen on UDP 40125 for audio, feedback and control datagrams. Desktops advertise with TXT `role=pc` on the same service type and listen on UDP 40126 for connection control. Discovery is mDNS in both directions; senders re-query periodically so every live receiver shows up.

All datagrams are big-endian binary packets starting with a 4-byte magic, protocol version (4) and per-type layout. Unknown or mismatched-version packets are dropped.

## Audio (magic `SV01`, 40-byte header + Opus payload)

Header: magic (4), version (1), codec (1, Opus=1), sample rate (4), channels (1), bitrate bps (4), session ID (4), sequence (4), payload length (2), frame milliseconds (2), flags (1, bit0 FEC / bit1 DTX), reserved (3), capture timestamp `TimestampNs` (8, nanoseconds in the sender's stream clock). 48 kHz stereo, 10 or 20 ms frames. The timestamp of each frame's first sample lets receivers schedule multi-device synchronized playback; a receiver measures its clock offset against the sender via NTP-style exchanges (see `SVTS` below) and maps the timestamps onto its local monotonic clock.

While sessions exist the sender pads silent WASAPI idle periods with DTX-encoded silence frames at the active frame cadence, so receivers never hit their audio-silence timeout while nothing is playing on the PC.

## Receiver feedback (magic `SVCT`, 35 bytes)

Magic (4), version (1), type=1 (1), reserved (2), session (4), highest sequence (4), received count (4), lost count (4), queue excess (2), current bitrate (4), then the calibration block appended in v4.2: sync state (1, 0 unknown / 1 calibrating clock / 2 aligned / 3 synchronized playback), median clock offset ms (2, signed), exchange RTT ms (2). The first 30 bytes are unchanged from the previous layout, so older senders keep working; the sync state drives both apps' calibration UI (检测 → 计算 → 同步 → 完成).

Feedback is sent roughly every 200 ms while a session is active. The queue value reports backlog beyond the receiver's sync budget, not total buffering, so the sender's 48–192 kbps adaptation does not misread steady-state buffering as congestion.

## Time sync (magic `SVTS`, 40 bytes)

Magic (4), version (1), kind (1, request/response), reserved (2), then t1/t2/t3/t4 as four 8-byte nanosecond fields (the response carries t1–t3; t4 is recorded locally by the requester). Receivers exchange requests every 250 ms until the clock estimate converges (≥4 samples), then every 2 s, and answer with the sender's stream-clock reading so all receivers align to one timebase.

## Connection control (magic `SVCR`, variable)

Magic (4), version (1), kind (1), reserved (2), then for requests: device ID (NUL-terminated) + requester name; for responses: device ID (NUL-terminated) + allow byte; for goodbye: device ID only. Either side may initiate; the peer must authorize (with optional "always allow" persisted by stable device ID). Requests are retransmitted every 1.5 s; prompt lifetimes on both ends are aligned (36 s) so a late approval still completes the handshake. A receiver that stops hearing audio for 10 s tears the session down and notifies the sender with a goodbye so both ends agree on the connection state.

## Settings sync (magic `SVCS`, 40 bytes)

Magic (4), version (1), type=1 (1), reserved (2), bitrate bps (4), frame ms (2), reserved (2), updated-at wall clock ms (8), device ID (16, NUL padded). Sent by the desktop on connect; the receiver applies the payload only when it is newer than its locally edited settings, keyed by (updatedAt, deviceId).
