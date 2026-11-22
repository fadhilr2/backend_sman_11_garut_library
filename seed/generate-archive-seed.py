"""Regenerates the sample archive import folder: seed/import/archive/.

Creates the images, videos and PDFs listed in ITEMS plus archive.csv, in the format
the bulk importer reads (see .md/backend-spec.md section 5.5). Output is deterministic,
so re-running produces the same files and the same checksums.

Requirements: Python 3.9+, ffmpeg on the PATH (with libx264, libvpx-vp9, libopus,
libmp3lame, libwebp and drawtext).
No pip packages.

    python seed/generate-archive-seed.py [--font PATH_TO_TTF]
"""

import argparse
import csv
import shutil
import subprocess
import sys
import tempfile
import zipfile
from pathlib import Path

OUT = Path(__file__).resolve().parent / "import" / "archive"

# filename, title, description, category, eventDate, visibility, colour (images/videos)
ITEMS = [
    ("images/upacara-hut-ri-80.jpg", "Upacara HUT RI ke-80",
     "Upacara bendera memperingati Hari Kemerdekaan Republik Indonesia di lapangan sekolah.",
     "Kegiatan Sekolah", "2025-08-17", "PUBLIC", "0xB71C1C"),
    ("images/mpls-2025.jpg", "MPLS Siswa Baru 2025",
     "Masa Pengenalan Lingkungan Sekolah untuk siswa kelas X tahun ajaran 2025/2026.",
     "Kegiatan Sekolah", "2025-07-14", "PUBLIC", "0x1565C0"),
    ("images/juara-olimpiade-sains.png", "Juara 1 Olimpiade Sains Kabupaten Garut",
     "Tim sains SMAN 11 Garut meraih juara 1 olimpiade sains tingkat kabupaten.",
     "Prestasi", "2025-10-04", "PUBLIC", "0xF9A825"),
    ("images/juara-futsal-pelajar.jpg", "Juara 2 Turnamen Futsal Pelajar",
     "Tim futsal sekolah meraih juara 2 turnamen futsal pelajar se-Priangan Timur.",
     "Prestasi", "2025-11-22", "PUBLIC", "0x2E7D32"),
    ("images/pramuka-perkemahan.webp", "Perkemahan Sabtu Minggu Pramuka",
     "Kegiatan perkemahan gugus depan Pramuka di bumi perkemahan Darajat.",
     "Kegiatan Sekolah", "2025-09-20", "PUBLIC", "0x6D4C41"),
    ("images/class-meeting-semester-ganjil.jpg", "Class Meeting Semester Ganjil",
     "Lomba antarkelas setelah penilaian akhir semester ganjil.",
     "Kegiatan Sekolah", "2025-12-15", "PUBLIC", "0x00838F"),
    ("images/kunjungan-perpustakaan-daerah.png", "Kunjungan ke Perpustakaan Daerah",
     "Siswa kelas XI berkunjung ke Dinas Arsip dan Perpustakaan Kabupaten Garut.",
     "Lainnya", "2026-02-10", "PUBLIC", "0x4527A0"),
    ("images/rapat-guru-awal-tahun.jpg", "Rapat Guru Awal Tahun Ajaran",
     "Dokumentasi rapat dewan guru menjelang tahun ajaran baru.",
     "Kegiatan Sekolah", "2025-07-07", "STAFF", "0x37474F"),
    ("images/wisuda-kelas-xii-2026.webp", "Pelepasan Siswa Kelas XII 2026",
     "Acara pelepasan dan pentas perpisahan siswa kelas XII angkatan 2026.",
     "Kegiatan Sekolah", "2026-05-23", "PUBLIC", "0xAD1457"),
    ("images/akreditasi-visitasi.jpg", "Visitasi Akreditasi Sekolah",
     "Kunjungan asesor badan akreditasi ke SMAN 11 Garut.",
     "Lainnya", "2026-03-03", "", "0x558B2F"),  # blank visibility: importer default (STAFF)
    ("videos/pentas-seni-2026.mp4", "Pentas Seni Akhir Tahun",
     "Cuplikan pentas seni dan musik siswa di aula sekolah.",
     "Kegiatan Sekolah", "2026-06-12", "PUBLIC", "0x880E4F"),
    ("videos/profil-sekolah.mp4", "Video Profil SMAN 11 Garut",
     "Video singkat profil sekolah, fasilitas dan kegiatan siswa.",
     "Lainnya", "2025-08-01", "PUBLIC", "0x0D47A1"),
    ("videos/latihan-paduan-suara.webm", "Latihan Paduan Suara",
     "Latihan paduan suara sekolah menjelang lomba (WebM: VP9 + Opus).",
     "Kegiatan Sekolah", "2026-04-18", "PUBLIC", "0x00695C"),
    ("videos/rekaman-lomba-debat.mkv", "Rekaman Lomba Debat Bahasa Inggris",
     "Rekaman mentah lomba debat. Format MKV (MPEG-4 Part 2 + MP3) yang tidak bisa diputar "
     "di browser, hanya bisa diunduh.",
     "Prestasi", "2026-01-24", "PUBLIC", "0x4E342E"),
    ("videos/rapat-komite-sekolah.mp4", "Rekaman Rapat Komite Sekolah",
     "Rekaman rapat komite sekolah bersama orang tua siswa. Hanya untuk staf.",
     "Dokumen Resmi", "2026-02-14", "STAFF", "0x263238"),
    ("documents/kalender-akademik-2025-2026.pdf", "Kalender Akademik 2025/2026",
     "Jadwal kegiatan belajar, ujian dan libur tahun ajaran 2025/2026.",
     "Dokumen Resmi", "2025-07-01", "PUBLIC", None),
    ("documents/sk-pembagian-tugas-mengajar-2026.pdf", "SK Pembagian Tugas Mengajar 2026",
     "Surat keputusan kepala sekolah tentang pembagian tugas mengajar guru.",
     "Dokumen Resmi", "2026-01-05", "STAFF", None),
    ("documents/piagam-juara-olimpiade-sains.pdf", "Piagam Juara Olimpiade Sains",
     "Salinan piagam penghargaan juara 1 olimpiade sains kabupaten.",
     "Prestasi", "2025-10-04", "PUBLIC", None),
    ("documents/tata-tertib-siswa.docx", "Tata Tertib Siswa SMAN 11 Garut",
     "Peraturan dan tata tertib siswa yang berlaku di lingkungan sekolah.",
     "Dokumen Resmi", "", "PUBLIC", None),  # no event date
    ("images/logo-sekolah.png", "Logo SMAN 11 Garut",
     "Logo resmi sekolah untuk keperluan dokumen dan publikasi.",
     "Lainnya", "", "PUBLIC", "0x1B5E20"),  # no event date
]

# Video encoding per container. The .mkv deliberately uses codecs no browser decodes.
VIDEO_CODECS = {
    ".mp4": ["-c:v", "libx264", "-preset", "medium", "-crf", "30", "-pix_fmt", "yuv420p",
             "-c:a", "aac", "-b:a", "64k", "-movflags", "+faststart"],
    ".webm": ["-c:v", "libvpx-vp9", "-crf", "40", "-b:v", "0", "-row-mt", "1", "-pix_fmt", "yuv420p",
              "-c:a", "libopus", "-b:a", "48k"],
    ".mkv": ["-c:v", "mpeg4", "-q:v", "8", "-c:a", "libmp3lame", "-b:a", "64k"],
}

CSV_HEADER = ["filename", "title", "description", "category", "eventDate", "visibility"]


def run_ffmpeg(args, cwd):
    cmd = ["ffmpeg", "-hide_banner", "-loglevel", "error", "-y", *args]
    result = subprocess.run(cmd, cwd=cwd, capture_output=True, text=True)
    if result.returncode != 0:
        sys.exit(f"ffmpeg failed: {' '.join(cmd)}\n{result.stderr}")


def text_filters(title, subtitle, title_size, subtitle_size):
    # Text comes from files in the working dir, so titles need no filter escaping.
    common = "fontfile=font.ttf:expansion=none:fontcolor=white:x=(w-text_w)/2"
    return (
        f"drawtext={common}:textfile=title.txt:fontsize={title_size}:y=(h/2)-{title_size},"
        f"drawtext={common}:textfile=subtitle.txt:fontsize={subtitle_size}:y=(h/2)+{subtitle_size // 2}"
    )


def make_image(target, title, subtitle, colour, work):
    (work / "title.txt").write_text(title, encoding="utf-8")
    (work / "subtitle.txt").write_text(subtitle, encoding="utf-8")
    vf = "drawbox=x=0:y=ih-60:w=iw:h=60:color=black@0.35:t=fill," + text_filters(title, subtitle, 52, 30)
    out = work / ("out" + target.suffix)
    codec = {".jpg": ["-q:v", "3"], ".png": [], ".webp": ["-c:v", "libwebp", "-quality", "80"]}[target.suffix]
    run_ffmpeg(["-f", "lavfi", "-i", f"color=c={colour}:s=1280x720", "-frames:v", "1",
                "-vf", vf, *codec, out.name], cwd=work)
    shutil.move(out, target)


def make_video(target, title, subtitle, colour, work):
    (work / "title.txt").write_text(title, encoding="utf-8")
    (work / "subtitle.txt").write_text(subtitle, encoding="utf-8")
    vf = (f"drawbox=x=0:y=ih/2-60:w=iw:h=120:color={colour}@0.85:t=fill,"
          + text_filters(title, subtitle, 30, 20))
    out = work / ("out" + target.suffix)
    run_ffmpeg(["-f", "lavfi", "-i", "testsrc2=s=640x360:r=25:d=6",
                "-f", "lavfi", "-i", "sine=frequency=440:duration=6",
                "-vf", vf, *VIDEO_CODECS[target.suffix], "-shortest", "-fflags", "+bitexact",
                out.name], cwd=work)
    shutil.move(out, target)


def pdf_escape(text):
    return text.replace("\\", "\\\\").replace("(", "\\(").replace(")", "\\)")


def make_pdf(target, title, lines):
    """Minimal one-page PDF 1.4 with Helvetica text and a correct xref table."""
    stream_lines = ["BT", "/F1 20 Tf", "72 760 Td", f"({pdf_escape(title)}) Tj", "/F1 12 Tf"]
    for line in lines:
        stream_lines += ["0 -24 Td", f"({pdf_escape(line)}) Tj"]
    stream_lines.append("ET")
    stream = "\n".join(stream_lines).encode("latin-1")

    objects = [
        b"<< /Type /Catalog /Pages 2 0 R >>",
        b"<< /Type /Pages /Kids [3 0 R] /Count 1 >>",
        b"<< /Type /Page /Parent 2 0 R /MediaBox [0 0 595 842] "
        b"/Resources << /Font << /F1 4 0 R >> >> /Contents 5 0 R >>",
        b"<< /Type /Font /Subtype /Type1 /BaseFont /Helvetica /Encoding /WinAnsiEncoding >>",
        b"<< /Length " + str(len(stream)).encode() + b" >>\nstream\n" + stream + b"\nendstream",
        b"<< /Title (" + pdf_escape(title).encode("latin-1") + b") /Producer (generate-archive-seed.py) >>",
    ]
    out = bytearray(b"%PDF-1.4\n%\xe2\xe3\xcf\xd3\n")
    offsets = []
    for number, body in enumerate(objects, start=1):
        offsets.append(len(out))
        out += f"{number} 0 obj\n".encode() + body + b"\nendobj\n"
    xref = len(out)
    out += f"xref\n0 {len(objects) + 1}\n0000000000 65535 f \n".encode()
    for offset in offsets:
        out += f"{offset:010d} 00000 n \n".encode()
    out += (f"trailer\n<< /Size {len(objects) + 1} /Root 1 0 R /Info {len(objects)} 0 R >>\n"
            f"startxref\n{xref}\n%%EOF\n").encode()
    target.write_bytes(bytes(out))


def xml_escape(text):
    return text.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")


def make_docx(target, title, lines):
    """Minimal Word document: content types, package rels and one document part."""
    paragraphs = [f'<w:p><w:pPr><w:pStyle w:val="Title"/></w:pPr><w:r><w:rPr><w:b/><w:sz w:val="40"/></w:rPr>'
                  f'<w:t>{xml_escape(title)}</w:t></w:r></w:p>']
    paragraphs += [f'<w:p><w:r><w:t xml:space="preserve">{xml_escape(line)}</w:t></w:r></w:p>' for line in lines]
    parts = {
        "[Content_Types].xml":
            '<?xml version="1.0" encoding="UTF-8" standalone="yes"?>'
            '<Types xmlns="http://schemas.openxmlformats.org/package/2006/content-types">'
            '<Default Extension="rels" ContentType="application/vnd.openxmlformats-package.relationships+xml"/>'
            '<Default Extension="xml" ContentType="application/xml"/>'
            '<Override PartName="/word/document.xml" ContentType="application/'
            'vnd.openxmlformats-officedocument.wordprocessingml.document.main+xml"/>'
            '</Types>',
        "_rels/.rels":
            '<?xml version="1.0" encoding="UTF-8" standalone="yes"?>'
            '<Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships">'
            '<Relationship Id="rId1" Type="http://schemas.openxmlformats.org/officeDocument/2006/'
            'relationships/officeDocument" Target="word/document.xml"/>'
            '</Relationships>',
        "word/document.xml":
            '<?xml version="1.0" encoding="UTF-8" standalone="yes"?>'
            '<w:document xmlns:w="http://schemas.openxmlformats.org/wordprocessingml/2006/main">'
            '<w:body>' + "".join(paragraphs) + '</w:body></w:document>',
    }
    with zipfile.ZipFile(target, "w", zipfile.ZIP_DEFLATED) as docx:
        for name, xml in parts.items():
            info = zipfile.ZipInfo(name, date_time=(2026, 1, 1, 0, 0, 0))  # fixed date: same bytes every run
            info.compress_type = zipfile.ZIP_DEFLATED
            docx.writestr(info, xml.encode("utf-8"))


def main():
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("--font", default="C:/Windows/Fonts/arial.ttf", help="TrueType font for image/video text")
    args = parser.parse_args()

    font = Path(args.font)
    if not font.is_file():
        sys.exit(f"Font not found: {font}. Pass --font /path/to/font.ttf")
    if shutil.which("ffmpeg") is None:
        sys.exit("ffmpeg not found on the PATH")

    for sub in ("images", "videos", "documents"):
        (OUT / sub).mkdir(parents=True, exist_ok=True)

    with tempfile.TemporaryDirectory() as tmp:
        work = Path(tmp)
        shutil.copy(font, work / "font.ttf")
        for filename, title, description, category, event_date, _visibility, colour in ITEMS:
            target = OUT / filename
            subtitle = " - ".join(part for part in (category, event_date, "SMAN 11 Garut") if part)
            lines = [subtitle, "", description, "", "Dokumen contoh (seed data) untuk pengembangan."]
            if filename.startswith("images/"):
                make_image(target, title, subtitle, colour, work)
            elif filename.startswith("videos/"):
                make_video(target, title, subtitle, colour, work)
            elif target.suffix == ".docx":
                make_docx(target, title, lines)
            else:
                make_pdf(target, title, lines)
            print(f"  {filename} ({target.stat().st_size // 1024} KB)")

    with open(OUT / "archive.csv", "w", encoding="utf-8", newline="") as f:
        writer = csv.writer(f)
        writer.writerow(CSV_HEADER)
        for filename, title, description, category, event_date, visibility, _colour in ITEMS:
            writer.writerow([filename, title, description, category, event_date, visibility])
    print(f"Wrote {len(ITEMS)} items and archive.csv to {OUT}")


if __name__ == "__main__":
    main()
