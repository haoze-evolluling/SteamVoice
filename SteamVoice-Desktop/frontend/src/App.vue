<script setup lang="ts">
import { computed, onMounted, ref, watch } from 'vue';
import { Connect, DiscoverDevices, Disconnect, GetStatus } from '../wailsjs/go/main/App';
import { EventsOn, WindowSetDarkTheme, WindowSetLightTheme, WindowSetSystemDefaultTheme } from '../wailsjs/runtime/runtime';

type Theme = 'light' | 'dark' | 'system';
type Settings = { bitrate: number; frameMs: number; theme: Theme; updatedAtMs: number; deviceId: string };
type Device = { name: string; host: string; port: number; id: string; codec: string; sampleRate: number; channels: number; bitrate: number; frameMs: number; supportedFrameMs: number[]; updatedAtMs: number; settingsDeviceId: string };
const settingsKey = 'steamvoice.desktop.settings.v2';
const deviceIdKey = 'steamvoice.desktop.device_id';
const deviceId = localStorage.getItem(deviceIdKey) ?? crypto.randomUUID();
localStorage.setItem(deviceIdKey, deviceId);
const defaults: Settings = { bitrate: 128000, frameMs: 10, theme: 'system', updatedAtMs: 0, deviceId };
const settings = ref<Settings>({ ...defaults });
const themeOptions: Theme[] = ['light', 'dark', 'system'];
const page = ref<'devices' | 'settings'>('devices');
const devices = ref<Device[]>([]);
const status = ref('未连接接收端');
const actualBitrate = ref(0);
const active = ref<Device | null>(null);
const supportedFrames = computed(() => active.value?.supportedFrameMs ?? (devices.value.length ? devices.value.flatMap((device) => device.supportedFrameMs) : [10, 20]));
const frameAvailable = computed(() => !active.value || supportedFrames.value.includes(settings.value.frameMs));

function normalizeSettings(raw: unknown): Settings {
  const value = raw as Partial<Settings> | null;
  return { bitrate: [64000, 96000, 128000, 192000].includes(value?.bitrate ?? 0) ? value!.bitrate! : defaults.bitrate, frameMs: value?.frameMs === 20 ? 20 : 10, theme: value?.theme === 'light' || value?.theme === 'dark' || value?.theme === 'system' ? value.theme : defaults.theme, updatedAtMs: Number(value?.updatedAtMs) || 0, deviceId: typeof value?.deviceId === 'string' && value.deviceId ? value.deviceId : deviceId };
}
function normalizeDevice(raw: any): Device {
  const values = raw?.supportedFrameMs ?? raw?.SupportedFrameMs ?? [raw?.frameMs ?? raw?.FrameMs ?? 10];
  const supportedFrameMs = (Array.isArray(values) ? values : [values]).filter((value) => value === 10 || value === 20);
  return { name: raw?.name ?? raw?.Name ?? 'Android device', host: raw?.host ?? raw?.Host ?? '', port: raw?.port ?? raw?.Port ?? 0, id: raw?.id ?? raw?.ID ?? raw?.Id ?? raw?.name ?? raw?.Name ?? '', codec: raw?.codec ?? raw?.Codec ?? '', sampleRate: raw?.sampleRate ?? raw?.SampleRate ?? 48000, channels: raw?.channels ?? raw?.Channels ?? 2, bitrate: raw?.bitrate ?? raw?.Bitrate ?? 128000, frameMs: raw?.frameMs ?? raw?.FrameMs ?? 10, supportedFrameMs: supportedFrameMs.length ? supportedFrameMs : [10], updatedAtMs: Number(raw?.updatedAtMs ?? raw?.UpdatedAtMs) || 0, settingsDeviceId: raw?.settingsDeviceId ?? raw?.SettingsDeviceID ?? '' };
}
function newer(a: { updatedAtMs: number; deviceId: string }, b: { updatedAtMs: number; deviceId: string }) { return a.updatedAtMs > b.updatedAtMs || (a.updatedAtMs === b.updatedAtMs && a.deviceId > b.deviceId); }
function touch(next: Partial<Settings>) { settings.value = { ...settings.value, ...next, updatedAtMs: Date.now(), deviceId }; }
function statusMessage(raw: any, fallback: string): string {
  const value = raw?.message ?? raw?.Message;
  return typeof value === 'string' && value.trim() ? value : fallback;
}
function applyTheme(theme: Theme) {
  document.documentElement.dataset.theme = theme;
  if (theme === 'dark') WindowSetDarkTheme(); else if (theme === 'light') WindowSetLightTheme(); else WindowSetSystemDefaultTheme();
}
async function discover() { devices.value = []; try { await DiscoverDevices(); } catch { status.value = '无法发现接收端'; } }
async function connect(device: Device) {
  if (!device.supportedFrameMs.includes(settings.value.frameMs)) { status.value = `该接收端不支持 ${settings.value.frameMs} ms 音频帧`; return; }
  try {
    await Connect({ Name: device.name, Host: device.host, Port: device.port, ID: device.id, Codec: device.codec, SampleRate: device.sampleRate, Channels: device.channels, Bitrate: settings.value.bitrate, FrameMs: settings.value.frameMs, SupportedFrameMs: device.supportedFrameMs, UpdatedAtMs: settings.value.updatedAtMs, SettingsDeviceID: settings.value.deviceId } as any);
    active.value = device; status.value = '已连接，正在发送电脑音频';
  } catch (error) { active.value = null; status.value = `连接失败：${error instanceof Error ? error.message : String(error)}`; }
}
async function disconnect() { try { await Disconnect(); active.value = null; status.value = '未连接接收端'; } catch { status.value = '断开连接失败'; } }
watch(settings, (next) => { localStorage.setItem(settingsKey, JSON.stringify(next)); applyTheme(next.theme); }, { deep: true });
onMounted(async () => {
  try { settings.value = normalizeSettings(JSON.parse(localStorage.getItem(settingsKey) ?? 'null')); } catch { settings.value = { ...defaults }; }
  applyTheme(settings.value.theme);
  try { const value: any = await GetStatus(); status.value = statusMessage(value, status.value); } catch { status.value = '无法获取连接状态'; }
  EventsOn('device:found', (raw: any) => { const device = normalizeDevice(raw); if (device.updatedAtMs && newer(device, settings.value)) { settings.value = { ...settings.value, bitrate: device.bitrate, frameMs: device.frameMs, updatedAtMs: device.updatedAtMs, deviceId: device.settingsDeviceId || settings.value.deviceId }; } const index = devices.value.findIndex((item) => item.id === device.id); if (index >= 0) devices.value[index] = device; else devices.value.push(device); });
  EventsOn('stream:status', (raw: any) => { status.value = statusMessage(raw, '状态未知'); actualBitrate.value = Number(raw?.bitrate ?? raw?.Bitrate) || 0; });
  discover();
});
</script>

<template>
  <main>
    <header>
      <div><p class="eyebrow">电脑音频发送端</p><h1>SteamVoice</h1></div>
      <div class="header-actions"><span :class="['status', active ? 'live' : '']">{{ status }}</span><button class="secondary" @click="page = page === 'settings' ? 'devices' : 'settings'">{{ page === 'settings' ? '返回' : '设置' }}</button></div>
    </header>

    <section v-if="page === 'devices'">
      <div class="section-head"><div><h2>可用接收端</h2><p>同一局域网内已启动接收服务的设备</p></div><button @click="discover">重新扫描</button></div>
      <div v-if="devices.length" class="devices"><article v-for="device in devices" :key="device.id"><div class="speaker">S</div><div><h3>{{ device.name }}</h3><p>{{ device.host }}:{{ device.port }} · 支持 {{ device.supportedFrameMs.join('/') }} ms</p></div><button v-if="active?.id === device.id" class="secondary" @click="disconnect">断开</button><button v-else @click="connect(device)">连接</button></article></div>
      <div v-else class="empty">正在扫描局域网接收端...</div>
    </section>

    <section v-else class="settings">
      <div class="section-head"><div><h2>设置</h2><p>音频参数会在下一次连接时生效</p></div></div>
      <fieldset><legend>初始 Opus 码率</legend><label v-for="bitrate in [64000, 96000, 128000, 192000]" :key="bitrate" class="choice"><input v-model="settings.bitrate" @change="touch({ bitrate })" type="radio" name="bitrate" :value="bitrate"><span>{{ bitrate / 1000 }} kbps</span></label></fieldset>
      <fieldset><legend>音频帧时长</legend><label v-for="frame in [10, 20]" :key="frame" class="choice" :class="{ disabled: !supportedFrames.includes(frame) }"><input v-model="settings.frameMs" @change="touch({ frameMs: frame })" type="radio" name="frame" :value="frame" :disabled="!supportedFrames.includes(frame)"><span>{{ frame }} ms</span></label><p v-if="!frameAvailable" class="warning">当前连接的接收端不支持所选帧时长；请在下次连接前改为受支持的值。</p></fieldset>
      <fieldset><legend>外观</legend><label v-for="theme in themeOptions" :key="theme" class="choice"><input v-model="settings.theme" type="radio" name="theme" :value="theme"><span>{{ theme === 'light' ? '浅色' : theme === 'dark' ? '深色' : '跟随系统' }}</span></label></fieldset>
      <fieldset class="readonly"><legend>接收格式</legend><p>编码器：Opus</p><p>采样率：48 kHz</p><p>声道：立体声</p></fieldset>
    </section>
    <footer>{{ settings.bitrate / 1000 }} kbps · {{ settings.frameMs }} ms · {{ actualBitrate ? `当前 ${actualBitrate / 1000} kbps · ` : '' }}48 kHz 立体声 Opus · UDP 局域网传输</footer>
  </main>
</template>

<style>
:root { font-family: Inter, system-ui, sans-serif; color: #172033; background: #f6f7f8; }
:root[data-theme='dark'] { color: #e8edf0; background: #172033; color-scheme: dark; }
:root[data-theme='light'] { color-scheme: light; }
body { margin: 0; background: inherit; } main { max-width: 940px; margin: auto; padding: 44px 32px; } header, .section-head, article, .header-actions { display: flex; align-items: center; justify-content: space-between; gap: 18px; } header { border-bottom: 1px solid #d9dde1; padding-bottom: 29px; }.eyebrow { color: #687077; font-size: 12px; font-weight: 700; margin: 0 0 5px; } h1 { font-size: 32px; margin: 0; } h2 { margin: 0; font-size: 21px; } h3 { margin: 0 0 5px; } p { color: #687077; margin: 0; }.status { font-size: 14px; padding: 8px 12px; border: 1px solid #d9dde1; background: #fff; }.status.live { border-color: #16805b; color: #086d4d; } section { margin-top: 42px; }.section-head { margin-bottom: 16px; } button { border: 0; background: #086d4d; color: #fff; padding: 10px 15px; font-weight: 650; cursor: pointer; }.secondary { background: #e8ecee; color: #172033; }.devices { border-top: 1px solid #d9dde1; } article { background: #fff; border-bottom: 1px solid #d9dde1; padding: 18px; }.speaker { width: 34px; height: 34px; display: grid; place-items: center; background: #f0c75e; color: #172033; font-weight: 800; } article > div:nth-child(2) { flex: 1; }.empty { border: 1px dashed #b8c0c6; padding: 32px; color: #687077; } fieldset { border: 1px solid #d9dde1; margin: 0 0 18px; padding: 14px; } legend { font-weight: 700; padding: 0 5px; }.choice { display: flex; align-items: center; gap: 9px; padding: 9px 4px; cursor: pointer; }.disabled { color: #8b949a; cursor: not-allowed; }.warning { color: #a24a18; font-size: 13px; }.readonly p { padding: 4px 0; } footer { margin-top: 44px; color: #687077; font-size: 13px; }
:root[data-theme='dark'] header, :root[data-theme='dark'] .devices, :root[data-theme='dark'] article, :root[data-theme='dark'] fieldset { border-color: #3d4a51; }:root[data-theme='dark'] .status, :root[data-theme='dark'] article { background: #202b31; }:root[data-theme='dark'] .secondary { background: #36434a; color: #e8edf0; }:root[data-theme='dark'] p, :root[data-theme='dark'] footer { color: #b6c1c7; }
@media (max-width: 600px) { main { padding: 26px 18px; } header { align-items: flex-start; flex-wrap: wrap; }.header-actions, .section-head, article { align-items: flex-start; flex-wrap: wrap; } }
</style>
