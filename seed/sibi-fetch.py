"""Downloads SMA textbooks from SIBI (buku.kemendikdasmen.go.id) into a bulk-import batch.

Reads the public catalogue API the SIBI website itself uses, keeps the SMA books
(Kelas X-XII) for the chosen curriculum, downloads the PDFs and writes books.csv in the
format the bulk importer reads (see .md/backend-spec.md section 5.5). The output folder
is a ready batch: copy its contents to /mnt/library/import/books/ on the server
(.md/Content Loading.md).

- Student books are PUBLIC / EBOOK. Teacher guides (Panduan Guru) are STAFF /
  TEACHING_MODULE, or skipped with --no-teacher.
- SMK-only vocational books are skipped, and so is any subject not in CATEGORIES
  (vocational or blank subject codes) unless --all-subjects is given.
- When a book has an "(Edisi Revisi)" version, only the revision is kept.
- Each book's SIBI cover (PNG) is saved next to its PDF and named in the 'cover' column,
  so the import attaches it. --no-covers skips them; a failed cover leaves the cell blank.
- Re-running resumes: files already downloaded with the right size are skipped.

Politeness: one request at a time, at least --delay seconds between any two requests
(catalogue, size checks and downloads alike), an honest User-Agent, an optional
--max-rate bandwidth cap, and retries only for timeouts, connection errors and 5xx/429
responses (honouring Retry-After). A 404 or other 4xx is reported once, not retried.

Requirements: Python 3.9+. No pip packages.

    python seed/sibi-fetch.py --dry-run                  # list what would be fetched
    python seed/sibi-fetch.py                            # Merdeka, X-XII, student + teacher
    python seed/sibi-fetch.py --curriculum all --grades X --no-teacher --out D:/sibi
    python seed/sibi-fetch.py --max-rate 2 --delay 2        # gentle on the school's connection
"""

import argparse
import csv
import email.utils
import http.client
import json
import re
import sys
import time
import urllib.error
import urllib.request
from pathlib import Path

API = "https://api.buku.cloudapp.web.id/api/catalogue"
LISTS = {
    "merdeka": f"{API}/getPenggerakTextBooks?limit=2000&type_pdf&order_by=updated_at",
    "k13": f"{API}/getTextBooks?limit=2000&type_pdf&order_by=updated_at",
}
CURRICULUM_LABEL = {"merdeka": "Merdeka", "k13": "K13"}
GRADES = {"10": "X", "11": "XI", "12": "XII"}
USER_AGENT = "SMAN11Garut-LibraryImporter/1.0 (offline school library; Python-urllib)"
MAX_ATTEMPTS = 4
BACKOFF = [5, 15, 45]          # seconds before attempts 2, 3, 4
MAX_RETRY_AFTER = 300          # never wait longer than this for a Retry-After
CSV_HEADER = ["filename", "title", "author", "publisher", "year", "type",
              "category", "grade", "curriculum", "visibility", "cover"]
COVER_TYPES = {"png", "jpg", "jpeg"}  # what the library accepts as a cover

# SIBI subject code (normalised) -> library category name. Unknown codes fall back to
# the code in title case.
CATEGORIES = {
    "agama_islam": "Pendidikan Agama Islam",
    "agama_kristen": "Pendidikan Agama Kristen",
    "agama_katolik": "Pendidikan Agama Katolik",
    "agama_hindu": "Pendidikan Agama Hindu",
    "agama_buddha": "Pendidikan Agama Buddha",
    "agama_khonghucu": "Pendidikan Agama Khonghucu",
    "kepercayaan": "Pendidikan Kepercayaan",
    "pendidikan_pancasila": "Pendidikan Pancasila",
    "bahasa_indonesia": "Bahasa Indonesia",
    "bahasa_inggris": "Bahasa Inggris",
    "bahasa_jepang": "Bahasa Jepang",
    "bahasa_korea": "Bahasa Korea",
    "bahasa_mandarin": "Bahasa Mandarin",
    "matematika": "Matematika",
    "ipa": "IPA",
    "ips": "IPS",
    "projek_ipas": "IPAS",
    "fisika": "Fisika",
    "kimia": "Kimia",
    "biologi": "Biologi",
    "ekonomi": "Ekonomi",
    "geografi": "Geografi",
    "sosiologi": "Sosiologi",
    "antropologi": "Antropologi",
    "sejarah": "Sejarah",
    "informatika": "Informatika",
    "koding_dan_kecerdasan_artifisial": "Koding dan Kecerdasan Artifisial",
    "pjok": "PJOK",
    "seni_budaya": "Seni Budaya",
    "seni_musik": "Seni Musik",
    "seni_rupa": "Seni Rupa",
    "seni_tari": "Seni Tari",
    "seni_teater": "Seni Teater",
    "prakarya": "Prakarya dan Kewirausahaan",
}


class PermanentError(Exception):
    """A response that retrying won't fix (404 and other 4xx except 429)."""


class Client:
    """Sequential HTTP client: spaces requests by `delay` seconds, caps download speed at
    `max_rate` bytes/s (0 = no cap) and retries only transient failures."""

    def __init__(self, delay, max_rate):
        self.delay = delay
        self.max_rate = max_rate
        self._last = float("-inf")

    def _pace(self):
        wait = self._last + self.delay - time.monotonic()
        if wait > 0:
            time.sleep(wait)

    def _with_retries(self, url, method, handle):
        """Opens url and passes the response to handle(); retries transient failures."""
        for attempt in range(1, MAX_ATTEMPTS + 1):
            self._pace()
            try:
                req = urllib.request.Request(url, method=method, headers={"User-Agent": USER_AGENT})
                with urllib.request.urlopen(req, timeout=120) as resp:
                    return handle(resp)
            except urllib.error.HTTPError as e:
                if e.code != 429 and e.code < 500:
                    raise PermanentError(f"HTTP {e.code}") from None
                error, wait = f"HTTP {e.code}", retry_after(e.headers)
            except (urllib.error.URLError, http.client.HTTPException, OSError) as e:
                error, wait = str(e), None
            finally:
                self._last = time.monotonic()
            if attempt == MAX_ATTEMPTS:
                raise IOError(f"{error} (gave up after {MAX_ATTEMPTS} attempts)")
            wait = wait if wait is not None else BACKOFF[attempt - 1]
            print(f"    {error}; retrying in {wait:.0f} s", file=sys.stderr)
            time.sleep(wait)

    def json(self, url):
        return self._with_retries(url, "GET", json.load)

    def size(self, url):
        return self._with_retries(url, "HEAD", lambda r: int(r.headers.get("Content-Length") or -1))

    def save(self, url, part):
        """Streams url into the file `part`, throttled to max_rate. Returns bytes written."""
        def stream(resp):
            start, done = time.monotonic(), 0
            with open(part, "wb") as out:
                while chunk := resp.read(256 * 1024):
                    out.write(chunk)
                    done += len(chunk)
                    if self.max_rate:
                        ahead = start + done / self.max_rate - time.monotonic()
                        if ahead > 0:
                            time.sleep(ahead)
            return done
        return self._with_retries(url, "GET", stream)


def retry_after(headers):
    """Seconds from a Retry-After header (delta or HTTP date), capped; None if absent."""
    value = headers.get("Retry-After") if headers else None
    if not value:
        return None
    try:
        seconds = float(value)
    except ValueError:
        try:
            seconds = email.utils.parsedate_to_datetime(value).timestamp() - time.time()
        except (TypeError, ValueError):
            return None
    return min(max(seconds, 1), MAX_RETRY_AFTER)


def subject_code(raw):
    code = re.sub(r"[\s-]+", "_", (raw or "").strip().lower())
    return "prakarya" if code.startswith("prakarya") else code


def category_for(raw):
    code = subject_code(raw)
    if not code:
        return ""
    return CATEGORIES.get(code, code.replace("_", " ").title())


def clean(text):
    """Collapses non-breaking spaces, tabs and runs of spaces into single spaces."""
    return re.sub(r"\s+", " ", text or "").strip()  # \s also matches U+00A0


def is_teacher_book(book):
    return book.get("book_type") == "buku_guru" or "panduan guru" in book["title"].lower()


def is_smk_only(book):
    return "SMK" in book["title"] and "SMA" not in book["title"]


def grade_for(book):
    """The grade named in the title wins over the catalogue's class field, which is
    sometimes wrong (e.g. a "Kelas XI" book filed under class 10). Two grades -> ALL."""
    named = set(re.findall(r"\b(XII|XI|X)\b", book["title"].split("Kelas", 1)[-1])) \
        if "Kelas" in book["title"] else set()
    if len(named) > 1:
        return "ALL"
    return named.pop() if named else GRADES.get(str(book.get("class")))


def valid_year(year):
    """SIBI uses '0000-00-00' for an unknown date; the importer rejects year 0 (and any
    year outside 1000-9999). Keep only plausible years, else leave the column blank."""
    return bool(re.fullmatch(r"\d{4}", year)) and 1900 <= int(year) <= time.localtime().tm_year + 1


def base_title(title):
    return re.sub(r"\s*\(edisi revisi\)\s*$", "", title, flags=re.I).strip()


def select(client, curricula, grades, teacher, all_subjects):
    """Returns the chosen books, deduplicated, as dicts ready for books.csv."""
    raw = []
    for cur in curricula:
        for book in client.json(LISTS[cur])["results"]:
            book["_curriculum"] = cur
            book["title"] = clean(book["title"])
            raw.append(book)

    for b in raw:
        b["_grade"] = grade_for(b)
    picked = [b for b in raw
              if (b["_grade"] in grades
                  or (b["_grade"] == "ALL" and GRADES.get(str(b.get("class"))) in grades))
              and b.get("type") == "pdf" and b.get("attachment")
              and not is_smk_only(b)
              and (all_subjects or subject_code(b.get("subject")) in CATEGORIES)
              and (teacher or not is_teacher_book(b))]

    # Same file listed under several grades -> one row, grade ALL.
    by_url = {}
    for b in picked:
        grade = b["_grade"]
        if b["attachment"] in by_url:
            if by_url[b["attachment"]]["_grade"] != grade:
                by_url[b["attachment"]]["_grade"] = "ALL"
            continue
        b["_grade"] = grade
        by_url[b["attachment"]] = b

    # Keep only the revision when both "X" and "X (Edisi Revisi)" exist.
    revised = {(b["_curriculum"], base_title(b["title"]).lower())
               for b in by_url.values() if base_title(b["title"]) != b["title"]}
    books = [b for b in by_url.values()
             if base_title(b["title"]) != b["title"]
             or (b["_curriculum"], b["title"].lower()) not in revised]

    rows = []
    for b in sorted(books, key=lambda b: (b["_curriculum"], b["_grade"], category_for(b["subject"]), b["title"])):
        teacher_book = is_teacher_book(b)
        folder = f"{b['_curriculum']}/kelas-{b['_grade'].lower()}"
        year = (b.get("published_year") or "")[:4]
        pdf_name = b["attachment"].rsplit("/", 1)[-1]
        image = b.get("image") or ""
        image_file = image.rsplit("/", 1)[-1]
        image_ext = image_file.rsplit(".", 1)[-1].lower() if "." in image_file else ""
        has_cover = image_ext in COVER_TYPES
        rows.append({
            "url": b["attachment"],
            "filename": f"{folder}/{pdf_name}",
            "cover_url": image if has_cover else "",
            "cover": f"{folder}/{pdf_name.rsplit('.', 1)[0]}.{image_ext}" if has_cover else "",
            "title": b["title"][:255],
            "author": clean(b.get("writer"))[:255],
            "publisher": clean(b.get("publisher"))[:255],
            "year": year if valid_year(year) else "",
            "type": "TEACHING_MODULE" if teacher_book else "EBOOK",
            "category": category_for(b.get("subject")),
            "grade": b["_grade"],
            "curriculum": CURRICULUM_LABEL[b["_curriculum"]],
            "visibility": "STAFF" if teacher_book else "PUBLIC",
        })
    return rows


def download(client, url, dest):
    """Downloads url to dest. Returns 'skipped', 'ok' or 'failed: <reason>'."""
    part = dest.with_name(dest.name + ".part")
    try:
        size = client.size(url)
        if dest.exists() and dest.stat().st_size == size:
            return "skipped"
        dest.parent.mkdir(parents=True, exist_ok=True)
        got = client.save(url, part)
        if size > 0 and got != size:
            raise IOError(f"got {got} bytes, expected {size}")
        part.replace(dest)
        return "ok"
    except (PermanentError, IOError) as e:
        part.unlink(missing_ok=True)
        return f"failed: {e}"


def main():
    p = argparse.ArgumentParser(description=__doc__.split("\n\n")[0])
    p.add_argument("--curriculum", choices=["merdeka", "k13", "all"], default="merdeka")
    p.add_argument("--grades", nargs="+", choices=["X", "XI", "XII"], default=["X", "XI", "XII"])
    p.add_argument("--no-teacher", action="store_true", help="skip teacher guides (Panduan Guru)")
    p.add_argument("--all-subjects", action="store_true",
                   help="also keep subjects outside the SMA list in CATEGORIES (vocational, blank)")
    p.add_argument("--out", default=str(Path(__file__).resolve().parent.parent / "data" / "sibi" / "books"),
                   help="output folder (default: data/sibi/books, gitignored)")
    p.add_argument("--dry-run", action="store_true", help="list the books and total size, download nothing")
    p.add_argument("--no-covers", action="store_true", help="don't download covers (blank 'cover' column)")
    p.add_argument("--delay", type=float, default=1.0,
                   help="minimum seconds between any two requests (default 1)")
    p.add_argument("--max-rate", type=float, default=0,
                   help="download speed cap in MB/s, to leave bandwidth for others (default: no cap)")
    args = p.parse_args()

    client = Client(max(args.delay, 0), max(args.max_rate, 0) * 1e6)
    curricula = ["merdeka", "k13"] if args.curriculum == "all" else [args.curriculum]
    rows = select(client, curricula, set(args.grades), not args.no_teacher, args.all_subjects)
    out = Path(args.out)
    print(f"{len(rows)} books selected "
          f"({sum(r['visibility'] == 'PUBLIC' for r in rows)} student, "
          f"{sum(r['visibility'] == 'STAFF' for r in rows)} teacher)")

    if args.dry_run:
        total = 0
        for r in rows:
            try:
                size = client.size(r["url"])
                total += max(size, 0)
                shown = f"{size / 1e6:8.1f} MB"
            except (PermanentError, IOError) as e:
                shown = f"{str(e)[:11]:>11}"
            print(f"{shown}  {r['grade']:<3} {r['category'][:28]:<28} {r['title'][:80]}")
        print(f"Total: {total / 1e9:.2f} GB")
        return

    out.mkdir(parents=True, exist_ok=True)
    failed = []
    missing_covers = 0
    for i, r in enumerate(rows, 1):
        result = download(client, r["url"], out / r["filename"])
        print(f"[{i}/{len(rows)}] {result:<8} {r['filename']}")
        if result.startswith("failed"):
            failed.append(r)
            continue
        if args.no_covers or not r["cover_url"]:
            r["cover"] = ""
            continue
        # A missing cover never costs the book: the 'cover' cell is just left blank.
        cover_result = download(client, r["cover_url"], out / r["cover"])
        if cover_result.startswith("failed"):
            print(f"    cover {cover_result}; leaving the 'cover' cell blank")
            r["cover"] = ""
            missing_covers += 1

    ok_rows = [r for r in rows if r not in failed]
    with open(out / "books.csv", "w", newline="", encoding="utf-8") as f:
        w = csv.DictWriter(f, fieldnames=CSV_HEADER, extrasaction="ignore")
        w.writeheader()
        w.writerows(ok_rows)
    print(f"\nWrote {out / 'books.csv'} with {len(ok_rows)} rows "
          f"({sum(bool(r['cover']) for r in ok_rows)} with a cover).")
    if missing_covers:
        print(f"{missing_covers} covers could not be downloaded; run again to retry them.")
    if failed:
        print(f"{len(failed)} downloads failed (left out of the CSV). Run again to retry:")
        for r in failed:
            print(f"  {r['url']}")
        sys.exit(1)


if __name__ == "__main__":
    main()
