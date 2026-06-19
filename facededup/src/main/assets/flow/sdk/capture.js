// Optional camera-capture helper. The host app may use its own UI instead;
// this provides a minimal getUserMedia-based frame grabber that returns base64
// JPEG frames meeting the server's Annex A2 size expectations.
/** Start the camera into a <video> element. Returns a stop() function. */
export async function startCamera(video, opts = {}) {
    const stream = await navigator.mediaDevices.getUserMedia({
        video: {
            width: { ideal: opts.width ?? 720 },
            height: { ideal: opts.height ?? 960 },
            facingMode: "user",
        },
        audio: false,
    });
    video.srcObject = stream;
    await video.play();
    return () => stream.getTracks().forEach((t) => t.stop());
}
/** Grab one frame from the video as a base64 JPEG (3:4, quality >= 0.9). */
export function grabFrame(video, provesAction, opts = {}) {
    const w = opts.width ?? 720;
    const h = opts.height ?? 960;
    const canvas = document.createElement("canvas");
    canvas.width = w;
    canvas.height = h;
    const ctx = canvas.getContext("2d");
    if (!ctx)
        throw new Error("2d context unavailable");
    ctx.drawImage(video, 0, 0, w, h);
    const dataUrl = canvas.toDataURL("image/jpeg", opts.jpegQuality ?? 0.92);
    return {
        image_b64: dataUrl.split(",")[1],
        proves_action: provesAction,
    };
}
