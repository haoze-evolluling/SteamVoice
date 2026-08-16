declare module '../wailsjs/go/main/App' {
  export function DiscoverDevices(): Promise<void>
  export function Connect(device: {name:string;host:string;port:number;id:string;bitrate:number;frameMs:number}): Promise<void>
  export function Disconnect(): Promise<void>
  export function GetStatus(): Promise<{connected:boolean;message:string}>
}
declare module '../wailsjs/runtime/runtime' { export function EventsOn(name:string, callback:(value:any)=>void): void }
