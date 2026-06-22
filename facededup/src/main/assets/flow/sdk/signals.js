// Browser fraud-signal collection (maximum-signal posture, consent-gated).
// The host app MUST have obtained the `device_signals` (and, for precise GPS,
// `precise_location`) consent scope before calling this — see README.
//
// Two sources are merged:
//   1. Browser-visible signals (UA-CH, screen, memory, timezone, network, GPS).
//   2. NATIVE signals the browser can't see (Build.*, fingerprint, root/jailbreak,
//      package name, app hash, hardware attestation, carrier, VPN…), injected by the
//      native SDK at document-start as `window.__FACEDEDUP_NATIVE_SIGNALS`
//      ({ device?, network? }). Native fields override/extend the browser ones.
const SDK_VERSION = "1.3.2";
/** Native-injected signals (set by the Android/iOS SDK before the flow runs). */
function nativeSignals() {
    try {
        return window
            .__FACEDEDUP_NATIVE_SIGNALS || {};
    }
    catch {
        return {};
    }
}
/** Best-effort screen refresh rate (Hz) via requestAnimationFrame timing. */
function screenRefreshRate() {
    return new Promise((resolve) => {
        let last = 0, n = 0, sum = 0, done = false;
        const finish = () => { if (done)
            return; done = true; resolve(n ? Math.round(1000 / (sum / n)) : undefined); };
        const tick = (t) => {
            if (last) {
                sum += t - last;
                n++;
            }
            last = t;
            if (n < 12) {
                try {
                    requestAnimationFrame(tick);
                }
                catch {
                    finish();
                }
            }
            else
                finish();
        };
        try {
            requestAnimationFrame(tick);
            setTimeout(finish, 450);
        }
        catch {
            resolve(undefined);
        }
    });
}
async function deviceSignals(opts) {
    const nav = navigator;
    let model;
    let platformVersion;
    let architecture;
    let bitness;
    let platform = nav.userAgentData?.platform;
    try {
        const high = await nav.userAgentData?.getHighEntropyValues?.([
            "model", "platformVersion", "platform", "architecture", "bitness",
        ]);
        model = high?.model;
        platformVersion = high?.platformVersion;
        platform = high?.platform ?? platform;
        architecture = high?.architecture;
        bitness = high?.bitness;
    }
    catch {
        /* high-entropy hints unavailable */
    }
    const dpr = window.devicePixelRatio || 1;
    const heap = performance.memory;
    const refresh = await screenRefreshRate();
    const native = nativeSignals().device || {};
    return {
        platform: "web",
        os: platform,
        os_version: platformVersion,
        model,
        sdk_version: SDK_VERSION,
        app_version: opts.appVersion,
        // system architecture, e.g. "arm-64" (UA-CH; native fills exact ABI/uname)
        system_architecture: architecture ? `${architecture}${bitness ? "-" + bitness : ""}` : undefined,
        screen: `${window.screen.width}x${window.screen.height}@${dpr}`,
        screen_width_px: Math.round(window.screen.width * dpr),
        screen_height_px: Math.round(window.screen.height * dpr),
        screen_refresh_rate: refresh,
        device_memory_gb: nav.deviceMemory, // coarse (e.g. 4, 8)
        app_memory_used_bytes: heap?.usedJSHeapSize, // Chromium-only JS heap
        app_memory_limit_bytes: heap?.jsHeapSizeLimit,
        locale: navigator.language,
        languages: (navigator.languages || []).slice(0, 5).join(","),
        timezone: Intl.DateTimeFormat().resolvedOptions().timeZone,
        timezone_offset_minutes: -new Date().getTimezoneOffset(), // +60 = UTC+1
        // Browser can't see emulator/root/debugger/Build.*/package/app-hash — the native
        // SDK injects them via __FACEDEDUP_NATIVE_SIGNALS (merged last, so it wins).
        ...native,
    };
}
function networkSignals() {
    const conn = navigator.connection;
    const native = nativeSignals().network || {};
    return {
        connection_type: conn?.type ?? conn?.effectiveType,
        downlink_mbps: conn?.downlink,
        rtt_ms: conn?.rtt,
        save_data: conn?.saveData,
        online: navigator.onLine,
        // carrier / VPN / proxy / client IP come from the native SDK (and the server
        // observes the source IP) — merged last.
        ...native,
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
/** Collect a DeviceContext in the browser (+ merged native signals). Call only after consent. */
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
