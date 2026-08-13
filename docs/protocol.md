# SteamVoice v1 protocol

Android registers `_steamvoice._udp.local.` through NSD and listens on its advertised UDP port. The desktop app uses mDNS for discovery.

Each UDP datagram is a big-endian binary packet: `SV01` magic (4 bytes), version (1), channels (1), sample rate Hz (4), bits per sample (2), session ID (4), sequence number (4), payload length (2), reserved (2), then signed 16-bit little-endian stereo PCM. v1 requires 48,000 Hz, 2 channels, and 16 bits per sample. Packets carrying a different version or format are dropped.

The receiver establishes its active session from the first valid packet, drops duplicate/older sequence values, and plays after a 40 ms buffer target. A new session ID resets the queue. UDP is intentionally lossy; a missing packet is represented as silence by AudioTrack underrun behavior.
