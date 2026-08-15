# SteamVoice Desktop

Windows Wails desktop sender for SteamVoice Android receivers. It discovers `_steamvoice._udp.local.` services, captures the default Windows render endpoint through WASAPI loopback, encodes 48 kHz stereo audio as 10 ms Opus frames, and sends them through UDP.

## Development

Install a Windows C/C++ compiler (Visual Studio Build Tools with the Desktop C++ workload, or MinGW-w64), libopus development headers/library, and `pkg-config`. The WASAPI backend and Opus encoder require CGO.

```powershell
$env:CGO_ENABLED = "1"
cd frontend; npm install; npm run build; cd ..
wails dev -tags steamvoice_opus
```

Use `wails build -tags steamvoice_opus` for a production executable. Build Wails from a terminal that has the C++ compiler and libopus environment loaded. Without the `steamvoice_opus` build tag the app intentionally reports that the encoder is unavailable.
