import { areaLevels, capGain, gammaFor, type AreaRect } from './areaLevels';

const VERTEX = `
attribute vec2 a_pos;
varying vec2 v_uv;
void main() {
  v_uv = vec2((a_pos.x + 1.0) * 0.5, (1.0 - a_pos.y) * 0.5);
  gl_Position = vec4(a_pos, 0.0, 1.0);
}`;

// The contrast maximizer: the whole frame stretched to the levels measured
// inside the area of interest (u_levels: its black and white points), then a
// gamma (u_gamma) that puts the area's median at mid-grey. Off (u_on = 0) for
// a frame the source gives no area: the frame as recorded.
const FRAGMENT = `
precision highp float;
varying vec2 v_uv;
uniform sampler2D u_frame;
uniform float u_on;
uniform vec2 u_levels;
uniform float u_gamma;
void main() {
  vec3 c = texture2D(u_frame, v_uv).rgb;
  if (u_on > 0.0) {
    c = clamp((c - u_levels.x) / (u_levels.y - u_levels.x), 0.0, 1.0);
    c = pow(c, vec3(u_gamma));
  }
  gl_FragColor = vec4(c, 1.0);
}`;

// The frame as it is, for the levels readback.
const COPY = `
precision mediump float;
varying vec2 v_uv;
uniform sampler2D u_frame;
void main() {
  gl_FragColor = vec4(texture2D(u_frame, v_uv).rgb, 1.0);
}`;

/** The levels are measured on a copy of the frame this many pixels on its
 * long side: enough samples in an area a few percent of the frame across. */
const LEVELS_SIZE = 320;
/** Share of the area's pixels let past each end, so a few hot or dead pixels
 * don't set the levels. */
const CLIP = 0.01;
/** Most the stretch may multiply contrast by: in a near-black scene, more
 * mostly shows compression noise. */
const MAX_GAIN = 6;
/** The range the gamma is kept in. Below 1 lifts the area's shadows. */
const GAMMA_MIN = 0.4;
const GAMMA_MAX = 2.5;
/** Time constant the levels follow, in seconds, so they don't pump. */
const TAU_S = 0.3;

interface Tone {
  lo: number;
  hi: number;
  gamma: number;
}

function link(gl: WebGLRenderingContext, fragment: string): WebGLProgram {
  const prog = gl.createProgram();
  if (!prog) throw new Error('WebGL allocation failed');
  gl.attachShader(prog, compile(gl, gl.VERTEX_SHADER, VERTEX));
  gl.attachShader(prog, compile(gl, gl.FRAGMENT_SHADER, fragment));
  // Both programs read the one quad.
  gl.bindAttribLocation(prog, 0, 'a_pos');
  gl.linkProgram(prog);
  if (!gl.getProgramParameter(prog, gl.LINK_STATUS)) throw new Error(gl.getProgramInfoLog(prog) ?? 'link failed');
  return prog;
}

function compile(gl: WebGLRenderingContext, type: number, src: string): WebGLShader {
  const s = gl.createShader(type);
  if (!s) throw new Error('createShader failed');
  gl.shaderSource(s, src);
  gl.compileShader(s);
  if (!gl.getShaderParameter(s, gl.COMPILE_STATUS)) throw new Error(gl.getShaderInfoLog(s) ?? 'shader compile failed');
  return s;
}

/**
 * Draws a `<video>`'s frames onto a `<canvas>` through a WebGL shader that
 * maximizes contrast in each segment's area of interest. The video stays the
 * only decoder and clock - MSE, seeking and the transport are untouched - and
 * the canvas, laid exactly over it, is what's seen.
 *
 * Frames come from `requestVideoFrameCallback`, once per frame the video
 * presents: while playing, after a seek, and the first frame of a new stream.
 *
 * The canvas is hidden until it has a frame of the current stream, so the
 * video's poster shows through meanwhile. The backing store is the video's
 * own size; CSS `object-fit: contain` letterboxes it the same way as the video.
 */
export class PictureRenderer {
  /** Fired once if the context is lost; the renderer is dead after it. */
  onLost?: () => void;

  private gl: WebGLRenderingContext;
  private tex: WebGLTexture;
  private prog: WebGLProgram;
  private copyProg: WebGLProgram;
  private levelsTex: WebGLTexture;
  private levelsFbo: WebGLFramebuffer;
  private levelsW = 0;
  private levelsH = 0;
  private levelsPixels = new Uint8Array(0);
  /** The tone the shader uses, smoothed, and the media time it was last
   * measured at (NaN: never, or not since a fresh stream, seek or gap). */
  private tone: Tone = { lo: 0, hi: 1, gamma: 1 };
  private toneTime = NaN;
  private buf: WebGLBuffer;
  private uOn: WebGLUniformLocation | null;
  private uLevels: WebGLUniformLocation | null;
  private uGamma: WebGLUniformLocation | null;
  private area: ((mediaTime: number) => AreaRect | null) | null = null;
  /** The media time of the frame in the texture. */
  private mediaTime = 0;
  private hasFrame = false;
  private frameHandle: number | null = null;
  private disposed = false;

  /** A renderer, or null when WebGL isn't available. */
  static create(canvas: HTMLCanvasElement, video: HTMLVideoElement): PictureRenderer | null {
    if (typeof video.requestVideoFrameCallback !== 'function') return null;
    const gl = canvas.getContext('webgl', { alpha: false, antialias: false, depth: false, premultipliedAlpha: false });
    if (!gl) return null;
    try {
      return new PictureRenderer(canvas, video, gl);
    } catch (e) {
      console.warn('picture renderer unavailable:', e);
      return null;
    }
  }

  private constructor(private canvas: HTMLCanvasElement, private video: HTMLVideoElement, gl: WebGLRenderingContext) {
    this.gl = gl;
    const tex = gl.createTexture();
    const buf = gl.createBuffer();
    const levelsTex = gl.createTexture();
    const levelsFbo = gl.createFramebuffer();
    if (!tex || !buf || !levelsTex || !levelsFbo) throw new Error('WebGL allocation failed');
    const prog = link(gl, FRAGMENT);
    const copyProg = link(gl, COPY);

    gl.bindBuffer(gl.ARRAY_BUFFER, buf);
    gl.bufferData(gl.ARRAY_BUFFER, new Float32Array([-1, -1, 1, -1, -1, 1, 1, 1]), gl.STATIC_DRAW);
    gl.enableVertexAttribArray(0);
    gl.vertexAttribPointer(0, 2, gl.FLOAT, false, 0, 0);

    gl.bindTexture(gl.TEXTURE_2D, levelsTex);
    gl.texParameteri(gl.TEXTURE_2D, gl.TEXTURE_MIN_FILTER, gl.NEAREST);
    gl.texParameteri(gl.TEXTURE_2D, gl.TEXTURE_MAG_FILTER, gl.NEAREST);
    gl.texParameteri(gl.TEXTURE_2D, gl.TEXTURE_WRAP_S, gl.CLAMP_TO_EDGE);
    gl.texParameteri(gl.TEXTURE_2D, gl.TEXTURE_WRAP_T, gl.CLAMP_TO_EDGE);

    // Video frames are rarely a power of two: no mipmaps, no wrap.
    gl.bindTexture(gl.TEXTURE_2D, tex);
    gl.texParameteri(gl.TEXTURE_2D, gl.TEXTURE_MIN_FILTER, gl.LINEAR);
    gl.texParameteri(gl.TEXTURE_2D, gl.TEXTURE_MAG_FILTER, gl.LINEAR);
    gl.texParameteri(gl.TEXTURE_2D, gl.TEXTURE_WRAP_S, gl.CLAMP_TO_EDGE);
    gl.texParameteri(gl.TEXTURE_2D, gl.TEXTURE_WRAP_T, gl.CLAMP_TO_EDGE);

    this.prog = prog;
    this.copyProg = copyProg;
    this.levelsTex = levelsTex;
    this.levelsFbo = levelsFbo;
    this.tex = tex;
    this.buf = buf;
    this.uOn = gl.getUniformLocation(prog, 'u_on');
    this.uLevels = gl.getUniformLocation(prog, 'u_levels');
    this.uGamma = gl.getUniformLocation(prog, 'u_gamma');

    canvas.addEventListener('webglcontextlost', this.handleLost);
    video.addEventListener('emptied', this.handleEmptied);
    video.addEventListener('loadeddata', this.listen);
    this.showCanvas(false);
    this.listen();
  }

  /** `source` gives the area of interest at a frame by its media time - null
   * where that frame's segment has none, so it is drawn as recorded. */
  setArea(source: ((mediaTime: number) => AreaRect | null) | null) {
    this.area = source;
    this.toneTime = NaN;
    this.redraw();
  }

  /** Draw the current frame again - after its segment's area arrived, say. */
  redraw() {
    if (this.hasFrame && !this.disposed) this.draw();
  }

  dispose() {
    if (this.disposed) return;
    this.disposed = true;
    this.stopListening();
    this.canvas.removeEventListener('webglcontextlost', this.handleLost);
    this.video.removeEventListener('emptied', this.handleEmptied);
    this.video.removeEventListener('loadeddata', this.listen);
    const gl = this.gl;
    gl.deleteTexture(this.tex);
    gl.deleteBuffer(this.buf);
    gl.deleteProgram(this.prog);
    gl.deleteProgram(this.copyProg);
    gl.deleteTexture(this.levelsTex);
    gl.deleteFramebuffer(this.levelsFbo);
    this.showCanvas(false);
  }

  // (Re)subscribe to frames. Also on loadeddata: a new stream may drop a
  // callback registered against the old one, and cancelling a dropped one is
  // harmless.
  private listen = () => {
    if (this.disposed) return;
    this.stopListening();
    this.frameHandle = this.video.requestVideoFrameCallback(this.handleFrame);
  };

  private stopListening() {
    if (this.frameHandle !== null) this.video.cancelVideoFrameCallback(this.frameHandle);
    this.frameHandle = null;
  }

  private handleFrame = (_now: number, meta: VideoFrameCallbackMetadata) => {
    this.frameHandle = null;
    if (this.disposed) return;
    this.mediaTime = meta.mediaTime;
    this.upload();
    this.listen();
  };

  private upload() {
    const { gl, video, canvas } = this;
    const w = video.videoWidth;
    const h = video.videoHeight;
    if (w === 0 || h === 0) return;
    if (canvas.width !== w || canvas.height !== h) {
      canvas.width = w;
      canvas.height = h;
    }
    gl.bindTexture(gl.TEXTURE_2D, this.tex);
    gl.texImage2D(gl.TEXTURE_2D, 0, gl.RGB, gl.RGB, gl.UNSIGNED_BYTE, video);
    this.hasFrame = true;
    this.draw();
    this.showCanvas(true);
  }

  private draw() {
    const { gl, canvas } = this;
    const area = this.area ? this.area(this.mediaTime) : null;
    // A frame without an area breaks the run: the next area starts afresh.
    const on = !!area && this.measure(area);
    if (!area) this.toneTime = NaN;
    gl.useProgram(this.prog);
    gl.bindFramebuffer(gl.FRAMEBUFFER, null);
    gl.bindTexture(gl.TEXTURE_2D, this.tex);
    gl.viewport(0, 0, canvas.width, canvas.height);
    gl.uniform1f(this.uOn, on ? 1 : 0);
    gl.uniform2f(this.uLevels, this.tone.lo, this.tone.hi);
    gl.uniform1f(this.uGamma, this.tone.gamma);
    gl.drawArrays(gl.TRIANGLE_STRIP, 0, 4);
  }

  /**
   * Measure the area's tone on the current frame and move the shader's toward
   * it: smoothly while playing, at once after a seek or a gap, and not at all
   * on a redraw of the same frame (a paused clip keeps the tone it played
   * with). False when the area covers no pixel and nothing was measured yet.
   */
  private measure(area: AreaRect): boolean {
    const dt = this.mediaTime - this.toneTime;
    if (dt === 0) return true;
    const { gl, video } = this;
    const scale = LEVELS_SIZE / Math.max(video.videoWidth, video.videoHeight);
    const w = Math.max(1, Math.round(video.videoWidth * scale));
    const h = Math.max(1, Math.round(video.videoHeight * scale));
    gl.bindTexture(gl.TEXTURE_2D, this.levelsTex);
    if (w !== this.levelsW || h !== this.levelsH) {
      gl.texImage2D(gl.TEXTURE_2D, 0, gl.RGBA, w, h, 0, gl.RGBA, gl.UNSIGNED_BYTE, null);
      gl.bindFramebuffer(gl.FRAMEBUFFER, this.levelsFbo);
      gl.framebufferTexture2D(gl.FRAMEBUFFER, gl.COLOR_ATTACHMENT0, gl.TEXTURE_2D, this.levelsTex, 0);
      this.levelsW = w;
      this.levelsH = h;
      this.levelsPixels = new Uint8Array(w * h * 4);
    }
    gl.bindFramebuffer(gl.FRAMEBUFFER, this.levelsFbo);
    gl.bindTexture(gl.TEXTURE_2D, this.tex);
    gl.useProgram(this.copyProg);
    gl.viewport(0, 0, w, h);
    gl.drawArrays(gl.TRIANGLE_STRIP, 0, 4);
    gl.readPixels(0, 0, w, h, gl.RGBA, gl.UNSIGNED_BYTE, this.levelsPixels);

    const measured = areaLevels(this.levelsPixels, w, h, area, CLIP);
    if (!measured) return !Number.isNaN(this.toneTime);
    const [lo, hi] = capGain(measured.lo, measured.hi, MAX_GAIN);
    const target: Tone = { lo, hi, gamma: gammaFor(lo, hi, measured.mid, GAMMA_MIN, GAMMA_MAX) };
    // A seek, a step back, or a fresh area: start afresh.
    if (!(dt > 0 && dt < 0.25)) {
      this.tone = target;
    } else {
      const a = 1 - Math.exp(-dt / TAU_S);
      const t = this.tone;
      this.tone = {
        lo: t.lo + (target.lo - t.lo) * a,
        hi: t.hi + (target.hi - t.hi) * a,
        gamma: t.gamma + (target.gamma - t.gamma) * a,
      };
    }
    this.toneTime = this.mediaTime;
    return true;
  }

  /** A fresh stream: the last frame belongs to the old one. */
  private handleEmptied = () => {
    this.toneTime = NaN;
    this.hasFrame = false;
    this.showCanvas(false);
  };

  private handleLost = (e: Event) => {
    e.preventDefault();
    this.dispose();
    this.onLost?.();
  };

  private showCanvas(on: boolean) {
    this.canvas.style.visibility = on ? 'visible' : 'hidden';
  }
}
