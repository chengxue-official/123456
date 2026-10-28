import base64, binascii

def decode_hongguo_base64(value):
    value = value.strip()
    try:
        return base64.b64decode(value, validate=True)  # StdEncoding.Strict
    except Exception:
        # RawStdEncoding.Strict: no padding
        return base64.b64decode(value, validate=True)  # same as above but try without padding handled by b64decode lenient? emulate

def ones_count(n):
    return bin(n).count('1')

def hongguo_content_key(value):
    raw = bytearray(base64.b64decode(value, validate=True))
    if len(raw) < 3:
        raise ValueError('too short')
    tag_length = (raw[0] ^ raw[1] ^ raw[2]) - 48
    content_length = len(raw) - tag_length - 1
    if tag_length < 1 or content_length < 33 or content_length >= len(raw):
        raise ValueError('bad structure')
    seed = raw[len(raw) - tag_length - 2] ^ raw[len(raw) - tag_length - 1]
    tag = bytearray(tag_length)
    for i in range(tag_length):
        tag[i] = raw[len(raw) - tag_length + i] ^ seed
    if bytes(tag) in (b'app_v2', b'web_v2'):
        raise ValueError('unsupported version')
    decoded = bytearray(content_length)
    prev_even = 250
    prev_odd = 85
    for index in range(content_length):
        current = raw[1 + index]
        prev = prev_even
        if index % 2 == 0:
            prev_even = current
        else:
            prev = prev_odd
            prev_odd = current
        decoded[index] = (prev ^ current) - 21 - ones_count(index) & 0xFF
    padding = int(chr(decoded[0]), 36)  # base36
    if content_length - padding - 1 != 32:
        raise ValueError('bad padding')
    key = bytes.fromhex(bytes(decoded[1:33]).decode('ascii'))
    if len(key) != 16:
        raise ValueError('bad key')
    return key

spade = "orws+mKxG8pTgB34Z4U2zXiLMcx9gzPOSLUE+k+EHs5hgCmYmA=="
raw = base64.b64decode(spade, validate=True)
print('raw len =', len(raw))
key = hongguo_content_key(spade)
print('key hex =', key.hex())
print('expected = c075bd047abf9050204865bd472f81e8')
print('MATCH' if key.hex() == 'c075bd047abf9050204865bd472f81e8' else 'MISMATCH')
