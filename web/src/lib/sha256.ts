// 零依赖流式增量 SHA-256 计算器
// 支持 GB 级大文件边分片读写边校验完整性，内存占用恒定在数 KB 以内

const K = new Uint32Array([
  0x428a2f98, 0x71374491, 0xb5c0fbcf, 0xe9b5dba5, 0x3956c25b, 0x59f111f1, 0x923f82a4, 0xab1c5ed5,
  0xd807aa98, 0x12835b01, 0x243185be, 0x550c7dc3, 0x72be5d74, 0x80deb1fe, 0x9bdc06a7, 0xc19bf174,
  0xe49b69c1, 0xefbe4786, 0x0fc19dc6, 0x240ca1cc, 0x2de92c6f, 0x4a7484aa, 0x5cb0a9dc, 0x76f988da,
  0x983e5152, 0xa831c66d, 0xb00327c8, 0xbf597fc7, 0xc6e00bf3, 0xd5a79147, 0x06ca6351, 0x14292967,
  0x27b70a85, 0x2e1b2138, 0x4d2c6dfc, 0x53380d13, 0x650a7354, 0x766a0abb, 0x81c2c92e, 0x92722c85,
  0xa2bfe8a1, 0xa81a664b, 0xc24b8b70, 0xc76c51a3, 0xd192e819, 0xd6990624, 0xf40e3585, 0x106aa070,
  0x19a4c116, 0x1e376c08, 0x2748774c, 0x34b0bcb5, 0x391c0cb3, 0x4ed8aa4a, 0x5b9cca4f, 0x682e6ff3,
  0x748f82ee, 0x78a5636f, 0x84c87814, 0x8cc70208, 0x90befffa, 0xa4506ceb, 0xbef9a3f7, 0xc67178f2,
]);

export class IncrementalSHA256 {
  private h = new Uint32Array([
    0x6a09e667, 0xbb67ae85, 0x3c6ef372, 0xa54ff53a,
    0x510e527f, 0x9b05688c, 0x1f83d9ab, 0x5be0cd19,
  ]);
  private w = new Uint32Array(64);
  private buf = new Uint8Array(64);
  private bufLen = 0;
  private totalBytes = 0;

  // 追加任意长度分片数据
  update(chunk: Uint8Array): void {
    let offset = 0;
    const len = chunk.length;
    this.totalBytes += len;

    if (this.bufLen > 0) {
      const needed = 64 - this.bufLen;
      const take = Math.min(needed, len);
      this.buf.set(chunk.subarray(0, take), this.bufLen);
      this.bufLen += take;
      offset += take;
      if (this.bufLen === 64) {
        this.processBlock(this.buf, 0);
        this.bufLen = 0;
      }
    }

    while (offset + 64 <= len) {
      this.processBlock(chunk, offset);
      offset += 64;
    }

    if (offset < len) {
      this.buf.set(chunk.subarray(offset), 0);
      this.bufLen = len - offset;
    }
  }

  // 输出 32 字节原始哈希摘要
  digestBytes(): Uint8Array {
    const bitLenHi = Math.floor(this.totalBytes / 0x20000000);
    const bitLenLo = (this.totalBytes << 3) >>> 0;

    const padLen = this.bufLen < 56 ? 56 - this.bufLen : 120 - this.bufLen;
    const pad = new Uint8Array(padLen + 8);
    pad[0] = 0x80;
    const view = new DataView(pad.buffer);
    view.setUint32(padLen, bitLenHi, false);
    view.setUint32(padLen + 4, bitLenLo, false);

    // 临时追加填充而不污染调用方视角
    const savedTotal = this.totalBytes;
    this.update(pad);
    this.totalBytes = savedTotal;

    const out = new Uint8Array(32);
    const outView = new DataView(out.buffer);
    for (let i = 0; i < 8; i++) {
      outView.setUint32(i * 4, this.h[i], false);
    }
    return out;
  }

  // 输出 64 位小写十六进制哈希字符串
  digestHex(): string {
    return bytesToHex(this.digestBytes());
  }

  private processBlock(data: Uint8Array, offset: number): void {
    const w = this.w;
    for (let i = 0; i < 16; i++) {
      const j = offset + i * 4;
      w[i] =
        ((data[j] << 24) | (data[j + 1] << 16) | (data[j + 2] << 8) | data[j + 3]) >>> 0;
    }
    for (let i = 16; i < 64; i++) {
      const s0 = rotr(w[i - 15], 7) ^ rotr(w[i - 15], 18) ^ (w[i - 15] >>> 3);
      const s1 = rotr(w[i - 2], 17) ^ rotr(w[i - 2], 19) ^ (w[i - 2] >>> 10);
      w[i] = (w[i - 16] + s0 + w[i - 7] + s1) >>> 0;
    }

    let a = this.h[0];
    let b = this.h[1];
    let c = this.h[2];
    let d = this.h[3];
    let e = this.h[4];
    let f = this.h[5];
    let g = this.h[6];
    let h = this.h[7];

    for (let i = 0; i < 64; i++) {
      const S1 = rotr(e, 6) ^ rotr(e, 11) ^ rotr(e, 25);
      const ch = (e & f) ^ (~e & g);
      const temp1 = (h + S1 + ch + K[i] + w[i]) >>> 0;
      const S0 = rotr(a, 2) ^ rotr(a, 13) ^ rotr(a, 22);
      const maj = (a & b) ^ (a & c) ^ (b & c);
      const temp2 = (S0 + maj) >>> 0;

      h = g;
      g = f;
      f = e;
      e = (d + temp1) >>> 0;
      d = c;
      c = b;
      b = a;
      a = (temp1 + temp2) >>> 0;
    }

    this.h[0] = (this.h[0] + a) >>> 0;
    this.h[1] = (this.h[1] + b) >>> 0;
    this.h[2] = (this.h[2] + c) >>> 0;
    this.h[3] = (this.h[3] + d) >>> 0;
    this.h[4] = (this.h[4] + e) >>> 0;
    this.h[5] = (this.h[5] + f) >>> 0;
    this.h[6] = (this.h[6] + g) >>> 0;
    this.h[7] = (this.h[7] + h) >>> 0;
  }
}

function rotr(x: number, n: number): number {
  return (x >>> n) | (x << (32 - n));
}

export function bytesToHex(bytes: Uint8Array): string {
  let hex = '';
  for (let i = 0; i < bytes.length; i++) {
    hex += bytes[i].toString(16).padStart(2, '0');
  }
  return hex;
}

const ONE_MIB = 1024 * 1024;
const TWO_MIB = 2 * ONE_MIB;

// 计算单段字节数组的 SHA-256 小写十六进制摘要
export function sha256Hex(bytes: Uint8Array): string {
  const sha = new IncrementalSHA256();
  sha.update(bytes);
  return sha.digestHex();
}

// 计算跨端一致的断点续传唯一键 resumeKey（Web 与 Android 严格对齐）：
// - file.size < 2MiB: sha256(utf8(`${fileName}|${file.size}|${fullHex}`))
// - file.size >= 2MiB: sha256(utf8(`${fileName}|${file.size}|${first1MiBHex}|${last1MiBHex}`))
export async function computeFileResumeKey(file: Blob, fileName: string): Promise<string> {
  const encoder = new TextEncoder();
  if (file.size < TWO_MIB) {
    const allBuf = await file.arrayBuffer();
    const fullHex = sha256Hex(new Uint8Array(allBuf));
    return sha256Hex(encoder.encode(`${fileName}|${file.size}|${fullHex}`));
  }
  const [firstBuf, lastBuf] = await Promise.all([
    file.slice(0, ONE_MIB).arrayBuffer(),
    file.slice(file.size - ONE_MIB, file.size).arrayBuffer(),
  ]);
  const firstHex = sha256Hex(new Uint8Array(firstBuf));
  const lastHex = sha256Hex(new Uint8Array(lastBuf));
  return sha256Hex(encoder.encode(`${fileName}|${file.size}|${firstHex}|${lastHex}`));
}

// 流式将文件前缀 [0, byteLength) 喂入增量 SHA-256（每片 1MiB，避免大文件占满内存）
export async function feedBlobPrefixToSha(
  blob: Blob,
  byteLength: number,
  sha: IncrementalSHA256
): Promise<void> {
  const target = Math.max(0, Math.min(byteLength, blob.size));
  let pos = 0;
  while (pos < target) {
    const end = Math.min(pos + ONE_MIB, target);
    const buf = await blob.slice(pos, end).arrayBuffer();
    sha.update(new Uint8Array(buf));
    pos = end;
  }
}
