

**Step 1: Find the problem**

- **Input:** `ip -br a`
- **Output:** `enp0s31f6` was `UP` with only IPv6 addresses (`2404:c0:ab08:...`) and no IPv4.

**Step 2: Read the network log**

- **Input:** `journalctl -u systemd-networkd -n 20 --no-pager`
- **Output:** only DHCPv6 lines, with no IPv4 DHCP attempt.

**Step 3: Find the config file**

- **Input:** `ls /etc/netplan/`
- **Output:** `00-installer-config.yaml`

**Step 4: Read the config**

- **Input:** `sudo cat /etc/netplan/00-installer-config.yaml`
- **Output:** `enp0s31f6` with MAC `6c:4b:90:35:32:93` and no `dhcp4: true`.

**Step 5: Turn on IPv4 DHCP**

- **Input:**
    
    ```
    sudo nano /etc/netplan/00-installer-config.yaml
    ```
    
    ```yaml
    network:
      ethernets:
        enp0s31f6:
          dhcp4: true
          match:
            macaddress: 6c:4b:90:35:32:93
          set-name: enp0s31f6
      version: 2
      wifis: {}
    ```
    
    ```
    sudo netplan try
    ```
    
- **Output:** `ip -br a` showed an IPv4 address on `enp0s31f6`.

**Step 6: Connect by SSH from your PC**

- **Input:** `ssh admin-sekolah@<ip>`
- **Output:** prompt `admin-sekolah@perpustakaan:~$`.

**Step 7: Install Tailscale**

- **Input:**
    
    ```
    curl -fsSL https://tailscale.com/install.sh | sh
    sudo tailscale up
    ```
    
- **Output:** a login link, then the device joined your account (`100.89.4.10`).

**Step 8: Configure Ethernet, USB tether and Wi-Fi**

- **Input:**
    
    ```
    sudo nano /etc/netplan/00-installer-config.yaml
    ```
    
    ```yaml
    network:
      version: 2
      ethernets:
        enp0s31f6:
          match:
            macaddress: 6c:4b:90:35:32:93
          set-name: enp0s31f6
          dhcp4: true
          dhcp4-overrides:
            route-metric: 300
        usbtether:
          match:
            name: "enx*"
          dhcp4: true
          dhcp4-overrides:
            route-metric: 100
      wifis:
        wlxe009bf0fe996:
          dhcp4: true
          dhcp4-overrides:
            route-metric: 200
          access-points:
            "YourWifiName":
              password: "YourWifiPassword"
    ```
    
    ```
    sudo chmod 600 /etc/netplan/00-installer-config.yaml
    sudo netplan try
    ```
    
- **Output:** no errors, and all three interfaces got IPv4 addresses.

**Step 9: Test Wi-Fi**

- **Input:** `ping -4 -c 3 google.com`, `ip route get 1.1.1.1`, `ip -br a`
- **Output:** 0% loss. The route was `dev wlxe009bf0fe996` (`192.168.100.144`).

**Step 10: Test USB tethering**

- **Input:** (phone plugged in, tethering on) `ip route get 1.1.1.1`, `ping -4 -c 3 google.com`
- **Output:** route `via 10.13.18.68 dev enxfe992dc685c3`, 0% loss.

**Step 11: Update the system**

- **Input:** `sudo apt update && sudo apt full-upgrade -y`
- **Output:** packages upgraded, no errors.

**Step 12: Set the time zone**

- **Input:** `sudo timedatectl set-timezone Asia/Jakarta`
- **Output:** no output. `timedatectl` shows `Time zone: Asia/Jakarta (WIB, +0700)`.

**Step 13: Install the project software**

- **Input:** `sudo apt install -y openjdk-21-jre-headless mysql-server nginx ffmpeg`
- **Output:** completed without errors.

**Step 14: Check the versions**

- **Input:** `java -version`, `nginx -v`, `mysql --version`, `ffprobe -version`
- **Output:** OpenJDK 21.0.13.1, nginx 1.28.3, MySQL 8.4.13, ffprobe 8.0.1 (Ubuntu 26.04 LTS).

**Step 15: Check the services start on boot**

- **Input:** `systemctl is-enabled mysql nginx`
- **Output:** `enabled` and `enabled`.



## External hard drive setup: Input and Output

**Step 1: Find the drive**

- **What it does:** lists all disks, so you can tell the external drive from the internal one before touching anything.
- **Input:** `lsblk -o NAME,SIZE,MODEL,TRAN,FSTYPE,MOUNTPOINT`
- **Output:** a `931.5G` disk, model `EZEX-75M2NA0`, `TRAN` = `usb`, with old Windows partitions (vfat, two NTFS). The internal SSD (`119.2G`, `sata`) held Ubuntu.

**Step 2: Check the drive's health**

- **What it does:** reads the drive's self-report (SMART), to catch a failing drive before you trust it with data.
- **Input:** `sudo apt install -y smartmontools` and `sudo smartctl -H -d sat /dev/sdb`
- **Output:** `SMART overall-health self-assessment test result: PASSED` (with the normal USB enclosure warning).

**Step 3: Create one partition on the whole drive**

- **What it does:** wipes the old Windows layout and makes a single partition using all the space.
- **Input:** `sudo parted /dev/sdb --script mklabel gpt mkpart library ext4 1MiB 100%`
- **Output:** no output. `lsblk /dev/sdb` showed one partition, `sdb1`, at about 931.5G.

**Step 4: Format it as ext4**

- **What it does:** writes the Linux file system with the label `library`. `-m 1` reserves 1% instead of 5%, so you keep more usable space.
- **Input:** `sudo mkfs.ext4 -L library -m 1 /dev/sdb1`, then `y` at the "contains a vfat file system" warning.
- **Output:** "Creating journal (262144 blocks)", then done.

**Step 5: Get the UUID**

- **What it does:** shows the drive's permanent ID, which doesn't change when device names like `sda` and `sdb` swap.
- **Input:** `lsblk -f /dev/sdb`
- **Output:** `ext4`, label `library`, UUID `58dee3a3-261e-486c-ad7f-71f9477ca185`.

**Step 6: Create the service user and the mount point**

- **What it does:** makes the account the app will run as (no login allowed), and an empty folder where the drive will appear.
- **Input:**
    
    ```
    sudo useradd --system --home /opt/library --shell /usr/sbin/nologin library
    sudo mkdir -p /mnt/library
    ```
    
- **Output:** no output.

**Step 7: Make it mount automatically**

- **What it does:** adds a line to `/etc/fstab` so the drive mounts at every boot. `nofail` lets the server boot even if the drive is missing, and the timeout stops a long wait.
- **Input:**
    
    ```
    sudo cp /etc/fstab /etc/fstab.bak
    echo 'UUID=58dee3a3-261e-486c-ad7f-71f9477ca185  /mnt/library  ext4  defaults,nofail,noatime,x-systemd.device-timeout=10s  0  2' | sudo tee -a /etc/fstab
    ```
    
- **Output:** the line was echoed back.

**Step 8: Mount it now**

- **What it does:** mounts everything in fstab without rebooting.
- **Input:** `sudo systemctl daemon-reload`, `sudo mount -a`, `findmnt /mnt/library`
- **Output:** `/mnt/library` on `/dev/sda1`, ext4, `rw,noatime`.

**Step 9: Create the folders and marker file**

- **What it does:** makes the folders the app uses, and the `.library-storage` marker that tells the app the drive is really mounted. It then gives the `library` user ownership.
- **Input:**
    
    ```
    sudo mkdir -p /mnt/library/files /mnt/library/import
    sudo touch /mnt/library/files/.library-storage
    sudo chown library:library /mnt/library/files /mnt/library/import /mnt/library/files/.library-storage
    ls -la /mnt/library/files
    ```
    
- **Output:** `.library-storage`, 0 bytes, owner `library library`.

**Step 10: Confirm it's the external drive**

- **What it does:** proves the files are on the external drive, not the internal SSD. The device letters had swapped (the external was now `sda`), and the UUID mount still found it.
- **Input:** `lsblk -o NAME,SIZE,MODEL,TRAN,FSTYPE,MOUNTPOINT` and `df -h /mnt/library /`
- **Output:** `sda1` (931.5G, `usb`) at `/mnt/library` with `907G` available. The internal SSD held `/`.

**Step 11: Reboot test**

- **What it does:** checks the drive mounts by itself after a reboot.
- **Input:** `sudo reboot`, then `findmnt /mnt/library` and `ls -la /mnt/library/files`
- **Output (expected):** the drive mounted at `/mnt/library`, and `.library-storage` still present. I haven't seen this output, so replace it with what you saw.