## Router setup: Input and Output (final)

**Step 1: Connect the hardware**

- **What it does:** powers the router and links the mini PC to it. The WAN (blue) port stays empty, because the router needs no internet.
- **Input:** router powered on, mini PC's Ethernet cable in a **LAN** port, your PC on the router's Wi-Fi.
- **Output:** the PC connected to the router's network (with "no internet", which is expected).

**Step 2: Open the admin page**

- **What it does:** lets you configure the router.
- **Input:** `http://tplinkwifi.net` (or `http://192.168.0.1`) in a browser.
- **Output:** the TP-Link login or first-setup page, where you created the admin password.

**Step 3: Run Quick Setup**

- **What it does:** sets the basic connection options. They don't matter much here, since there is no upstream internet.
- **Input:** Operation mode **Router**, Internet connection type **Dynamic IP**, MAC address **Use Default MAC Address**.
- **Output:** the wizard continued to the Wi-Fi step.

**Step 4: Set the Wi-Fi**

- **What it does:** names and secures the library network.
- **Input:** a network name for 2.4 GHz and 5 GHz, security WPA2-PSK, a simple password, both bands left on.
- **Output:** the library Wi-Fi visible on your phone.

**Step 5: Admin password**

- **What it does:** stops students on the Wi-Fi from changing the router's settings. It is separate from the Wi-Fi password.
- **Input:** a router admin password.
- **Output:** the admin page asks for it at the next login.

**Step 6: Diagnose the missing IPv4**

- **What it does:** shows why the mini PC got no address.
- **Input:** `networkctl status enp0s31f6`
- **Output:** `degraded (configuring)`, only an `fe80::` address, no IPv4. The cable was in the blue **WAN** port.

**Step 7: Fix the port**

- **What it does:** moves the cable to a LAN port, where the router's DHCP server answers.
- **Input:** cable moved to a LAN port, then `ip -br a` on the server.
- **Output:** `enp0s31f6` received an IPv4 address.

**Step 8: Find the server in the router**

- **What it does:** shows the address the router assigned, which you use for SSH.
- **Input:** the router's DHCP client list, entry `perpustakaan`, MAC `6c:4b:90:35:32:93`.
- **Output:** the server's IP address.

**Step 9: SSH through the router**

- **What it does:** proves you can manage the server on the library's own network, with no Tailscale and no internet.
- **Input:** `ssh admin-sekolah@<IP from the router>`
- **Output:** prompt `admin-sekolah@perpustakaan:~$`.

**Step 10: Test from a phone**

- **What it does:** proves students will be able to reach the server.
- **Input:** `http://<server IP>/` on a phone on the library Wi-Fi.
- **Output:** the **"Welcome to nginx!"** page.

**Step 11: DHCP reservation**

- **What it does:** ties the server's MAC address to one IP, so the router always gives it the same address.
- **Input:** **Advanced > Network > DHCP Server > Address Reservation > Add**, MAC `6c:4b:90:35:32:93`, an IP outside the DHCP pool, Enable, Save.
- **Output:** the server keeps the same IP after every reboot.

**Step 12: Harden the router**

- **What it does:** closes features the library doesn't need.
- **Input:** WPS turned off (**Advanced > Wireless > WPS**), UPnP turned off (**Advanced > NAT Forwarding > UPnP**).
- **Output:** a smaller attack surface. AP Isolation has no option on your model, and your phone reaching the server shows clients aren't isolated.

**Step 13: Reboot test**

- **What it does:** confirms the IP survives a restart.
- **Input:** `ip -br a`, then `sudo reboot`, then `ip -br a` again.
- **Output:** you said you did all of it, but you haven't pasted the before and after lines. If they match, the router is finished.

Next is Phase A, the production build. Paste your `application-prod.yml` (no real secrets) when you're ready.