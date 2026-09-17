export function sanitizePayload(value) {
    return sanitizeValue(value);
}
export function sanitizeText(value) {
    if (!value)
        return value;
    const kind = kindOf(value);
    if (kind || looksMostlyBinary(value)) {
        return omitted(Buffer.byteLength(value), kind);
    }
    if (value.includes('\0'))
        return value.replace(/\0/g, '');
    return value;
}
function sanitizeValue(value) {
    if (typeof value === 'string')
        return sanitizeText(value);
    if (Array.isArray(value))
        return value.map((item) => sanitizeValue(item));
    if (value && typeof value === 'object') {
        const record = value;
        const copy = {};
        for (const [key, item] of Object.entries(record))
            copy[key] = sanitizeValue(item);
        return copy;
    }
    return value;
}
function looksMostlyBinary(value) {
    const n = Math.min(value.length, 4096);
    let control = 0;
    for (let i = 0; i < n; i += 1) {
        const code = value.charCodeAt(i);
        if (code < 32 && code !== 9 && code !== 10 && code !== 13)
            control += 1;
    }
    return n >= 16 && control * 20 > n;
}
function kindOf(value) {
    if (value.includes('PK\u0003\u0004'))
        return 'ZIP';
    if (value.includes('%PDF-'))
        return 'PDF';
    if (value.charCodeAt(0) === 0x1f && value.charCodeAt(1) === 0x8b)
        return 'GZIP';
    if (value.startsWith('\u0089PNG'))
        return 'PNG';
    return undefined;
}
function omitted(bytes, kind) {
    if (!kind)
        return `[\u5df2\u7701\u7565\u4e8c\u8fdb\u5236\u5185\u5bb9\uff0c${bytes} \u5b57\u8282]`;
    return `[\u5df2\u7701\u7565\u4e8c\u8fdb\u5236\u5185\u5bb9\uff08${kind}\uff09\uff0c${bytes} \u5b57\u8282]`;
}
