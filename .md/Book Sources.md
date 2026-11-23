# Book Sources: Input and Output

This explains where the library's books come from and how to turn them into an import batch. Getting a batch onto the server and importing it is in [Content Loading.md](Content%20Loading.md). The CSV rules are owned by the [spec](backend-spec.md#55-bulk-import).

Only legal, free material goes in. A school is an easy target for a copyright complaint, so no pirated PDFs of commercial textbooks (Erlangga, Yrama Widya and others) or popular novels.

## 1. Sources, in order

| # | Source | What you get | How it gets in |
|---|---|---|---|
| 1 | **SIBI**, `buku.kemendikdasmen.go.id` (formerly `buku.kemdikbud.go.id`) | Official Kurikulum Merdeka and K13 textbooks and teacher guides, free to copy | `seed/sibi-fetch.py` (§2) |
| 2 | **The school's own teachers** | Modul ajar, worksheets, past exams | Teachers upload from the front-end, or a librarian batch |
| 3 | **Badan Bahasa digital books** (`budi.kemdikbud.go.id`) and **GLN** reading materials | Indonesian literacy reading: stories and nonfiction | Manual batch (Content Loading §2) |
| 4 | **Project Gutenberg / Standard Ebooks** | Public-domain English classics (EPUB) | Manual batch |
| 5 | **OpenStax** | University-level physics, chemistry, biology and math (English, CC BY) | Manual batch |
| 6 | **Wikisource Bahasa Indonesia** | Public-domain Indonesian literature | Manual batch; check each title really is public domain |

Not usable: **iPusnas**, whose books are locked inside its app and can't be downloaded.

## 2. SIBI textbooks with `seed/sibi-fetch.py`

The script reads the catalogue API the SIBI website itself calls (`api.buku.cloudapp.web.id/api/catalogue/...`; PDFs on `static-sc.cloudapp.web.id`). It keeps the SMA books, downloads the PDFs and writes `books.csv`. It needs Python 3.9+ and no pip packages.

**Step 1: Preview**

- **What it does:** lists the books the options select, with sizes. Nothing is downloaded.
- **Input (Windows PC, project folder):** `python seed/sibi-fetch.py --dry-run`
- **Output:** one line per book, then the total. On 2026-11-23 the defaults (Merdeka, Kelas X–XII, student books and teacher guides) selected 160 books (69 student, 91 teacher), 2.32 GB.

**Step 2: Download**

- **What it does:** downloads the PDFs into grade subfolders and writes `books.csv`. Running it again resumes: finished files are skipped, and failed downloads are left out of the CSV and listed so the next run retries them.
- **Input:** `python seed/sibi-fetch.py --max-rate 2` (on the school's connection; see the options below).
- **Output:** `data/sibi/books/` (gitignored) with `merdeka/kelas-x/...pdf` subfolders and `books.csv`, then `Wrote ...books.csv with N rows.` On 2026-11-23 the Kelas X student books (`--grades X --no-teacher`) gave 16 PDFs, 180 MB, and every CSV row passed the importer's rules.

**Step 3: Hand over to Content Loading**

- **What it does:** the output folder is a finished batch. It replaces Content Loading Step 2.
- **Input (Windows PC, project folder):** `scp -r data/sibi/books admin-sekolah@<server IP>:/mnt/library/import/`, then the completeness check and permission fix at the end of Content Loading Step 3, then Steps 4–6. This copies the folder itself, merging into `import/books/`. Don't use a `books\*` wildcard: Git Bash doesn't expand it.
- **Output (expected):** an import job with `failed: 0`. The subjects appear as categories.

### 2.1 Options

| Option | Effect |
|---|---|
| `--curriculum merdeka\|k13\|all` | Which catalogue. Default `merdeka`. K13 adds 84 older SMA books. |
| `--grades X XI XII` | Grades to keep. Default all three. |
| `--no-teacher` | Skip teacher guides. |
| `--all-subjects` | Also keep subjects outside the SMA list (`CATEGORIES` in the script), such as vocational ones. |
| `--out DIR` | Output folder. Default `data/sibi/books`. |
| `--delay S` | Minimum seconds between any two requests. Default `1`. |
| `--max-rate MB` | Download speed cap in MB/s, so the school's connection stays usable. Default: no cap. |
| `--dry-run` | Preview only (Step 1). |
| `--no-covers` | Don't download covers; the `cover` column stays blank. |

### 2.2 What the script decides

| Field | Rule |
|---|---|
| Selection | SMA Kelas X–XII PDFs. SMK-only books and subjects outside `CATEGORIES` are skipped. When "(Edisi Revisi)" exists, the older edition is skipped. |
| `type` / `visibility` | Student books: `EBOOK` / `PUBLIC`. Teacher guides (`Panduan Guru`): `TEACHING_MODULE` / `STAFF`. |
| `category` | The SIBI subject, mapped to a readable name (`agama_islam` → `Pendidikan Agama Islam`). |
| `grade` | From "Kelas X/XI/XII" in the title, because the catalogue's class field is sometimes wrong (Bahasa Jepang "Kelas XI" is filed under class 10). A book for two grades gets `ALL`. |
| `curriculum` | `Merdeka` or `K13`. |
| `author`, `publisher`, `year` | SIBI writer list and publisher, cut to 255 characters. Year from `published_year`. It's left blank if missing or not plausible (before 1900 or after next year). SIBI writes `0000-00-00` for an unknown date, and the importer rejects year `0000`. On 2026-11-23, 103 of the 160 books had no usable year. |
| `filename` | `<curriculum>/kelas-<grade>/<original PDF name>`. |
| `cover` | The SIBI cover (PNG), saved next to the PDF as `<PDF name>.png`, so the import attaches it. Blank with `--no-covers`, or if that cover's download failed (the book is still included). On 2026-11-23 all 160 books got a cover. |

### 2.3 Politeness

- One request at a time, with `--delay` between every request: the catalogue, each size check and each download.
- User-Agent `SMAN11Garut-LibraryImporter/1.0`, with no contact details.
- Retries only timeouts, connection errors and 5xx/429 responses: up to 4 attempts with 5/15/45 s backoff. A 429 waits for its `Retry-After`, capped at 5 minutes. A 404 or other 4xx fails once.
- On 2026-11-23 the SIBI `robots.txt` files allowed everything, and the PDFs are served through Cloudflare's cache.

### 2.4 Known gaps and upkeep

- **SIBI gaps (2026-11-23):** no SMA student books for Seni or Prakarya (teacher guides only), and no Sejarah Kelas X. Teachers' own material has to cover these.
- **New editions:** run Steps 1–3 again later. Books already in the library are skipped as duplicates, so only new or revised PDFs are added.
- **If the API changes:** the SIBI site is a single-page app. Download its JavaScript bundle (`/static/js/main.*.js`, named in the homepage HTML) and search it for `api/catalogue` to find the new endpoints.
