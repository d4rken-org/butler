---
layout: plain
permalink: /security
title: "Security"
---

# Security

## Verifying APK authenticity

You can verify a downloaded or installed APK against the fingerprints below using
[`apksigner`](https://developer.android.com/tools/apksigner) or
[AppVerifier](https://github.com/soupslurpr/AppVerifier):

```bash
apksigner verify --print-certs eu.darken.butler-*.apk
```

### FOSS build (GitHub Releases)

```
SHA-256: D6:CC:28:FA:61:EF:3E:6D:C3:2E:2B:62:92:8B:C4:54:26:10:B7:36:9A:EF:EF:A5:DE:1C:30:56:28:65:63:70
SHA-1:   0E:68:0E:B2:55:F8:9C:3F:53:54:6D:49:34:5C:C2:B1:A4:82:5E:CE
MD5:     62:22:0A:6D:77:B4:DF:89:D1:5E:7A:35:73:C3:4C:0A
```

### Google Play build

```
SHA-256: A0:C0:FE:AA:6A:29:9D:07:F8:29:7E:0D:54:2C:E7:34:EC:5C:82:A6:81:B2:E5:26:21:F5:A2:6C:AD:CA:83:F5
SHA-1:   A2:32:1C:99:8F:16:75:D5:F4:74:8B:8E:3F:8D:35:29:C5:08:B6:90
```
