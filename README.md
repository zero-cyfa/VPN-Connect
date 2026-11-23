# VPN Connect (Android, IPsec)

**Author:** zero.cyfa

An Android app for Sophos Firewall IPsec VPNs with the same layout as the Sophos Connect desktop client.
It imports any Sophos Connect `.scx` file and connects the same IPsec tunnel that Sophos Connect
makes on Windows.

## Download

The APK package is attached to each release on the repository's **Releases** page
(e.g. `VpnConnect-v0.3.apk`). Download it on the phone, open it, and allow "Install unknown apps" if asked.

## Linux

A Linux desktop version lives in [`linux/`](linux/README.md): the same `.scx` import and tunnel,
using your distribution's strongSwan. Its `.deb`, `.rpm` and Arch/tarball packages are on the
Releases page under the `linux-v0.2` release.

## What it does

Sophos Connect's own log shows how this tunnel works, and the app follows it step by step:

1. IKEv1 **Main Mode** to the gateway (AES-256, SHA-256, DH group 14), NAT-T on port 4500.
2. Pre-shared key, then **XAuth** (your user name and password).
3. **Mode Config**: the gateway assigns the phone a virtual IP and DNS servers.
4. One **Quick Mode** tunnel (ESP, AES-256, SHA-512, PFS group 14) for each network listed in the
   `.scx`, and only those networks are routed through the VPN. DPD every 30 s.

All values (gateway, key, algorithms, networks, timers) are read from the `.scx`.

## Build it (no Android tools needed): GitHub Actions

1. Create a free GitHub account and a **private** repository.
2. Upload the contents of this folder to it (including the hidden `.github` folder).
3. Open the **Actions** tab, choose **Build Android APK**, press **Run workflow**.
4. After about 30 to 60 minutes, open the finished run and download **VpnConnect-apk**.
5. Unzip it and install the `.apk` on the phone (allow installs from your file manager).

If a step fails, open it, copy the last 40 lines of its log, and send them to me.

## Build it locally (Linux or Windows WSL)

    sudo apt install autoconf automake libtool gperf bison flex pkg-config jq make perl libssl-dev libgmp-dev git
    ./apply.sh strongswan
    # then follow strongswan/src/frontends/android/README.ndk (OpenSSL for Android, autogen/configure/make,
    # then ./gradlew assembleDebug in strongswan/src/frontends/android)

## Using the app

1. **⋮ menu > Import connection**, pick your `.scx` file (opening the file from a file manager works too). The title line shows "VPN Connect" until a file is imported, then the imported file's name. Importing another `.scx` adds it as a new connection, or updates the existing one with the same gateway or name.
2. Tap the connection, then **Connect**. Allow the Android VPN prompt.
3. On **Authenticate user** type your user name and password, then **Sign in**.
4. **Disconnect** when done. The **Events** tab lists connects, disconnects and failures.
5. If it fails: **⋮ > Open VPN log > Share**, and send the log to ICT support. The log is detailed
   (it shows addresses and your user name, not passwords or keys): remove your user name first.

## What is stored

The pre-shared key from the `.scx` is stored in the app's private database (Android keeps it away
from other apps; backups are off). Your user name is remembered. **The password is never saved.**
Treat the `.scx` file itself as confidential too.

## Testing

Tested: v0.2 builds in GitHub Actions, installs on an Android phone, imports a `.scx` file and
connects to the firewall.

If a connection fails, open **⋮ > Open VPN log** and send the log for diagnosis.

## Known limits

- IPv4 only. No traffic counters (strongSwan does not expose them).
- The launcher icon (blue rings) is replaced on all Android versions.
- If the gateway refuses one of the networks, that tunnel is skipped and the log says so.

## Licence

Built on strongSwan (GPLv2). Anyone you give the APK to is entitled to this source. Keep the
About text. Do not use Sophos names or logos.

VPN Connect by zero.cyfa.
