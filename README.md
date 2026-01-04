# 📡 LAN-Caster

LAN-Caster is a high-speed, Peer-to-Peer (P2P) file transfer application designed for local networks. It allows users to send large files directly between devices without relying on internet speed or cloud servers.

Built with **Java**, **Spring Boot**, and **JavaFX**.

## 🚀 Features
* **Zero Internet Required:** Transfers happen over your Local Area Network (LAN).
* **High Speed:** Limited only by your router speed (often 10x faster than internet).
* **Cross-Platform:** Works seamlessly on Windows and Linux (Kali, Ubuntu, Debian).
* **No File Size Limit:** Send huge files (GBs or TBs) without restrictions.
* **Secure:** Direct device-to-device connection.

---

## 📥 Download & Install

### 🪟 Windows
1.  **[Download the Installer (.msix)](https://001albin.github.io/LAN-Caster/lan-caster-1.0.0.x64.msix)**
2.  Double-click the file.
3.  Click **Install**.
4.  Launch "LAN-Caster" from your Start Menu.

### 🐧 Linux (Debian/Ubuntu/Kali)
Open your terminal and run these commands to download and install automatically:

```bash
# 1. Download the correct version for your CPU
CPU=$(dpkg --print-architecture)
wget [https://001albin.github.io/LAN-Caster/debian/lan-caster_1.0.0_$](https://001albin.github.io/LAN-Caster/debian/lan-caster_1.0.0_$){CPU}.deb

# 2. Install the app
sudo apt install ./lan-caster_1.0.0_${CPU}.deb

# 3. Run the app
lan-caster
