<script setup lang="ts">
import { computed, onMounted, ref, watch } from 'vue';
import { Connect, DiscoverDevices, Disconnect, GetStatus, GetIdentity, ListAuthorizedDevices, RemoveAuthorizedDevice, RespondConnection, SaveLocalSettings } from '../wailsjs/go/main/App';
import { EventsOn, WindowSetDarkTheme, WindowSetLightTheme, WindowSetSystemDefaultTheme } from '../wailsjs/runtime/runtime';

type Theme = 'light' | 'dark' | 'system';
type Settings = { bitrate: number; frameMs: number; theme: Theme; updatedAtMs: number; deviceId: string };
type Device = { name: string; host: string; port: number; id: string; codec: string; sampleRate: number; channels: number; bitrate: number; frameMs: number; supportedFrameMs: number[]; updatedAtMs: number; settingsDeviceId: string };
type DeviceStatus = { deviceId: string; name: string; connected: boolean; message: string; bitrate: number; frameMs: number };
type ConnRequest = { requestId: string; deviceId: string; name: string; host: string };
type AuthorizedDevice = { ID: string; Name: string; AddedAtMs: number };
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
const connected = ref<Record<string, DeviceStatus>>({});
const connRequest = ref<ConnRequest | null>(null);
const rememberChoice = ref(true);
const identity = ref<{ deviceId: string; name: string }>({ deviceId: '', name: '' });
const authorizedDevices = ref<AuthorizedDevice[]>([]);
const connectedCount = computed(() => Object.keys(connected.value).length);
const connectedDevices = computed(() => devices.value.filter((device) => connected.value[device.id]));
const headerStatus = computed(() => (connectedCount.value > 0 ? `已连接 ${connectedCount.value} 台接收端，正在发送电脑音频` : status.value));
const supportedFrames = computed(() => {
  if (connectedDevices.value.length) return connectedDevices.value.reduce((acc: number[], device) => acc.filter((frame) => device.supportedFrameMs.includes(frame)), [10, 20]);
  return devices.value.length ? Array.from(new Set(devices.value.flatMap((device) => device.supportedFrameMs))) : [10, 20];
});
const frameAvailable = computed(() => connectedDevices.value.every((device) => device.supportedFrameMs.includes(settings.value.frameMs)));

function normalizeSettings(raw: unknown): Settings {
  const value = raw as Partial<Settings> | null;
  return { bitrate: [64000, 96000, 128000, 192000].includes(value?.bitrate ?? 0) ? value!.bitrate! : defaults.bitrate, frameMs: value?.frameMs === 20 ? 20 : 10, theme: value?.theme === 'light' || value?.theme === 'dark' || value?.theme === 'system' ? value.theme : defaults.theme, updatedAtMs: Number(value?.updatedAtMs) || 0, deviceId: typeof value?.deviceId === 'string' && value.deviceId ? value.deviceId : deviceId };
}
function normalizeDevice(raw: any): Device {
  const values = raw?.supportedFrameMs ?? raw?.SupportedFrameMs ?? [raw?.frameMs ?? raw?.FrameMs ?? 10];
  const supportedFrameMs = (Array.isArray(values) ? values : [values]).filter((value) => value === 10 || value === 20);
  return { name: raw?.name ?? raw?.Name ?? 'Android device', host: raw?.host ?? raw?.Host ?? '', port: raw?.port ?? raw?.Port ?? 0, id: raw?.id ?? raw?.ID ?? raw?.Id ?? raw?.name ?? raw?.Name ?? '', codec: raw?.codec ?? raw?.Codec ?? '', sampleRate: raw?.sampleRate ?? raw?.SampleRate ?? 48000, channels: raw?.channels ?? raw?.Channels ?? 2, bitrate: raw?.bitrate ?? raw?.Bitrate ?? 128000, frameMs: raw?.frameMs ?? raw?.FrameMs ?? 10, supportedFrameMs: supportedFrameMs.length ? supportedFrameMs : [10], updatedAtMs: Number(raw?.updatedAtMs ?? raw?.UpdatedAtMs) || 0, settingsDeviceId: raw?.settingsDeviceId ?? raw?.SettingsDeviceID ?? '' };
}
function normalizeDeviceStatus(raw: any): DeviceStatus {
  return { deviceId: String(raw?.deviceId ?? raw?.DeviceID ?? ''), name: String(raw?.name ?? raw?.Name ?? ''), connected: Boolean(raw?.connected ?? raw?.Connected), message: String(raw?.message ?? raw?.Message ?? ''), bitrate: Number(raw?.bitrate ?? raw?.Bitrate) || 0, frameMs: Number(raw?.frameMs ?? raw?.FrameMs) || 0 };
}
function newer(a: { updatedAtMs: number; deviceId: string }, b: { updatedAtMs: number; deviceId: string }) { return a.updatedAtMs > b.updatedAtMs || (a.updatedAtMs === b.updatedAtMs && a.deviceId > b.deviceId); }
function touch(next: Partial<Settings>) { settings.value = { ...settings.value, ...next, updatedAtMs: Date.now(), deviceId }; }
function statusMessage(raw: any, fallback: string): string {
  const value = raw?.message ?? raw?.Message;
  return typeof value === 'string' && value.trim() ? value : fallback;
}
function deviceInfo(device: Device): string {
  const value = connected.value[device.id];
  if (!value) return '';
  if (value.message && value.message !== '正在传输系统音频') return value.message;
  return value.bitrate ? `${value.bitrate / 1000} kbps` : '';
}
function deviceStalled(device: Device): boolean {
  const value = connected.value[device.id];
  return !!value && !!value.message && value.message !== '正在传输系统音频';
}
function applyTheme(theme: Theme) {
  document.documentElement.dataset.theme = theme;
  if (theme === 'dark') WindowSetDarkTheme(); else if (theme === 'light') WindowSetLightTheme(); else WindowSetSystemDefaultTheme();
}
async function discover() { devices.value = []; try { await DiscoverDevices(); } catch { status.value = '无法发现接收端'; } }
async function refreshAuthorized() {
  try {
    const list: any = await ListAuthorizedDevices();
    authorizedDevices.value = (Array.isArray(list) ? list : []).map((raw: any) => ({ ID: String(raw?.ID ?? raw?.id ?? ''), Name: String(raw?.Name ?? raw?.name ?? ''), AddedAtMs: Number(raw?.AddedAtMs ?? raw?.addedAtMs) || 0 }));
  } catch { authorizedDevices.value = []; }
}
async function respondConnection(allow: boolean) {
  const request = connRequest.value;
  if (!request) return;
  connRequest.value = null;
  try {
    await RespondConnection(request.requestId, allow, rememberChoice.value);
    if (allow) status.value = `已允许 ${request.name} 连接`;
  } catch (error) { status.value = `授权失败：${error instanceof Error ? error.message : String(error)}`; }
}
async function removeAuthorized(device: AuthorizedDevice) {
  try { await RemoveAuthorizedDevice(device.ID); await refreshAuthorized(); } catch { status.value = '移除授权失败'; }
}
async function connect(device: Device) {
  if (connected.value[device.id]) return;
  if (!device.supportedFrameMs.includes(settings.value.frameMs)) { status.value = `该接收端不支持 ${settings.value.frameMs} ms 音频帧`; return; }
  try {
    await Connect({ Name: device.name, Host: device.host, Port: device.port, ID: device.id, Codec: device.codec, SampleRate: device.sampleRate, Channels: device.channels, Bitrate: settings.value.bitrate, FrameMs: settings.value.frameMs, SupportedFrameMs: device.supportedFrameMs, UpdatedAtMs: settings.value.updatedAtMs, SettingsDeviceID: settings.value.deviceId } as any);
  } catch (error) { status.value = `连接失败：${error instanceof Error ? error.message : String(error)}`; }
}
async function disconnect(device: Device) {
  try { await Disconnect(device.id); delete connected.value[device.id]; } catch { status.value = '断开连接失败'; }
}
watch(settings, (next) => { localStorage.setItem(settingsKey, JSON.stringify(next)); applyTheme(next.theme); SaveLocalSettings(next.bitrate, next.frameMs).catch(() => {}); }, { deep: true });
watch(page, (next) => { if (next === 'settings') { refreshAuthorized(); GetIdentity().then((value: any) => { identity.value = { deviceId: String(value?.deviceId ?? value?.DeviceID ?? ''), name: String(value?.name ?? value?.Name ?? '') }; }).catch(() => {}); } });
onMounted(async () => {
  try { settings.value = normalizeSettings(JSON.parse(localStorage.getItem(settingsKey) ?? 'null')); } catch { settings.value = { ...defaults }; }
  applyTheme(settings.value.theme);
  SaveLocalSettings(settings.value.bitrate, settings.value.frameMs).catch(() => {});
  try {
    const value: any = await GetStatus();
    const list: any[] = Array.isArray(value?.devices) ? value.devices : Array.isArray(value?.Devices) ? value.Devices : [];
    for (const item of list) { const entry = normalizeDeviceStatus(item); if (entry.connected && entry.deviceId) connected.value[entry.deviceId] = entry; }
    if (!connectedCount.value) status.value = statusMessage(value, status.value);
  } catch { status.value = '无法获取连接状态'; }
  EventsOn('device:found', (raw: any) => { const device = normalizeDevice(raw); if (device.updatedAtMs && newer(device, settings.value)) { settings.value = { ...settings.value, bitrate: device.bitrate, frameMs: device.frameMs, updatedAtMs: device.updatedAtMs, deviceId: device.settingsDeviceId || settings.value.deviceId }; } const index = devices.value.findIndex((item) => item.id === device.id); if (index >= 0) devices.value[index] = device; else devices.value.push(device); });
  EventsOn('stream:status', (raw: any) => {
    const value = normalizeDeviceStatus(raw);
    if (!value.deviceId) return;
    if (value.connected) connected.value[value.deviceId] = value;
    else { delete connected.value[value.deviceId]; if (value.message) status.value = value.message; }
  });
  EventsOn('conn:request', (raw: any) => { connRequest.value = { requestId: String(raw?.requestId ?? raw?.RequestID ?? ''), deviceId: String(raw?.deviceId ?? raw?.DeviceID ?? ''), name: String(raw?.name ?? raw?.Name ?? '未知设备'), host: String(raw?.host ?? raw?.Host ?? '') }; rememberChoice.value = true; });
  EventsOn('conn:cancelled', (requestId: unknown) => { if (connRequest.value && String(requestId) === connRequest.value.requestId) connRequest.value = null; });
  discover();
});
</script>

<template>
  <main>
    <header>
      <div><p class="eyebrow">电脑音频发送端</p><h1>SteamVoice</h1></div>
      <div class="header-actions"><span :class="['status', connectedCount ? 'live' : '']">{{ headerStatus }}</span><button class="secondary" @click="page = page === 'settings' ? 'devices' : 'settings'">{{ page === 'settings' ? '返回' : '设置' }}</button></div>
    </header>

    <section v-if="page === 'devices'">
      <div class="section-head"><div><h2>可用接收端</h2><p>同一局域网内已启动接收服务的设备，可同时连接多台外放</p></div><button @click="discover">重新扫描</button></div>
      <div v-if="devices.length" class="devices"><article v-for="device in devices" :key="device.id"><div class="speaker">S</div><div><h3>{{ device.name }}</h3><p>{{ device.host }}:{{ device.port }} · 支持 {{ device.supportedFrameMs.join('/') }} ms<span v-if="deviceInfo(device)" :class="['live-info', { warn: deviceStalled(device) }]"> · {{ deviceInfo(device) }}</span></p></div><button v-if="connected[device.id]" class="secondary" @click="disconnect(device)">断开</button><button v-else @click="connect(device)">连接</button></article></div>
      <div v-else class="empty">正在扫描局域网接收端...</div>
    </section>

    <section v-else class="settings">
      <div class="section-head"><div><h2>设置</h2><p>音频参数会在下一次连接时生效</p></div></div>
      <fieldset><legend>初始 Opus 码率</legend><label v-for="bitrate in [64000, 96000, 128000, 192000]" :key="bitrate" class="choice"><input v-model="settings.bitrate" @change="touch({ bitrate })" type="radio" name="bitrate" :value="bitrate"><span>{{ bitrate / 1000 }} kbps</span></label></fieldset>
      <fieldset><legend>音频帧时长</legend><label v-for="frame in [10, 20]" :key="frame" class="choice" :class="{ disabled: !supportedFrames.includes(frame) }"><input v-model="settings.frameMs" @change="touch({ frameMs: frame })" type="radio" name="frame" :value="frame" :disabled="!supportedFrames.includes(frame)"><span>{{ frame }} ms</span></label><p v-if="!frameAvailable" class="warning">当前已连接的接收端不支持所选帧时长；请先断开全部设备后再更改。</p></fieldset>
      <fieldset><legend>外观</legend><label v-for="theme in themeOptions" :key="theme" class="choice"><input v-model="settings.theme" type="radio" name="theme" :value="theme"><span>{{ theme === 'light' ? '浅色' : theme === 'dark' ? '深色' : '跟随系统' }}</span></label></fieldset>
      <fieldset><legend>已授权设备</legend>
        <p class="field-hint">以下设备连接这台电脑时无需再次确认</p>
        <div v-if="authorizedDevices.length" class="authorized">
          <div v-for="device in authorizedDevices" :key="device.ID" class="authorized-row">
            <div><strong>{{ device.Name || '未命名设备' }}</strong><span class="muted">{{ device.ID }}</span></div>
            <button class="secondary danger" @click="removeAuthorized(device)">移除</button>
          </div>
        </div>
        <p v-else class="field-hint">暂无已授权设备。当 Android 设备主动连接时，可以选择记住它。</p>
      </fieldset>
      <fieldset class="readonly"><legend>本机信息</legend>
        <p>设备名称：{{ identity.name || '未命名' }}</p>
        <p>设备标识：{{ identity.deviceId }}</p>
        <p>编码器：Opus</p><p>采样率：48 kHz</p><p>声道：立体声</p>
      </fieldset>
    </section>
    <footer>{{ connectedCount ? `已连接 ${connectedCount} 台 · ` : '' }}{{ settings.bitrate / 1000 }} kbps · {{ settings.frameMs }} ms · 48 kHz 立体声 Opus · UDP 局域网传输</footer>

    <div v-if="connRequest" class="modal-overlay">
      <div class="modal" role="dialog" aria-modal="true">
        <h3>连接请求</h3>
        <p class="modal-device">{{ connRequest.name }}</p>
        <p class="muted">{{ connRequest.host }} 想要把这台电脑的音频推送到它上面播放。</p>
        <label class="choice"><input v-model="rememberChoice" type="checkbox"><span>以后自动同意该设备</span></label>
        <div class="modal-actions">
          <button class="secondary" @click="respondConnection(false)">拒绝</button>
          <button @click="respondConnection(true)">允许</button>
        </div>
      </div>
    </div>
  </main>
</template>

<style>
:root { font-family: Inter, system-ui, sans-serif; color: #172033; background: #f6f7f8; }
:root[data-theme='dark'] { color: #e8edf0; background: #172033; color-scheme: dark; }
:root[data-theme='light'] { color-scheme: light; }
body { margin: 0; background: inherit; } main { max-width: 940px; margin: auto; padding: 44px 32px; } header, .section-head, article, .header-actions { display: flex; align-items: center; justify-content: space-between; gap: 18px; } header { border-bottom: 1px solid #d9dde1; padding-bottom: 29px; }.eyebrow { color: #687077; font-size: 12px; font-weight: 700; margin: 0 0 5px; } h1 { font-size: 32px; margin: 0; } h2 { margin: 0; font-size: 21px; } h3 { margin: 0 0 5px; } p { color: #687077; margin: 0; }.status { font-size: 14px; padding: 8px 12px; border: 1px solid #d9dde1; background: #fff; }.status.live { border-color: #16805b; color: #086d4d; } .live-info { color: #086d4d; font-weight: 600; } .live-info.warn { color: #a24a18; } section { margin-top: 42px; }.section-head { margin-bottom: 16px; } button { border: 0; background: #086d4d; color: #fff; padding: 10px 15px; font-weight: 650; cursor: pointer; }.secondary { background: #e8ecee; color: #172033; }.devices { border-top: 1px solid #d9dde1; } article { background: #fff; border-bottom: 1px solid #d9dde1; padding: 18px; }.speaker { width: 34px; height: 34px; display: grid; place-items: center; background: #f0c75e; color: #172033; font-weight: 800; } article > div:nth-child(2) { flex: 1; }.empty { border: 1px dashed #b8c0c6; padding: 32px; color: #687077; } fieldset { border: 1px solid #d9dde1; margin: 0 0 18px; padding: 14px; } legend { font-weight: 700; padding: 0 5px; }.choice { display: flex; align-items: center; gap: 9px; padding: 9px 4px; cursor: pointer; }.disabled { color: #8b949a; cursor: not-allowed; }.warning { color: #a24a18; font-size: 13px; }.readonly p { padding: 4px 0; } footer { margin-top: 44px; color: #687077; font-size: 13px; }
:root[data-theme='dark'] header, :root[data-theme='dark'] .devices, :root[data-theme='dark'] article, :root[data-theme='dark'] fieldset { border-color: #3d4a51; }:root[data-theme='dark'] .status, :root[data-theme='dark'] article { background: #202b31; }:root[data-theme='dark'] .secondary { background: #36434a; color: #e8edf0; }:root[data-theme='dark'] p, :root[data-theme='dark'] footer { color: #b6c1c7; }:root[data-theme='dark'] .live-info { color: #4fc08d; }
@media (prefers-color-scheme: dark) {
  :root[data-theme='system'] { color: #e8edf0; background: #172033; color-scheme: dark; }
  :root[data-theme='system'] header, :root[data-theme='system'] .devices, :root[data-theme='system'] article, :root[data-theme='system'] fieldset { border-color: #3d4a51; }
  :root[data-theme='system'] .status, :root[data-theme='system'] article { background: #202b31; }
  :root[data-theme='system'] .secondary { background: #36434a; color: #e8edf0; }
  :root[data-theme='system'] p, :root[data-theme='system'] footer { color: #b6c1c7; }
  :root[data-theme='system'] .live-info { color: #4fc08d; }
}
@media (max-width: 600px) { main { padding: 26px 18px; } header { align-items: flex-start; flex-wrap: wrap; }.header-actions, .section-head, article { align-items: flex-start; flex-wrap: wrap; } }
.field-hint { font-size: 13px; margin-bottom: 8px; } .muted { color: #687077; font-size: 12px; }
.authorized { display: flex; flex-direction: column; gap: 6px; } .authorized-row { display: flex; align-items: center; justify-content: space-between; gap: 12px; padding: 9px 10px; border: 1px solid #d9dde1; background: #fff; } .authorized-row .muted { display: block; } .authorized-row div { min-width: 0; } .danger { color: #a24a18; }
.modal-overlay { position: fixed; inset: 0; background: rgba(23, 32, 51, 0.45); display: grid; place-items: center; z-index: 40; }
.modal { background: #fff; color: #172033; border-radius: 10px; padding: 24px; width: min(420px, calc(100vw - 48px)); box-shadow: 0 18px 50px rgba(0, 0, 0, 0.25); }
.modal h3 { margin: 0 0 8px; } .modal-device { font-size: 17px; font-weight: 700; margin: 0 0 4px; } .modal .muted { font-size: 13px; margin-bottom: 14px; } .modal-actions { display: flex; justify-content: flex-end; gap: 10px; margin-top: 16px; }
:root[data-theme='dark'] .authorized-row { border-color: #3d4a51; background: #202b31; } :root[data-theme='dark'] .modal { background: #202b31; color: #e8edf0; } :root[data-theme='dark'] .modal .muted { color: #b6c1c7; }
@media (prefers-color-scheme: dark) {
  :root[data-theme='system'] .authorized-row { border-color: #3d4a51; background: #202b31; }
  :root[data-theme='system'] .modal { background: #202b31; color: #e8edf0; }
  :root[data-theme='system'] .modal .muted { color: #b6c1c7; }
}
</style>
