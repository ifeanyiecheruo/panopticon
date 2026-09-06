export namespace calibration {
	
	export class CameraZoomSummary {
	    cameraId: string;
	    facing: string;
	    opticalRange: phoneapi.FloatRange2;
	    digitalRange: phoneapi.FloatRange2;
	    crossoverRatio?: number;
	    positionHonored: boolean;
	    positionMetadataLied: boolean;
	    qualityCollapseRatio?: number;
	    resolutions: number;
	
	    static createFrom(source: any = {}) {
	        return new CameraZoomSummary(source);
	    }
	
	    constructor(source: any = {}) {
	        if ('string' === typeof source) source = JSON.parse(source);
	        this.cameraId = source["cameraId"];
	        this.facing = source["facing"];
	        this.opticalRange = this.convertValues(source["opticalRange"], phoneapi.FloatRange2);
	        this.digitalRange = this.convertValues(source["digitalRange"], phoneapi.FloatRange2);
	        this.crossoverRatio = source["crossoverRatio"];
	        this.positionHonored = source["positionHonored"];
	        this.positionMetadataLied = source["positionMetadataLied"];
	        this.qualityCollapseRatio = source["qualityCollapseRatio"];
	        this.resolutions = source["resolutions"];
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
	export class View {
	    modelKey: string;
	    present: boolean;
	    checksPassed: number;
	    checksTotal: number;
	    calibratedAtMs: number;
	    sourcePhoneId: string;
	    sourcePhoneName: string;
	    viaOtherPhone: boolean;
	    cameras: CameraZoomSummary[];
	
	    static createFrom(source: any = {}) {
	        return new View(source);
	    }
	
	    constructor(source: any = {}) {
	        if ('string' === typeof source) source = JSON.parse(source);
	        this.modelKey = source["modelKey"];
	        this.present = source["present"];
	        this.checksPassed = source["checksPassed"];
	        this.checksTotal = source["checksTotal"];
	        this.calibratedAtMs = source["calibratedAtMs"];
	        this.sourcePhoneId = source["sourcePhoneId"];
	        this.sourcePhoneName = source["sourcePhoneName"];
	        this.viaOtherPhone = source["viaOtherPhone"];
	        this.cameras = this.convertValues(source["cameras"], CameraZoomSummary);
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

export namespace main {
	
	export class PhoneView {
	    id: string;
	    name: string;
	    manufacturer: string;
	    model: string;
	    baseUrl: string;
	    reachable: boolean;
	    status: string;
	    batteryPercent: number;
	    hasBattery: boolean;
	    charging: boolean;
	    lastSeenMs: number;
	    syncCursorMs: number;
	    diskUsageBytes: number;
	
	    static createFrom(source: any = {}) {
	        return new PhoneView(source);
	    }
	
	    constructor(source: any = {}) {
	        if ('string' === typeof source) source = JSON.parse(source);
	        this.id = source["id"];
	        this.name = source["name"];
	        this.manufacturer = source["manufacturer"];
	        this.model = source["model"];
	        this.baseUrl = source["baseUrl"];
	        this.reachable = source["reachable"];
	        this.status = source["status"];
	        this.batteryPercent = source["batteryPercent"];
	        this.hasBattery = source["hasBattery"];
	        this.charging = source["charging"];
	        this.lastSeenMs = source["lastSeenMs"];
	        this.syncCursorMs = source["syncCursorMs"];
	        this.diskUsageBytes = source["diskUsageBytes"];
	    }
	}
	export class AddPhoneResult {
	    ok: boolean;
	    outcome: string;
	    message: string;
	    phone?: PhoneView;
	
	    static createFrom(source: any = {}) {
	        return new AddPhoneResult(source);
	    }
	
	    constructor(source: any = {}) {
	        if ('string' === typeof source) source = JSON.parse(source);
	        this.ok = source["ok"];
	        this.outcome = source["outcome"];
	        this.message = source["message"];
	        this.phone = this.convertValues(source["phone"], PhoneView);
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
	export class CalibrationProgressResult {
	    ok: boolean;
	    error?: string;
	    progress?: phoneapi.CalibrationProgress;
	    stored: boolean;
	
	    static createFrom(source: any = {}) {
	        return new CalibrationProgressResult(source);
	    }
	
	    constructor(source: any = {}) {
	        if ('string' === typeof source) source = JSON.parse(source);
	        this.ok = source["ok"];
	        this.error = source["error"];
	        this.progress = this.convertValues(source["progress"], phoneapi.CalibrationProgress);
	        this.stored = source["stored"];
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
	export class CalibrationStartResult {
	    ok: boolean;
	    outcome: string;
	    runId?: string;
	    message?: string;
	
	    static createFrom(source: any = {}) {
	        return new CalibrationStartResult(source);
	    }
	
	    constructor(source: any = {}) {
	        if ('string' === typeof source) source = JSON.parse(source);
	        this.ok = source["ok"];
	        this.outcome = source["outcome"];
	        this.runId = source["runId"];
	        this.message = source["message"];
	    }
	}
	export class ClipView {
	    phoneId: string;
	    phoneName: string;
	    filename: string;
	    state: string;
	    createdAtMs: number;
	    durationMs: number;
	    sizeBytes: number;
	    width: number;
	    height: number;
	    videoUrl: string;
	    thumbnailUrl: string;
	    hasThumbnail: boolean;
	
	    static createFrom(source: any = {}) {
	        return new ClipView(source);
	    }
	
	    constructor(source: any = {}) {
	        if ('string' === typeof source) source = JSON.parse(source);
	        this.phoneId = source["phoneId"];
	        this.phoneName = source["phoneName"];
	        this.filename = source["filename"];
	        this.state = source["state"];
	        this.createdAtMs = source["createdAtMs"];
	        this.durationMs = source["durationMs"];
	        this.sizeBytes = source["sizeBytes"];
	        this.width = source["width"];
	        this.height = source["height"];
	        this.videoUrl = source["videoUrl"];
	        this.thumbnailUrl = source["thumbnailUrl"];
	        this.hasThumbnail = source["hasThumbnail"];
	    }
	}
	export class ParsedInvite {
	    address: string;
	    code: string;
	    ok: boolean;
	
	    static createFrom(source: any = {}) {
	        return new ParsedInvite(source);
	    }
	
	    constructor(source: any = {}) {
	        if ('string' === typeof source) source = JSON.parse(source);
	        this.address = source["address"];
	        this.code = source["code"];
	        this.ok = source["ok"];
	    }
	}
	export class PhoneDetailView {
	    phone: PhoneView;
	    status?: phoneapi.Status;
	    statusError?: string;
	    config?: phoneapi.Config;
	    configError?: string;
	    calibration: calibration.View;
	
	    static createFrom(source: any = {}) {
	        return new PhoneDetailView(source);
	    }
	
	    constructor(source: any = {}) {
	        if ('string' === typeof source) source = JSON.parse(source);
	        this.phone = this.convertValues(source["phone"], PhoneView);
	        this.status = this.convertValues(source["status"], phoneapi.Status);
	        this.statusError = source["statusError"];
	        this.config = this.convertValues(source["config"], phoneapi.Config);
	        this.configError = source["configError"];
	        this.calibration = this.convertValues(source["calibration"], calibration.View);
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
	
	export class UnpairResult {
	    ok: boolean;
	    outcome: string;
	    unsyncedCount: number;
	    message?: string;
	
	    static createFrom(source: any = {}) {
	        return new UnpairResult(source);
	    }
	
	    constructor(source: any = {}) {
	        if ('string' === typeof source) source = JSON.parse(source);
	        this.ok = source["ok"];
	        this.outcome = source["outcome"];
	        this.unsyncedCount = source["unsyncedCount"];
	        this.message = source["message"];
	    }
	}

}

export namespace phoneapi {
	
	export class CalibrationStepProgress {
	    index: number;
	    total: number;
	
	    static createFrom(source: any = {}) {
	        return new CalibrationStepProgress(source);
	    }
	
	    constructor(source: any = {}) {
	        if ('string' === typeof source) source = JSON.parse(source);
	        this.index = source["index"];
	        this.total = source["total"];
	    }
	}
	export class CalibrationProgress {
	    runId: string;
	    status: string;
	    currentCameraId: string;
	    camerasCompleted: number;
	    camerasTotal: number;
	    currentStep: string;
	    stepsCompleted: number;
	    stepsTotal: number;
	    progressWithinStep: CalibrationStepProgress;
	    startedAtMs: number;
	
	    static createFrom(source: any = {}) {
	        return new CalibrationProgress(source);
	    }
	
	    constructor(source: any = {}) {
	        if ('string' === typeof source) source = JSON.parse(source);
	        this.runId = source["runId"];
	        this.status = source["status"];
	        this.currentCameraId = source["currentCameraId"];
	        this.camerasCompleted = source["camerasCompleted"];
	        this.camerasTotal = source["camerasTotal"];
	        this.currentStep = source["currentStep"];
	        this.stepsCompleted = source["stepsCompleted"];
	        this.stepsTotal = source["stepsTotal"];
	        this.progressWithinStep = this.convertValues(source["progressWithinStep"], CalibrationStepProgress);
	        this.startedAtMs = source["startedAtMs"];
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
	
	export class Config {
	    deviceName: string;
	    motionSensitivity: string;
	    storageCapBytes: number;
	    ringBufferMaxAgeMs: number;
	    rotationDegrees: number;
	
	    static createFrom(source: any = {}) {
	        return new Config(source);
	    }
	
	    constructor(source: any = {}) {
	        if ('string' === typeof source) source = JSON.parse(source);
	        this.deviceName = source["deviceName"];
	        this.motionSensitivity = source["motionSensitivity"];
	        this.storageCapBytes = source["storageCapBytes"];
	        this.ringBufferMaxAgeMs = source["ringBufferMaxAgeMs"];
	        this.rotationDegrees = source["rotationDegrees"];
	    }
	}
	export class FloatRange2 {
	    lo: number;
	    hi: number;
	
	    static createFrom(source: any = {}) {
	        return new FloatRange2(source);
	    }
	
	    constructor(source: any = {}) {
	        if ('string' === typeof source) source = JSON.parse(source);
	        this.lo = source["lo"];
	        this.hi = source["hi"];
	    }
	}
	export class Status {
	    mode: string;
	    status: string;
	    cameraHealthy: boolean;
	    liveViewers: number;
	    storageUsedBytes: number;
	    storageCapBytes: number;
	    batteryPercent: number;
	    charging: boolean;
	    serverTimeMs: number;
	
	    static createFrom(source: any = {}) {
	        return new Status(source);
	    }
	
	    constructor(source: any = {}) {
	        if ('string' === typeof source) source = JSON.parse(source);
	        this.mode = source["mode"];
	        this.status = source["status"];
	        this.cameraHealthy = source["cameraHealthy"];
	        this.liveViewers = source["liveViewers"];
	        this.storageUsedBytes = source["storageUsedBytes"];
	        this.storageCapBytes = source["storageCapBytes"];
	        this.batteryPercent = source["batteryPercent"];
	        this.charging = source["charging"];
	        this.serverTimeMs = source["serverTimeMs"];
	    }
	}

}

