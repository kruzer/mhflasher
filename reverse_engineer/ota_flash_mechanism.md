# OTA Flash Mechanism - Magic Home to OpenBeken

## Goal

Magic Home devices such as bulbs and LED controllers with the BL602 chip
(HF-LPT230 module) include a built-in OTA update mechanism controlled by AT
commands over UDP. MagicHomeFlasher uses this mechanism to replace the original
ZengGe/Magic Home firmware with OpenBeken.

## Chip and Original Firmware

- Chip: **BL602** (RISC-V)
- WiFi module: **HF-LPT230** (Hi-Flying)
- Original firmware: ZengGe / Magic Home LED controller

## Communication Protocol

The device listens on **UDP port 48899** and accepts plain-text AT commands.

### Key Commands

| Command | Description |
|---------|-------------|
| `HF-A11ASSISTHREAD` | Broadcast discovery. The device replies with `IP,MAC,DeviceID`. |
| `AT+LVER` | Returns the firmware version, for example `+ok=x.xx.xxx`. |
| `AT+UPURL=<url>,<password>` | Starts OTA download from the given URL. |

CozyLife firmware also supports starting OTA over TCP port 5555 with a JSON
`cmd=5` command. This is a separate way to trigger the update; the OTA file is
still downloaded from the phone over HTTP.

### Discovery and Identification

```text
UDP -> 48899: HF-A11ASSISTHREAD
UDP <- reply: 10.10.123.3,AABBCCDDEEFF,ZGxxxxxxxx
              ^^^IP         ^^^MAC        ^^^DeviceID
```

After discovery, the app sends `AT+LVER` to the discovered IP address, not as a
broadcast, to confirm that the target is the expected device.

### OTA Start

```text
UDP -> 48899: AT+UPURL=http://10.10.123.4:1111/update?version=<ver>&beta,pierogi
```

- `10.10.123.4` is the phone IP address assigned by DHCP when the phone is
  connected to the device AP.
- `1111` is the HTTP server port opened by the app.
- `version=` is informational. The device logs it but does not validate it.
- `pierogi` is the fixed password string required by the firmware.
- `beta` is required by the firmware to accept unofficial OTA images.

After receiving this command, the device performs an HTTP GET to the provided URL
and downloads the binary OTA file.

## OTA File Format

The original ZengGe firmware accepts OTA files in this format:

```text
<Hi-Flying OTA header> + <xz payload>
```

File extension: `.bin.xz.ota`

OpenBeken releases provide ready-made files in this format, named like
`OpenBL602_x.xx.xxx_OTA.bin.xz.ota`.

In practice, some ZengGe firmware versions rejected the standard OTA file.
Preparing the OTA image inside the app fixed this: the app uses a ZengGe-compatible
header, then patches the compressed payload length and SHA-256 hash after
compression.

The current flow builds the OTA file dynamically from:

- the small OpenBeken payload `OpenBL602_small_..._OTA.bin`, which fits CozyLife
  devices with 1MB flash;
- the header template `OpenBL602_dev_..._OTA_zengge.bin.xz.ota`, which fixed OTA
  acceptance on ZengGe firmware;
- optional injected WiFi configuration stored in the `OBKCFG1` structure.

### Integrity Check

The firmware verifies the OTA file checksum. A truncated file, for example one
created with `dd ... count=400`, causes a checksum error. The device rejects the
flash, but the transfer itself still completes. This was useful for testing the
HTTP transfer path without risking a brick.

## MagicHomeFlasher App Flow

```text
1. The app starts an HTTP server on port 1111.
2. The user connects the phone to the device WiFi AP, for example SSID ZG_xxxxxxxx.
3. Android gets a DHCP lease from the device AP.
4. The app sends UDP broadcast HF-A11ASSISTHREAD and reads the device IP.
5. The app sends AT+LVER to confirm the firmware version.
6. The user taps Flash.
7. For AT OTA, the app sends AT+UPURL with its own HTTP URL.
8. For CozyLife TCP OTA, the app sends JSON cmd=5 to TCP port 5555 with the same HTTP URL.
9. The device connects to the app HTTP server and downloads the .bin.xz.ota file.
10. The device verifies the checksum, writes flash, and reboots into OpenBeken.
```

## Tested Devices and Firmware

- **AI-WB2-32S** development board with BL602 and original ZengGe LED firmware.
- Magic Home BL602: OTA already worked through standard AT commands.
- ZengGe BL602: OTA acceptance required patching the OTA header.
- CozyLife BL602 with 1MB flash: required TCP port 5555 for OTA start and the
  smaller firmware payload.

## OTA File Location in the Project

`mhflasher/src/main/assets/OpenBL602_<version>_OTA.bin.xz.ota`

When updating to a new version:

1. Copy the new `.bin.xz.ota` file or raw OTA payload to `assets/`.
2. Update the filename in `MainActivity.kt` where `context.assets.open(...)` is used.
3. Update the version string in `CMD.OTA`; it is informational and used for logs.
