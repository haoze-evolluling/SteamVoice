export namespace main {
	
	export class Device {
	    Name: string;
	    Host: string;
	    Port: number;
	    ID: string;
	    Codec: string;
	    SampleRate: number;
	    Channels: number;
	    Bitrate: number;
	    FrameMs: number;
	
	    static createFrom(source: any = {}) {
	        return new Device(source);
	    }
	
	    constructor(source: any = {}) {
	        if ('string' === typeof source) source = JSON.parse(source);
	        this.Name = source["Name"];
	        this.Host = source["Host"];
	        this.Port = source["Port"];
	        this.ID = source["ID"];
	        this.Codec = source["Codec"];
	        this.SampleRate = source["SampleRate"];
	        this.Channels = source["Channels"];
	        this.Bitrate = source["Bitrate"];
	        this.FrameMs = source["FrameMs"];
	    }
	}
	export class Status {
	    Connected: boolean;
	    Device?: Device;
	    Message: string;
	    Muted: boolean;
	
	    static createFrom(source: any = {}) {
	        return new Status(source);
	    }
	
	    constructor(source: any = {}) {
	        if ('string' === typeof source) source = JSON.parse(source);
	        this.Connected = source["Connected"];
	        this.Device = this.convertValues(source["Device"], Device);
	        this.Message = source["Message"];
	        this.Muted = source["Muted"];
	    }
	
		convertValues(a: any, classs: any, asMap: boolean = false): any {
		    if (!a) {
		        return a;
		    }
		    if (a.slice && a.map) {
		        return (a as any[]).map(elem => this.convertValues(elem, classs));
		    } else if ("object" === typeof a) {
		        if (asMap) {
		            for (const key of Object.keys(a)) {
		                a[key] = new classs(a[key]);
		            }
		            return a;
		        }
		        return new classs(a);
		    }
		    return a;
		}
	}

}

