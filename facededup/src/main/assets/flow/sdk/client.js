// LivenessClient — orchestrates the full consent -> request -> challenge ->
// capture -> verify flow against the server, carrying fraud signals.
// Framework-agnostic: the host app owns the UI; this owns the protocol.
import { collectDeviceContext } from "./signals.js";
// Downstream face-compare/liveness contract: raw-base64 JPEG frames = "image_type_2".
const IMAGE_TYPE_B64 = "image_type_2";
// Strip any "data:image/...;base64," prefix → the raw Base64 the engine expects.
function rawB64(s) {
    const i = s.indexOf(",");
    return s.startsWith("data:") && i >= 0 ? s.slice(i + 1) : s;
}
/**
 * Shape captured frames into { selfie_image, liveliness_images } for a
 * downstream engine. frame[0] is the best selfie; the remainder are the
 * liveness frames. Targets the 4-8 liveness range: if more than 8 are
 * available they are evenly sub-sampled; fewer are passed through as-is
 * (never fabricated — duplicate liveness frames would be misleading).
 */
function shapeImages(frames) {
    const valid = frames.filter((f) => f && f.image_b64);
    const toPayload = (f) => ({
        image_type: IMAGE_TYPE_B64,
        image: rawB64(f.image_b64),
    });
    const selfie = valid[0];
    let rest = valid.slice(1);
    if (rest.length > 8) {
        // Evenly sub-sample down to 8 across the captured span.
        const step = rest.length / 8;
        rest = Array.from({ length: 8 }, (_, k) => rest[Math.floor(k * step)]);
    }
    return {
        selfie_image: selfie ? toPayload(selfie) : undefined,
        liveliness_images: rest.map(toPayload),
    };
}
export class LivenessClient {
    constructor(opts) {
        this.base = opts.baseUrl.replace(/\/$/, "");
        this.f = opts.fetchImpl ?? fetch.bind(globalThis);
        this.lic = opts.licenseKey;
    }
    headers() {
        const h = { "content-type": "application/json" };
        if (this.lic)
            h["X-License-Key"] = this.lic;
        return h;
    }
    async post(path, body) {
        const res = await this.f(`${this.base}${path}`, {
            method: "POST",
            headers: this.headers(),
            body: JSON.stringify(body),
        });
        if (!res.ok) {
            const detail = await res.text();
            throw new Error(`${path} ${res.status}: ${detail}`);
        }
        return res.json();
    }
    /** Step 1: record explicit consent (incl. fraud-signal scopes). */
    async grantConsent(o) {
        const r = await this.post("/v1/consent", {
            subject_id: o.subjectId,
            accepted: true,
            signal_scopes: o.signalScopes ?? [],
        });
        return r.consent_id;
    }
    /** Step 2: open a request, attaching collected device/fraud signals. */
    async openRequest(args) {
        return this.post("/v1/request", {
            subject_id: args.subjectId,
            consent_id: args.consentId,
            reason: args.reason ?? "issuance",
            method: args.method ?? "face_liveness",
            device_context: args.deviceContext,
        });
    }
    /** Verify a spoken (face_voice / face_number) challenge. */
    async verifySpoken(args) {
        const result = await this.post("/v1/verify", {
            request_id: args.requestId,
            session_id: args.challenge.session_id,
            nonce: args.challenge.nonce,
            frames: args.frames,
            transcript: args.transcript,
            audio_present: args.audioPresent,
            audio_b64: args.audioB64 ?? null,
            video_b64: args.videoB64 ?? null,
        });
        return { ...result, ...shapeImages(args.frames) };
    }
    /** Assisted verification pathway (accessibility / fallback). */
    async assisted(subjectId, consentId, note = "") {
        return this.post("/v1/assisted", { subject_id: subjectId, consent_id: consentId, note });
    }
    /** Step 3: get the server-issued challenge (actions + nonce). */
    async getChallenge(requestId) {
        return this.post("/v1/challenge", { request_id: requestId });
    }
    /** Step 4: submit captured frames + nonce for scoring. */
    async verify(args) {
        const result = await this.post("/v1/verify", {
            request_id: args.requestId,
            session_id: args.challenge.session_id,
            nonce: args.challenge.nonce,
            attestation_token: args.attestationToken,
            client_actions: args.clientActions ?? null,
            frames: args.frames,
            video_b64: args.videoB64 ?? null,
            gps_lat: args.gps?.lat ?? null,
            gps_lon: args.gps?.lon ?? null,
            gps_accuracy_m: args.gps?.accuracy_m ?? null,
            face_attributes: args.faceAttributes ?? null,
            pad_strict: args.padStrict ?? false,
        });
        return { ...result, ...shapeImages(args.frames) };
    }
    /**
     * Convenience end-to-end run. The caller supplies a `capture` callback that,
     * given the required actions, returns the frames proving them (so the host
     * app controls the camera UI). Signals are collected automatically when
     * `collect` options are given.
     */
    async run(args) {
        const consentId = await this.grantConsent({
            subjectId: args.subjectId,
            signalScopes: args.signalScopes,
        });
        const deviceContext = args.collect
            ? await collectDeviceContext(args.collect)
            : undefined;
        const req = await this.openRequest({
            subjectId: args.subjectId,
            consentId,
            reason: args.reason,
            deviceContext,
        });
        const challenge = await this.getChallenge(req.request_id);
        const frames = await args.capture(challenge.actions);
        return this.verify({
            requestId: req.request_id,
            challenge,
            frames,
            attestationToken: args.collect?.attestationToken,
        });
    }
}
