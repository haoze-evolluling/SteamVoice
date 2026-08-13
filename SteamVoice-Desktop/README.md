# SteamVoice Desktop

Windows Wails desktop sender for SteamVoice Android receivers. It discovers `_steamvoice._udp.local.` services, captures the default Windows render endpoint through WASAPI loopback, and sends 48 kHz stereo PCM through UDP.

## Development

Install a Windows C/C++ compiler (Visual Studio Build Tools with the Desktop C++ workload, or MinGW-w64) and ensure it is on `PATH`. The WASAPI backend uses `malgo`, which requires CGO.

```powershell
$env:CGO_ENABLED = "1"
cd frontend; npm install; npm run build; cd ..
wails dev
```

Use `wails build` for a production executable. Build Wails from a terminal that has the C++ compiler environment loaded.
