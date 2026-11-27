import type { CachedMessage } from '../types';

export const E2EE_PREFIX = '::E2EE1::';
const IV_LEN_BYTES = 12;
const TAG_LEN_BITS = 128;
const DECRYPT_FAILED_FALLBACK = '[加密消息，无法解密]';

// 缓存每个房间派生后的 AES-256-GCM 密钥，避免批量同步历史记录时重复计算 SHA-256
const roomKeyCache = new Map<string, Promise<CryptoKey | null>>();

function encodeBytesToBase64(bytes: Uint8Array): string {
  let binary = '';
  const chunkSize = 0x8000;
  for (let i = 0; i < bytes.length; i += chunkSize) {
    const chunk = bytes.subarray(i, i + chunkSize);
    binary += String.fromCharCode(...chunk);
  }
  return btoa(binary);
}

function decodeBase64ToBytes(b64: string): Uint8Array<ArrayBuffer> {
  const binary = atob(b64);
  const out = new Uint8Array(new ArrayBuffer(binary.length));
  for (let i = 0; i < binary.length; i++) {
    out[i] = binary.charCodeAt(i);
  }
  return out;
}

export function isE2eeCiphertext(raw?: string | null): boolean {
  return typeof raw === 'string' && raw.startsWith(E2EE_PREFIX);
}

export function deriveRoomAesKey(
  roomCode: string,
  roomPassword: string
): Promise<CryptoKey | null> {
  if (typeof crypto === 'undefined' || !crypto.subtle) {
    return Promise.resolve(null);
  }
  const normalizedCode = roomCode.trim().toLowerCase();
  const cacheKey = `${normalizedCode}\u0000${roomPassword}`;
  const cached = roomKeyCache.get(cacheKey);
  if (cached) return cached;

  const task = (async (): Promise<CryptoKey | null> => {
    try {
      const material = `baychat-e2ee-v1:${normalizedCode}:${roomPassword}`;
      const materialBytes = new TextEncoder().encode(material);
      const hash = await crypto.subtle.digest('SHA-256', materialBytes);
      return await crypto.subtle.importKey(
        'raw',
        hash,
        { name: 'AES-GCM' },
        false,
        ['encrypt', 'decrypt']
      );
    } catch {
      return null;
    }
  })();

  roomKeyCache.set(cacheKey, task);
  return task;
}

/**
 * 使用房间密钥加密明文（与 Android ChatCrypto.kt AES-256-GCM 完全互通）；
 * 空字符串原样返回；若运行环境不支持 WebCrypto 则安全回退为原明文。
 */
function toStrictArrayBuffer(bytes: Uint8Array): ArrayBuffer {
  const buf = new ArrayBuffer(bytes.byteLength);
  new Uint8Array(buf).set(bytes);
  return buf;
}

export async function encryptChatText(
  roomCode: string,
  roomPassword: string,
  plainText: string
): Promise<string> {
  if (!plainText) return '';
  const key = await deriveRoomAesKey(roomCode, roomPassword);
  if (!key) return plainText;
  try {
    const iv = crypto.getRandomValues(new Uint8Array(new ArrayBuffer(IV_LEN_BYTES)));
    const encoded = new TextEncoder().encode(plainText);
    const cipherBuf = await crypto.subtle.encrypt(
      { name: 'AES-GCM', iv, tagLength: TAG_LEN_BITS },
      key,
      toStrictArrayBuffer(encoded)
    );
    const cipherBytes = new Uint8Array(cipherBuf);
    const payload = new Uint8Array(IV_LEN_BYTES + cipherBytes.length);
    payload.set(iv, 0);
    payload.set(cipherBytes, IV_LEN_BYTES);
    return E2EE_PREFIX + encodeBytesToBase64(payload);
  } catch {
    return plainText;
  }
}

/**
 * 解密 ::E2EE1:: 端到端密文；非 ::E2EE1:: 前缀原样返回，解密失败返回安全占位提示。
 */
export async function decryptChatText(
  roomCode: string,
  roomPassword: string,
  raw?: string | null
): Promise<string> {
  if (!raw) return '';
  if (!isE2eeCiphertext(raw)) {
    return raw;
  }
  const key = await deriveRoomAesKey(roomCode, roomPassword);
  if (!key) return DECRYPT_FAILED_FALLBACK;
  try {
    const encoded = raw.slice(E2EE_PREFIX.length);
    const payload = decodeBase64ToBytes(encoded);
    if (payload.length <= IV_LEN_BYTES) {
      return DECRYPT_FAILED_FALLBACK;
    }
    const iv = toStrictArrayBuffer(payload.subarray(0, IV_LEN_BYTES));
    const cipherBuf = toStrictArrayBuffer(payload.subarray(IV_LEN_BYTES));
    const plainBuf = await crypto.subtle.decrypt(
      { name: 'AES-GCM', iv, tagLength: TAG_LEN_BITS },
      key,
      cipherBuf
    );
    return new TextDecoder().decode(plainBuf);
  } catch {
    return DECRYPT_FAILED_FALLBACK;
  }
}

/**
 * 解密服务端 CachedMessage 中的密文字段（text 与 replyToPreview），保持结构不可变返回新对象
 */
export async function decryptCachedMessage(
  roomCode: string,
  roomPassword: string,
  cm: CachedMessage
): Promise<CachedMessage> {
  if (cm.kind === 'image' || cm.kind === 'audio' || cm.kind === 'file_notice') {
    if (!isE2eeCiphertext(cm.caption) && !isE2eeCiphertext(cm.text)) {
      return cm;
    }
  }
  const needText = isE2eeCiphertext(cm.text);
  const needPreview = isE2eeCiphertext(cm.replyToPreview);
  const needCaption = isE2eeCiphertext(cm.caption);
  if (!needText && !needPreview && !needCaption) {
    return cm;
  }
  const [text, replyToPreview, caption] = await Promise.all([
    needText ? decryptChatText(roomCode, roomPassword, cm.text) : Promise.resolve(cm.text),
    needPreview ? decryptChatText(roomCode, roomPassword, cm.replyToPreview) : Promise.resolve(cm.replyToPreview),
    needCaption ? decryptChatText(roomCode, roomPassword, cm.caption) : Promise.resolve(cm.caption),
  ]);
  return {
    ...cm,
    text,
    replyToPreview,
    caption,
  };
}
