# 📡 LAN-Caster

LAN-Caster is a high-speed, Peer-to-Peer (P2P) file transfer desktop application designed for local networks. It allows users to send large files directly between devices without relying on the internet, cloud servers, or third-party web frameworks.

Built with **Core Java**, **JavaFX**, and **Maven**.

## 🚀 Features
* **Zero Internet Required:** Transfers happen purely over your Local Area Network (LAN).
* **Blazing Fast:** Uses direct TCP Sockets for maximum router speed (often 100MB/s+).
* **Cross-Platform:** Native installers for Windows (.msix) and Linux (.deb).
* **Lightweight:** Built on standard Java libraries without heavy web frameworks.
* **Secure:** Data goes straight from Sender to Receiver; no middleman.

---

## 📥 Download & Install

### 🪟 Windows
1.  **[Download the Installer (.msix)](https://001albin.github.io/LAN-Caster/lan-caster-1.0.0.x64.msix)**
2.  Double-click the file.
3.  Click **Install** (Windows will handle the dependencies).
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
