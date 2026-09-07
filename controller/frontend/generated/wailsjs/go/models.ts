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
	export class EffectiveRectResult {
	    honoredZoom: number;
	    effectiveRectNorm: phoneapi.RectNorm;
	    positionHonored: boolean;
	    activePhysicalId: string;
	    note: string;
	
	    static createFrom(source: any = {}) {
	        return new EffectiveRectResult(source);
	    }
	
	    constructor(source: any = {}) {
	        if ('string' === typeof source) source = JSON.parse(source);
	        this.honoredZoom = source["honoredZoom"];
	        this.effectiveRectNorm = this.convertValues(source["effectiveRectNorm"], phoneapi.RectNorm);
	        this.positionHonored = source["positionHonored"];
	        this.activePhysicalId = source["activePhysicalId"];
	        this.note = source["note"];
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
	export class CameraActionResult {
	    ok: boolean;
	    outcome: string;
	    message?: string;
	    invalidKey?: string;
	
	    static createFrom(source: any = {}) {
	        return new CameraActionResult(source);
	    }
	
	    constructor(source: any = {}) {
	        if ('string' === typeof source) source = JSON.parse(source);
	        this.ok = source["ok"];
	        this.outcome = source["outcome"];
	        this.message = source["message"];
	        this.invalidKey = source["invalidKey"];
	    }
	}
	export class CameraControlsView {
	    ok: boolean;
	    error?: string;
	    capabilities?: phoneapi.CameraCapabilities;
	    state?: phoneapi.CameraStateResponse;
	    calibration: calibration.View;
	
	    static createFrom(source: any = {}) {
	        return new CameraControlsView(source);
	    }
	
	    constructor(source: any = {}) {
	        if ('string' === typeof source) source = JSON.parse(source);
	        this.ok = source["ok"];
	        this.error = source["error"];
	        this.capabilities = this.convertValues(source["capabilities"], phoneapi.CameraCapabilities);
	        this.state = this.convertValues(source["state"], phoneapi.CameraStateResponse);
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
	export class CamerasResult {
	    ok: boolean;
	    error?: string;
	    cameras: phoneapi.CameraInfo[];
	
	    static createFrom(source: any = {}) {
	        return new CamerasResult(source);
	    }
	
	    constructor(source: any = {}) {
	        if ('string' === typeof source) source = JSON.parse(source);
	        this.ok = source["ok"];
	        this.error = source["error"];
	        this.cameras = this.convertValues(source["cameras"], phoneapi.CameraInfo);
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
	export class SegmentView {
	    filename: string;
	    videoUrl: string;
	    durationMs: number;
	    createdAtMs: number;
	
	    static createFrom(source: any = {}) {
	        return new SegmentView(source);
	    }
	
	    constructor(source: any = {}) {
	        if ('string' === typeof source) source = JSON.parse(source);
	        this.filename = source["filename"];
	        this.videoUrl = source["videoUrl"];
	        this.durationMs = source["durationMs"];
	        this.createdAtMs = source["createdAtMs"];
	    }
	}
	export class ClipView {
	    phoneId: string;
	    phoneName: string;
	    clipId: string;
	    state: string;
	    startedAtMs: number;
	    endedAtMs: number;
	    durationMs: number;
	    sizeBytes: number;
	    thumbnailUrl: string;
	    hasThumbnail: boolean;
	    segments: SegmentView[];
	
	    static createFrom(source: any = {}) {
	        return new ClipView(source);
	    }
	
	    constructor(source: any = {}) {
	        if ('string' === typeof source) source = JSON.parse(source);
	        this.phoneId = source["phoneId"];
	        this.phoneName = source["phoneName"];
	        this.clipId = source["clipId"];
	        this.state = source["state"];
	        this.startedAtMs = source["startedAtMs"];
	        this.endedAtMs = source["endedAtMs"];
	        this.durationMs = source["durationMs"];
	        this.sizeBytes = source["sizeBytes"];
	        this.thumbnailUrl = source["thumbnailUrl"];
	        this.hasThumbnail = source["hasThumbnail"];
	        this.segments = this.convertValues(source["segments"], SegmentView);
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
	export class ConfigResult {
	    ok: boolean;
	    error?: string;
	    config?: phoneapi.Config;
	
	    static createFrom(source: any = {}) {
	        return new ConfigResult(source);
	    }
	
	    constructor(source: any = {}) {
	        if ('string' === typeof source) source = JSON.parse(source);
	        this.ok = source["ok"];
	        this.error = source["error"];
	        this.config = this.convertValues(source["config"], phoneapi.Config);
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
	export class LivePreviewResult {
	    ok: boolean;
	    outcome: string;
	    playlistPath?: string;
	    message?: string;
	
	    static createFrom(source: any = {}) {
	        return new LivePreviewResult(source);
	    }
	
	    constructor(source: any = {}) {
	        if ('string' === typeof source) source = JSON.parse(source);
	        this.ok = source["ok"];
	        this.outcome = source["outcome"];
	        this.playlistPath = source["playlistPath"];
	        this.message = source["message"];
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
	
	export class LongRange2 {
	    lo: number;
	    hi: number;
	
	    static createFrom(source: any = {}) {
	        return new LongRange2(source);
	    }
	
	    constructor(source: any = {}) {
	        if ('string' === typeof source) source = JSON.parse(source);
	        this.lo = source["lo"];
	        this.hi = source["hi"];
	    }
	}
	export class IntRange2 {
	    lo: number;
	    hi: number;
	
	    static createFrom(source: any = {}) {
	        return new IntRange2(source);
	    }
	
	    constructor(source: any = {}) {
	        if ('string' === typeof source) source = JSON.parse(source);
	        this.lo = source["lo"];
	        this.hi = source["hi"];
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
	export class CameraCapabilities {
	    cameraId: string;
	    zoomRatioRange: FloatRange2;
	    zoomViaRatioApi: boolean;
	    aeCompensationRange: IntRange2;
	    aeCompensationStepMilliEv: number;
	    exposureTimeRangeNs?: LongRange2;
	    sensitivityRange?: IntRange2;
	    minFocusDistanceDiopters: number;
	    hasManualSensor: boolean;
	    hasManualFocus: boolean;
	    hasManualWhiteBalance: boolean;
	    wbGainRange: FloatRange2;
	    awbModes: number[];
	    videoStabilizationModes: number[];
	    opticalStabilizationModes: number[];
	    maxAeRegions: number;
	    maxAfRegions: number;
	    physicalCameraIds: string[];
	    croppingType: string;
	    activeArrayWidth: number;
	    activeArrayHeight: number;
	
	    static createFrom(source: any = {}) {
	        return new CameraCapabilities(source);
	    }
	
	    constructor(source: any = {}) {
	        if ('string' === typeof source) source = JSON.parse(source);
	        this.cameraId = source["cameraId"];
	        this.zoomRatioRange = this.convertValues(source["zoomRatioRange"], FloatRange2);
	        this.zoomViaRatioApi = source["zoomViaRatioApi"];
	        this.aeCompensationRange = this.convertValues(source["aeCompensationRange"], IntRange2);
	        this.aeCompensationStepMilliEv = source["aeCompensationStepMilliEv"];
	        this.exposureTimeRangeNs = this.convertValues(source["exposureTimeRangeNs"], LongRange2);
	        this.sensitivityRange = this.convertValues(source["sensitivityRange"], IntRange2);
	        this.minFocusDistanceDiopters = source["minFocusDistanceDiopters"];
	        this.hasManualSensor = source["hasManualSensor"];
	        this.hasManualFocus = source["hasManualFocus"];
	        this.hasManualWhiteBalance = source["hasManualWhiteBalance"];
	        this.wbGainRange = this.convertValues(source["wbGainRange"], FloatRange2);
	        this.awbModes = source["awbModes"];
	        this.videoStabilizationModes = source["videoStabilizationModes"];
	        this.opticalStabilizationModes = source["opticalStabilizationModes"];
	        this.maxAeRegions = source["maxAeRegions"];
	        this.maxAfRegions = source["maxAfRegions"];
	        this.physicalCameraIds = source["physicalCameraIds"];
	        this.croppingType = source["croppingType"];
	        this.activeArrayWidth = source["activeArrayWidth"];
	        this.activeArrayHeight = source["activeArrayHeight"];
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
	export class RectNorm {
	    l: number;
	    t: number;
	    r: number;
	    b: number;
	
	    static createFrom(source: any = {}) {
	        return new RectNorm(source);
	    }
	
	    constructor(source: any = {}) {
	        if ('string' === typeof source) source = JSON.parse(source);
	        this.l = source["l"];
	        this.t = source["t"];
	        this.r = source["r"];
	        this.b = source["b"];
	    }
	}
	export class CameraControlKeys {
	    zoomRatio?: number;
	    cropRegionNorm?: RectNorm;
	    aeExposureCompensation?: number;
	    aeLock?: boolean;
	    aeRegionNorm?: RectNorm;
	    manualExposure?: boolean;
	    sensorExposureTimeNs?: number;
	    sensorSensitivityIso?: number;
	    manualFocus?: boolean;
	    lensFocusDistanceDiopters?: number;
	    afRegionNorm?: RectNorm;
	    awbMode?: number;
	    manualWhiteBalance?: boolean;
	    wbRedGain?: number;
	    wbGreenGain?: number;
	    wbBlueGain?: number;
	    videoStabilizationMode?: number;
	    opticalStabilizationMode?: number;
	
	    static createFrom(source: any = {}) {
	        return new CameraControlKeys(source);
	    }
	
	    constructor(source: any = {}) {
	        if ('string' === typeof source) source = JSON.parse(source);
	        this.zoomRatio = source["zoomRatio"];
	        this.cropRegionNorm = this.convertValues(source["cropRegionNorm"], RectNorm);
	        this.aeExposureCompensation = source["aeExposureCompensation"];
	        this.aeLock = source["aeLock"];
	        this.aeRegionNorm = this.convertValues(source["aeRegionNorm"], RectNorm);
	        this.manualExposure = source["manualExposure"];
	        this.sensorExposureTimeNs = source["sensorExposureTimeNs"];
	        this.sensorSensitivityIso = source["sensorSensitivityIso"];
	        this.manualFocus = source["manualFocus"];
	        this.lensFocusDistanceDiopters = source["lensFocusDistanceDiopters"];
	        this.afRegionNorm = this.convertValues(source["afRegionNorm"], RectNorm);
	        this.awbMode = source["awbMode"];
	        this.manualWhiteBalance = source["manualWhiteBalance"];
	        this.wbRedGain = source["wbRedGain"];
	        this.wbGreenGain = source["wbGreenGain"];
	        this.wbBlueGain = source["wbBlueGain"];
	        this.videoStabilizationMode = source["videoStabilizationMode"];
	        this.opticalStabilizationMode = source["opticalStabilizationMode"];
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
	export class CameraInfo {
	    cameraId: string;
	    facing: string;
	    label: string;
	    focalLengthMm?: number;
	    isActive: boolean;
	
	    static createFrom(source: any = {}) {
	        return new CameraInfo(source);
	    }
	
	    constructor(source: any = {}) {
	        if ('string' === typeof source) source = JSON.parse(source);
	        this.cameraId = source["cameraId"];
	        this.facing = source["facing"];
	        this.label = source["label"];
	        this.focalLengthMm = source["focalLengthMm"];
	        this.isActive = source["isActive"];
	    }
	}
	export class CameraStatePatch {
	    manualControlEnabled?: boolean;
	    rotationDegrees?: number;
	    keys?: CameraControlKeys;
	
	    static createFrom(source: any = {}) {
	        return new CameraStatePatch(source);
	    }
	
	    constructor(source: any = {}) {
	        if ('string' === typeof source) source = JSON.parse(source);
	        this.manualControlEnabled = source["manualControlEnabled"];
	        this.rotationDegrees = source["rotationDegrees"];
	        this.keys = this.convertValues(source["keys"], CameraControlKeys);
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
	export class CameraStateResponse {
	    cameraId: string;
	    rotationDegrees: number;
	    manualControlEnabled: boolean;
	    keys: CameraControlKeys;
	
	    static createFrom(source: any = {}) {
	        return new CameraStateResponse(source);
	    }
	
	    constructor(source: any = {}) {
	        if ('string' === typeof source) source = JSON.parse(source);
	        this.cameraId = source["cameraId"];
	        this.rotationDegrees = source["rotationDegrees"];
	        this.manualControlEnabled = source["manualControlEnabled"];
	        this.keys = this.convertValues(source["keys"], CameraControlKeys);
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
	
	    static createFrom(source: any = {}) {
	        return new Config(source);
	    }
	
	    constructor(source: any = {}) {
	        if ('string' === typeof source) source = JSON.parse(source);
	        this.deviceName = source["deviceName"];
	        this.motionSensitivity = source["motionSensitivity"];
	        this.storageCapBytes = source["storageCapBytes"];
	        this.ringBufferMaxAgeMs = source["ringBufferMaxAgeMs"];
	    }
	}
	export class ConfigPatch {
	    deviceName?: string;
	    motionSensitivity?: string;
	    storageCapBytes?: number;
	    ringBufferMaxAgeMs?: number;
	
	    static createFrom(source: any = {}) {
	        return new ConfigPatch(source);
	    }
	
	    constructor(source: any = {}) {
	        if ('string' === typeof source) source = JSON.parse(source);
	        this.deviceName = source["deviceName"];
	        this.motionSensitivity = source["motionSensitivity"];
	        this.storageCapBytes = source["storageCapBytes"];
	        this.ringBufferMaxAgeMs = source["ringBufferMaxAgeMs"];
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

