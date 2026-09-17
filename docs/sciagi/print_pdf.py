"""Prints HTML files to PDF with a headless Chrome or Edge, with bookmarks from the headings.

    python docs/sciagi/print_pdf.py docs/sciagi/sciaga-rozwiazania.html docs/sciagi/program-warsztatu.html

Each PDF lands next to its HTML file.
"""
import pathlib
import shutil
import subprocess
import sys
import tempfile

CANDIDATES = [
    r"C:\Program Files\Google\Chrome\Application\chrome.exe",
    r"C:\Program Files (x86)\Microsoft\Edge\Application\msedge.exe",
    "/Applications/Google Chrome.app/Contents/MacOS/Google Chrome",
    "/Applications/Microsoft Edge.app/Contents/MacOS/Microsoft Edge",
    shutil.which("google-chrome") or "",
    shutil.which("chromium") or "",
]
BROWSER = next((path for path in CANDIDATES if path and pathlib.Path(path).exists()), None)
if BROWSER is None:
    sys.exit("No Chrome or Edge found to print with")

for name in sys.argv[1:]:
    page = pathlib.Path(name).resolve()
    pdf = page.with_suffix(".pdf")
    pdf.unlink(missing_ok=True)
    for attempt in range(3):
        with tempfile.TemporaryDirectory() as profile:
            subprocess.run([BROWSER, "--headless=new", "--disable-gpu", "--no-pdf-header-footer",
                            "--user-data-dir=" + profile, "--generate-pdf-document-outline",
                            "--print-to-pdf=" + str(pdf), page.as_uri()],
                           capture_output=True, timeout=120)
        if pdf.exists():
            print("%s: %d bytes" % (pdf.name, pdf.stat().st_size))
            break
    else:
        sys.exit("Could not print " + str(page))
