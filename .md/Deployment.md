## Production deployment: Input and Output

This takes the server from [Ubuntu Server.md](Ubuntu%20Server.md) and the router from [Router.md](Router.md) to a running library. Library files go to the external drive at `/mnt/library` (`app.storage.root: /mnt/library/files` in `application-prod.yml`). The app's upload temp folder is `/mnt/library/files/temp`, on the same drive. Tomcat buffers each upload in `/tmp` on the SSD while it arrives and deletes that copy after the request.

Outputs marked **(expected)** haven't been run yet. Replace them with what you see.

**Step 1: Create the database**

- **What it does:** creates the empty `library` database and the account the app logs in with. The prod JDBC URL has no `createDatabaseIfNotExist`, so the database has to exist first. Flyway creates the tables on the first start.
- **Input:** `sudo mysql`, then:

    ```sql
    CREATE DATABASE library CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci;
    CREATE USER 'library'@'localhost' IDENTIFIED BY '<strong DB password>';
    GRANT ALL PRIVILEGES ON library.* TO 'library'@'localhost';
    EXIT;
    ```

- **Output (expected):** `Query OK` three times. `mysql -u library -p -e "SHOW DATABASES;"` lists `library`.

**Step 2: Create the app folders**

- **What it does:** makes the folders for the JAR, the secrets file and the log. The log path matches `logging.file.name` in `application-prod.yml`.
- **Input:**

    ```
    sudo mkdir -p /opt/library /etc/library /var/log/sman11-library
    sudo chown library:library /var/log/sman11-library
    ```

- **Output (expected):** no output. `ls -ld /var/log/sman11-library` shows owner `library library`.

**Step 3: Write the secrets file**

- **What it does:** keeps the passwords out of the JAR and out of git. Only root can read the file, and systemd passes the values to the app.
- **Input:** `openssl rand -hex 32` (copy the output for the JWT secret), then `sudo nano /etc/library/library.env`:

    ```
    DB_PASSWORD=<the password from Step 1>
    JWT_SECRET=<the openssl output>
    ADMIN_USERNAME=admin
    ADMIN_PASSWORD=<first librarian password>
    ```

    then `sudo chmod 600 /etc/library/library.env`.
- **Output (expected):** `sudo ls -l /etc/library/library.env` shows `-rw-------`, owner `root`.
- **Note:** `ADMIN_PASSWORD` is used once, when no librarian exists yet. If it is missing, the app falls back to `admin123`.

**Step 4: Build and copy the JAR**

- **What it does:** builds the app on your PC and installs it on the server. Start Docker Desktop first. Without it, the integration tests are skipped, so a green build proves little.
- **Input (Windows PC, in the project folder):**

    ```powershell
    .\mvnw.cmd clean package
    scp target\library-0.0.1-SNAPSHOT.jar admin-sekolah@<server IP>:/tmp/library.jar
    ```

    **Input (server):**

    ```
    sudo install -o root -g library -m 640 /tmp/library.jar /opt/library/library.jar
    rm /tmp/library.jar
    ```

- **Output (expected):** `BUILD SUCCESS` with `Skipped: 0`. `ls -l /opt/library` shows `library.jar`, owner `root library`.

**Step 5: Create the systemd service**

- **What it does:** runs the app as the `library` user, starts it on boot after MySQL, and restarts it if it crashes. There is deliberately no `RequiresMountsFor=/mnt/library`: without the drive the app must still start in degraded mode, so browsing keeps working.
- **Input:** `sudo nano /etc/systemd/system/library.service`:

    ```ini
    [Unit]
    Description=SMAN 11 Garut Library backend
    After=network.target mysql.service local-fs.target
    Wants=mysql.service

    [Service]
    User=library
    Group=library
    WorkingDirectory=/opt/library
    EnvironmentFile=/etc/library/library.env
    ExecStart=/usr/bin/java -Xmx1g -jar /opt/library/library.jar --spring.profiles.active=prod
    Restart=on-failure
    RestartSec=10
    SuccessExitStatus=143

    [Install]
    WantedBy=multi-user.target
    ```

    then:

    ```
    sudo systemctl daemon-reload
    sudo systemctl enable --now library
    journalctl -u library -f
    ```

- **Output (expected):** the log ends with `Started LibraryApplication`. `curl http://127.0.0.1:8080/actuator/health` returns `{"status":"UP"}`.

**Step 6: Configure Nginx**

- **What it does:** serves the front-end and passes `/api` to the app. `proxy_request_buffering off` streams uploads straight to Spring, so Nginx doesn't make another 2 GB copy in `/var/lib/nginx`. `proxy_buffering off` streams downloads and video. `/actuator` isn't proxied, so it stays reachable only from the server itself.
- **Input:** `sudo nano /etc/nginx/sites-available/library`:

    ```nginx
    server {
        listen 80 default_server;
        server_name _;

        client_max_body_size 2g;

        root /var/www/library;
        index index.html;
        location / { try_files $uri $uri/ /index.html; }

        location /api/ {
            proxy_pass http://127.0.0.1:8080;
            proxy_request_buffering off;
            proxy_buffering off;
            proxy_read_timeout 600s;
            proxy_send_timeout 600s;
            proxy_set_header Host $host;
            proxy_set_header X-Real-IP $remote_addr;
        }
    }
    ```

    then:

    ```
    sudo mkdir -p /var/www/library
    sudo ln -s /etc/nginx/sites-available/library /etc/nginx/sites-enabled/
    sudo rm /etc/nginx/sites-enabled/default
    sudo nginx -t && sudo systemctl reload nginx
    ```

- **Output (expected):** `syntax is ok` and `test is successful`. `curl http://127.0.0.1/api/health` returns `{"status":"UP",...}`.

**Step 7: Test from a phone**

- **What it does:** proves students and the librarian can use the app over the library Wi-Fi, and that the files land on the external drive.
- **Input:** on a phone on the library Wi-Fi, open `http://<server IP>/api/books`. Then log in as the librarian with `POST /api/auth/login` (Postman, or the front-end once it's deployed) and upload a PDF. On the server, run `ls -R /mnt/library/files/books`.
- **Output (expected):** the books JSON on the phone, and the uploaded file under `/mnt/library/files/books/<yyyy>/<MM>/`.

**Step 8: Drive-unplugged test**

- **What it does:** proves that a missing drive degrades the app instead of breaking it, and that nothing gets written to the SSD.
- **Input:** `sudo systemctl stop library`, `sudo umount /mnt/library`, unplug the drive, `sudo systemctl start library`, then `curl http://127.0.0.1/api/health` and a download (`/api/books/<id>/download`). Then plug the drive back in, run `sudo mount -a`, and repeat the health check.
- **Output (expected):** with the drive out, health shows `"status":"DEGRADED"` with storage `UNAVAILABLE`, browsing works, and the download returns `503 STORAGE_UNAVAILABLE`. With the drive back in, health shows `UP` without restarting the app.

**Step 9: Nightly database backup**

- **What it does:** dumps the database to the external drive every night and keeps 14 copies. The photos and videos can't be downloaded again, so also keep a second copy of `/mnt/library/files` on another disk ([backend-phases.md](backend-phases.md) §3).
- **Input:**

    ```
    sudo mkdir -p /mnt/library/backups
    sudo nano /etc/cron.d/library-backup
    ```

    ```
    0 2 * * * root mountpoint -q /mnt/library && mysqldump --single-transaction library | gzip > /mnt/library/backups/library-$(date +\%F).sql.gz && find /mnt/library/backups -name 'library-*.sql.gz' -mtime +14 -delete
    ```

- **Output (expected):** the next morning, `ls /mnt/library/backups` shows `library-<date>.sql.gz`. Restore it once into a test database to prove the backup works.

**Step 10: Updating the app later**

- **What it does:** replaces the JAR safely. Flyway applies any new migrations when the app starts.
- **Input:** build and `scp` as in Step 4, then:

    ```
    sudo systemctl stop library
    sudo sh -c 'mysqldump --single-transaction library | gzip > /mnt/library/backups/pre-update-$(date +%F).sql.gz'
    sudo install -o root -g library -m 640 /tmp/library.jar /opt/library/library.jar
    sudo systemctl start library
    ```

- **Note:** the backup runs inside `sudo sh -c '...'` because `/mnt/library/backups` belongs to root. With a plain `sudo mysqldump ... > file`, only `mysqldump` runs as root. Your own shell opens the file and fails with `Permission denied` (and `mysqldump: Got errno 11 on write`).
- **Output (expected):** `ls -lh /mnt/library/backups` shows today's `pre-update-<date>.sql.gz` (not 0 bytes). `journalctl -u library` shows `Started LibraryApplication`, and `/api/health` returns `UP`.
