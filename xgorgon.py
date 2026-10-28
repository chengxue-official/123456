#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""X-Gorgon 签名复现 —— 还原自 com.houg.tv (红果tv简洁版.apk)"""
import hashlib
import time
from urllib.parse import quote

SALT = bytes.fromhex("44b9b9d9a4aef9fca493aa757ca3c2c4a496938f")  # 20B
PREFIX = bytes([0x84, 0x04, 0x40, 0x1C, 0x00, 0x00])            # 6B
MAGIC = bytes([0x00, 0x06, 0x0B, 0x1C])
BITREV = [int(f"{i:08b}"[::-1], 2) for i in range(256)]

HOST = "https://api5-normal-sinfonlineb.fqnovel.com"
USER_AGENT = ("com.phoenix.read/73532 (Linux; U; Android 16; zh_CN; 25053RT47C; "
              "Build/BP2A.250605.031.A3; Cronet/TTNetVersion:04657795 2026-01-23 "
              "QuicVersion:c67e9834 2025-09-08")

BASE_PARAMS = {
    "ac": "wifi", "aid": "8662", "app_name": "novelread",
    "channel": "update_64", "device_brand": "Redmi",
    "device_id": "5512582981050003032", "device_platform": "android",
    "device_type": "25053RT47C", "dpi": "520",
    "iid": "6341557767548773986", "language": "zh",
    "manifest_version_code": "73532", "os": "android", "os_api": "36",
    "os_version": "16", "resolution": "1280*2772", "ssmix": "a",
    "update_version_code": "73532", "version_code": "73532",
    "version_name": "7.3.5.32",
}


def md5(data: bytes) -> bytes:
    return hashlib.md5(data).digest()


def build_query(params: dict) -> str:
    parts = []
    for k in sorted(params.keys()):
        parts.append(f"{quote(str(k), safe='')}={quote(str(params[k]), safe='')}")
    return "&".join(parts).replace("*", "%2A")


def xgorgon(query: str, body: bytes, unix_sec: int) -> str:
    buf = bytearray(20)
    buf[0:4] = md5(query.encode("utf-8"))[0:4]
    if body:
        buf[4:8] = md5(body)[0:4]
    buf[8:12] = MAGIC
    sec = unix_sec & 0xFFFFFFFF
    buf[12] = (sec >> 24) & 0xFF
    buf[13] = (sec >> 16) & 0xFF
    buf[14] = (sec >> 8) & 0xFF
    buf[15] = sec & 0xFF

    for i in range(20):
        buf[i] ^= SALT[i]

    for i in range(20):
        v = buf[i] & 0xFF
        v = ((v << 4) | (v >> 4)) & 0xFF
        v ^= buf[(i + 1) % 20] & 0xFF
        v = BITREV[v]
        buf[i] = ((~v) ^ 0x14) & 0xFF

    return (PREFIX + bytes(buf)).hex().upper()


def build_headers(path: str, extra: dict, body: bytes):
    unix_sec = int(time.time())
    params = dict(BASE_PARAMS)
    if extra:
        params.update(extra)
    params["_rticket"] = str(int(time.time() * 1000))
    query = build_query(params)
    url = f"{HOST}{path}?{query}"
    headers = {
        "User-Agent": USER_AGENT,
        "X-Khronos": str(unix_sec),
        "X-SS-STUB": md5(body).hex().upper() if body else md5(b"").hex().upper(),
        "X-Gorgon": xgorgon(query, body, unix_sec),
    }
    if body:
        headers["Content-Type"] = "application/json"
    return url, headers


def video_model(video_id: str):
    payload = ('{"video_id":"%s","content_type":1,"biz_param":'
               '{"need_all_video_definition":true,"video_platform":3}}' % video_id)
    return build_headers(
        "/novel/player/video_model/v1/",
        {"video_id": video_id},
        payload.encode("utf-8"),
    )


if __name__ == "__main__":
    q = "aid=8662&app_name=novelread&version_code=73532"
    b = b'{"video_id":"123456","content_type":1}'
    print("X-Gorgon:", xgorgon(q, b, 1767225600))
    print("X-SS-STUB:", md5(b).hex().upper())
    url, h = video_model("7123456789")
    print("URL:", url)
    for k, v in h.items():
        print(f"  {k}: {v}")
