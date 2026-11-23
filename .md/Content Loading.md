# Content Loading: Input and Output

This explains how books get onto the external drive once the app is live ([Deployment.md](Deployment.md)). The import rules (CSV columns, defaults, duplicates) are owned by the [spec](backend-spec.md#55-bulk-import) and [Phase 6](phase-6-bulk-import.md). This doc covers only the production routine.

Outputs marked **(expected)** haven't been run yet. Replace them with what you see.

## 1. Rule: the drive stays in the server

Never fill the drive by plugging it into a PC.

- **Windows can't write to it.** The drive is ext4 ([Ubuntu Server.md](Ubuntu%20Server.md) Step 4). Windows can't write ext4 without third-party drivers, and those risk corrupting the filesystem.
- **The app ignores files it didn't store.** A book exists only if it went through the upload pipeline: SHA-256 checksum, type check, a DB row, and the app's own file path under `/mnt/library/files/books/<yyyy>/<MM>/`. Files copied straight into `/mnt/library/files` never show up, and they waste space.
- **Unplugging takes the library down.** While the drive is out, every download, upload and import returns `503 STORAGE_UNAVAILABLE`.

Two ways in, both over the LAN:

| Amount | Route |
|---|---|
| A few books | Upload from the front-end admin page (`POST /api/books`). |
| A batch | Copy the files plus a CSV into `/mnt/library/import/books/`, then start a bulk import (the steps below). |

## 2. Bulk import routine

**Step 1: Give your login write access to the import folder (once)**

- **What it does:** `/mnt/library/import` belongs to `library` (Ubuntu Server.md Step 9), so `admin-sekolah` can't write there by default. This makes `admin-sekolah` the owner and keeps `library` as the group. The setgid bit (`2`) gives new files and subfolders the `library` group, so the app (user `library`) can read them. `admin-sekolah` must be the **owner**, not just a group member: Windows' scp sets the permissions of the folder it copies into ("setstat"), which only the owner may do. Otherwise it fails with `remote setstat "/mnt/library/import/books": Permission denied`.
- **Input (server):**

    ```
    sudo mkdir -p /mnt/library/import/books /mnt/library/import/archive
    sudo chown -R admin-sekolah:library /mnt/library/import
    sudo chmod -R 2775 /mnt/library/import
    ```

- **Output (expected):** `ls -ld /mnt/library/import/books` shows `drwxrwsr-x admin-sekolah library`.

**Step 2: Prepare a batch on your PC**

- **What it does:** puts the book files and `books.csv` in one folder named `books`. Only `filename` is required. Blank cells get the defaults, and category names that don't exist yet are created automatically. Allowed types: pdf, epub, docx, pptx, xlsx. The optional `cover` column names a jpg/png in the same batch, and the import attaches it to the book.
- **Input:** a folder like:

    ```
    books\
      books.csv
      matematika-x.pdf
      matematika-x.png
      kelas-xi\biologi-xi.pdf
    ```

    ```csv
    filename,title,author,publisher,year,type,category,grade,curriculum,visibility,cover
    matematika-x.pdf,Matematika Kelas X,Kemendikbudristek,Kemendikbudristek,2022,,Matematika,X,Merdeka,,matematika-x.png
    kelas-xi/biologi-xi.pdf,Biologi Kelas XI,Kemendikbudristek,Kemendikbudristek,2022,,Biologi,XI,Merdeka,,
    ```

- **Output:** none. Save the CSV as UTF-8. Excel with an Indonesian locale saves `;` as the separator, and the importer accepts that.

**Step 3: Copy the batch to the server**

- **What it does:** sends the files over the LAN into the import folder. It copies the `books` folder itself into `/mnt/library/import/`, merging into the existing `import/books/`. Don't use a wildcard such as `books\*`: Git Bash doesn't expand it (scp then looks for a file literally named `books*`). Forward slashes work in both PowerShell and Git Bash. WinSCP works too, if you prefer drag and drop.
- **Input (Windows PC, from the folder that contains `books`):**

    ```
    scp -r books admin-sekolah@<server IP>:/mnt/library/import/
    ```

- **Output (expected):** one progress line per file. On the server, `ls /mnt/library/import/books` lists `books.csv` and the files.
- **If the copy breaks** (`scp: Connection closed`): scp can't resume, so run the same command again; it overwrites everything. Adding `-o ServerAliveInterval=15` makes scp notice a dead link quickly. A cable is more reliable than Wi-Fi for big batches. If drops repeat, check `sudo dmesg -T | tail -40` on the server for USB resets or I/O errors from the external drive.
- **Before Step 4, check the copy is complete.** A half-copied PDF still looks like a PDF, so the importer would store a broken book. The file count and total bytes must match on both sides:

    ```powershell
    Get-ChildItem books -Recurse -Filter *.pdf | Measure-Object Length -Sum    # PC: Count and Sum
    ```

    ```
    find /mnt/library/import/books -name '*.pdf' | wc -l                       # server: count
    find /mnt/library/import/books -name '*.pdf' -printf '%s\n' | awk '{s+=$1} END {print s}'   # server: bytes
    ```

- **Then let the app read the batch (after every copy).** Windows' scp creates the subfolders (`merdeka/kelas-x/...`) and may reset the permissions of `books/` itself, using modes from the Windows files that can leave the `library` group out. The app then can't see `books.csv` and Step 4 fails with `400 IMPORT_CSV_NOT_FOUND`, even though the file is there. Put everything back in the `library` group with read access (`X` adds "enter" to folders only, not to the files):

    ```
    sudo chgrp -R library /mnt/library/import
    sudo chmod -R g+rX /mnt/library/import
    sudo -u library test -f /mnt/library/import/books/books.csv && echo "app can see it" || echo "app CANNOT see it"
    ```

    Expected: `app can see it`. If not, `namei -l /mnt/library/import/books/books.csv` shows which folder blocks the way.

**Step 4: Start the import**

- **What it does:** logs in as the librarian and starts the job. Use the front-end's admin page if it has an import button. Otherwise run this on the server:
- **Input (server):**

    ```
    TOKEN=$(curl -s -X POST http://127.0.0.1/api/auth/login \
      -H 'Content-Type: application/json' \
      -d '{"username":"admin","password":"<librarian password>"}' | sed 's/.*"token":"\([^"]*\)".*/\1/')
    curl -s -X POST http://127.0.0.1/api/admin/import \
      -H "Authorization: Bearer $TOKEN" -H 'Content-Type: application/json' \
      -d '{"target":"BOOK"}'
    ```

- **Output (expected):** `202` JSON with `"status":"RUNNING"` and an `id`. On 2026-11-23 the first SIBI batch (160 books) started as job `1`.
- **If it fails with `400 IMPORT_CSV_NOT_FOUND`** while `books.csv` is there: the app can't read it. Run the permission fix at the end of Step 3.

**Step 5: Watch the progress and check the result**

- **What it does:** shows the counts and the error report for each row while the job runs. `totalRows` is filled in once the CSV is read, then `succeeded + skipped + failed` climbs towards it. One book takes a few seconds (checksum, type check, move), so 160 books take several minutes.
- **Input (server), in the same shell as Step 4 so `$TOKEN` is set:**

    ```
    watch -n 5 "curl -s http://127.0.0.1/api/admin/import/<id> -H 'Authorization: Bearer $TOKEN' | python3 -m json.tool"
    ```

    `Ctrl+C` stops watching; the import keeps running. For a single check, drop `watch -n 5 "..."` and run the `curl` alone. `GET /api/admin/import` (same header) lists the 20 most recent jobs if you lost the `id`. The token is valid for 8 hours; after that, repeat the login line of Step 4. To see what the app is doing, `journalctl -u library -f` in a second SSH window.
- **Output (expected):** `"status":"COMPLETED"`. `skipped` counts files already in the library (duplicates, trashed ones included). Every `failed` row has a line in `errors`, for example `Row 6 (sejarah.pdf): Invalid grade 'XIII' ...`. Fix those rows and run Step 4 again. Rows that already succeeded are skipped, so re-running is safe.

**Step 6: Clean up the import folder**

- **What it does:** frees the space. The importer leaves source files in place, so until you delete them every imported book takes up space twice.
- **Input (server), only after Step 5 shows `failed: 0`:**

    ```
    rm -r /mnt/library/import/books/*
    ```

- **Output (expected):** `ls /mnt/library/import/books` is empty, and `df -h /mnt/library` shows the space back.

## 3. Notes

- **Covers:** the `cover` column attaches a jpg/png during the import. A missing or non-image cover fails that row (`Cover ...` in the report), so fix the cell and re-run. Re-importing never adds a cover to a book that is already in the library, because it's skipped as a duplicate first. Set those covers with `PUT /api/books/{id}/cover` ([spec](backend-spec.md)).
- **Archive (photos and videos):** the same routine, with `/mnt/library/import/archive/`, `archive.csv` and `{"target":"ARCHIVE"}`. The columns are in [Phase 6](phase-6-bulk-import.md#folder-and-csv-convention).
- **One job at a time:** a second import while one is running returns `409 IMPORT_ALREADY_RUNNING`.
- **Backups:** books can be downloaded again, but keep the original batches (with their CSVs) on your PC as well. That copy is the cheapest way to rebuild the library if the drive dies.

## 4. Where books come from

Book sources, and the `seed/sibi-fetch.py` script that builds a ready batch of SIBI textbooks (it replaces Step 2), are in [Book Sources.md](Book%20Sources.md).
