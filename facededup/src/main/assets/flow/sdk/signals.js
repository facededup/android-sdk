// Browser fraud-signal collection (maximum-signal posture, consent-gated).
// The host app MUST have obtained the `device_signals` (and, for precise GPS,
// `precise_location`) consent scope before calling this — see README.
const SDK_VERSION = "0.1.0";
async function deviceSignals(opts) {
    const nav = navigator;
    let model;
    let platformVersion;
    let platform = nav.userAgentData?.platform;
    try {
        const high = await nav.userAgentData?.getHighEntropyValues?.([
            "model",
            "platformVersion",
            "platform",
        ]);
        model = high?.model;
        platformVersion = high?.platformVersion;
        platform = high?.platform ?? platform;
    }
    catch {
        /* high-entropy hints unavailable */
    }
    const dpr = window.devicePixelRatio || 1;
    return {
        platform: "web",
        os: platform,
        os_version: platformVersion,
        model,
        sdk_version: SDK_VERSION,
        app_version: opts.appVersion,
        screen: `${window.screen.width}x${window.screen.height}@${dpr}`,
        locale: navigator.language,
        timezone: Intl.DateTimeFormat().resolvedOptions().timeZone,
        // Browser cannot reliably detect emulator/root/debugger; leave undefined
        // rather than guessing (the native SDKs fill these).
    };
}
function networkSignals() {
    const conn = navigator.connection;
    return {
        connection_type: conn?.type ?? conn?.effectiveType,
    };
}
function getLocation() {
    if (!("geolocation" in navigator))
        return Promise.resolve(undefined);
    return new Promise((resolve) => {
        navigator.geolocation.getCurrentPosition((pos) => resolve({
            lat: pos.coords.latitude,
            lng: pos.coords.longitude,
            accuracy_m: pos.coords.accuracy,
            source: "gps",
            captured_at: new Date(pos.timestamp).toISOString(),
        }), () => resolve(undefined), { enableHighAccuracy: true, timeout: 8000, maximumAge: 0 });
    });
}
/** Collect a DeviceContext in the browser. Call only after consent. */
export async function collectDeviceContext(opts = {}) {
    const [device, location] = await Promise.all([
        deviceSignals(opts),
        opts.collectPreciseLocation ? getLocation() : Promise.resolve(undefined),
    ]);
    return {
        device,
        network: networkSignals(),
        location,
        timing: { client_timestamp: new Date().toISOString() },
        attestation_token: opts.attestationToken,
    };
}
