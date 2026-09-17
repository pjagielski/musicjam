"""Cheat sheet for a tablet: step-N -> step-N-final diffs of src/main, one step per page.

Run from inside the repository: python docs/sciagi/build_sciaga.py docs/sciagi/sciaga-rozwiazania.html
It reads the local step branches, or origin's where a branch was never checked out.
"""
import html
import re
import subprocess
import sys

REPO = subprocess.run(["git", "rev-parse", "--show-toplevel"], capture_output=True, check=True,
                      text=True).stdout.strip()
OUT = sys.argv[1]

STEPS = [
    (2, "Czytamy plik MIDI", "step2.InspectMidi", "MidiFileReaderTest (9, zielone od startu 4)"),
    (3, "Własny scheduler i Gervill", "step3.ListMidiDevices, step3.PlayNotes",
     "MidiNoteOutputTest, MidiPlayerTest, PooledSchedulerTest"),
    (4, "Zewnętrzny syntezator", "step4.PlayOnSynth (awaryjnie step4.MidiPanic)",
     "ExternalMidiOutputTest (9, zielone od startu 3)"),
    (5, "Perkusja, dwa zegary i jeden zegar", "step5.PlayBeat, step5.PlayJam",
     "TransportTest, PatternCompilerTest, AudioEngineScheduleTest, AudioEngineTest, MidiPlayerLoopTest"),
]


def git(*args):
    return subprocess.run(["git", *args], cwd=REPO, capture_output=True, check=True).stdout.decode("utf-8")


def branch(name):
    """A step branch as it is here, or as origin has it when it was never checked out."""
    local = subprocess.run(["git", "rev-parse", "--verify", "--quiet", name], cwd=REPO, capture_output=True)
    return name if local.returncode == 0 else "origin/" + name


HUNK = re.compile(r"^@@ -\d+(?:,\d+)? \+(\d+)(?:,\d+)? @@")
SIGNATURE = re.compile(r"^\s*(?:(?:public|private|protected|static|final|synchronized)\s+)+[\w<>\[\], ?.]+\s+\w+\s*\(")
# what a scaffold leaves where the code goes: hints and a throw, nothing a solution needs to see
HINT = re.compile(r"^\s*(//.*|throw new UnsupportedOperationException\(.*\);|/\*\*|\*.*|\*/)?\s*$")


def parse(text):
    """{file: [(kind, text, new line number)]}; a removed line carries the number of the line after it."""
    files = {}
    rows = None
    number = 0
    for line in text.splitlines():
        if line.startswith("diff --git"):
            rows = files.setdefault(line.split(" b/", 1)[1], [])
        elif line.startswith(("index ", "--- ", "+++ ", "\\")):
            continue
        elif (m := HUNK.match(line)):
            if rows:
                rows.append(("gap", "", 0))
            number = int(m.group(1))
        elif rows is not None:
            kind = line[:1] or " "
            rows.append((kind, line[1:], number))
            if kind != "-":
                number += 1
    return files


def lines_word(n):
    return "linia" if n == 1 else "linie" if n < 5 else "linii"


def clusters(rows):
    """Changed lines grouped by where they land in the final file: apart by more than two lines, apart."""
    groups = []
    end = None
    for kind, text, number in rows:
        if groups and number - end <= 2:
            groups[-1].append((kind, text, number))
        else:
            groups.append([(kind, text, number)])
        end = number + 1 if kind == "+" else number
    return groups


def render_cluster(cluster, final_lines):
    # a scaffold hint taken out with nothing put in its place is not worth a line on paper
    if all(kind == "-" and HINT.match(text) for kind, text, _ in cluster):
        return ""
    # the method the change is in: its signature, straight above when it is close, else with a gap
    top = cluster[0][2]
    signature = next((n for n in range(top - 1, 0, -1) if SIGNATURE.match(final_lines[n - 1])), None)
    out_rows = []
    if signature is not None and top - signature <= 3:
        out_rows += [(" ", final_lines[n - 1]) for n in range(signature, top)]
        number = signature
    else:
        if signature is not None:
            out_rows += [(" ", final_lines[signature - 1]), ("gap", "")]
        if final_lines[top - 2].strip():
            out_rows.append((" ", final_lines[top - 2]))
        number = top - 1 if signature is None else signature

    changes = []
    current = top
    for kind, text, line in cluster:
        changes += [(" ", final_lines[n - 1]) for n in range(current, line)]
        changes.append((kind, text))
        current = line + 1 if kind == "+" else line
    if current <= len(final_lines):
        changes.append((" ", final_lines[current - 1]))

    # a run of removed scaffold hints becomes one line
    i = 0
    while i < len(changes):
        if changes[i][0] == "-":
            j = i
            while j < len(changes) and changes[j][0] == "-":
                j += 1
            run = [r[1] for r in changes[i:j]]
            if all(HINT.match(r) for r in run):
                out_rows.append(("x", "%d %s TODO" % (len(run), lines_word(len(run)))))
            else:
                out_rows += changes[i:j]
            i = j
        else:
            out_rows.append(changes[i])
            i += 1

    code = [t for k, t in out_rows if k in " +-" and t.strip()]
    indent = min((len(t) - len(t.lstrip(" ")) for t in code), default=0)
    out = ['<div class="hunk"><span class="at">%d</span>' % number]
    for kind, text in out_rows:
        if kind == "x":
            out.append('<div class="x"><i>&minus;</i>[%s]</div>' % text)
        elif kind == "gap":
            out.append('<div class="c"><i>&nbsp;</i>&#8942;</div>')
        else:
            mark = {"+": "+", "-": "&minus;", " ": "&nbsp;"}[kind]
            cls = {"+": "a", "-": "d", " ": "c"}[kind]
            out.append('<div class="%s"><i>%s</i>%s</div>' % (cls, mark, html.escape(text[indent:]) or "&nbsp;"))
    out.append("</div>")
    return "\n".join(out)


sections = []
for number, title, run, tests in STEPS:
    files = parse(git("diff", "-U0", branch("step-%d" % number), branch("step-%d-final" % number), "--", "src/main"))
    blocks = []
    for path, rows in files.items():
        imports = [t[len("import "):].rstrip(";") for k, t, _ in rows if k == "+" and t.startswith("import ")]
        rows = [r for r in rows if not r[1].startswith("import ") and r[0] != "gap"]
        final_lines = git("show", "%s:%s" % (branch("step-%d-final" % number), path)).splitlines()
        short = path.replace("src/main/java/pl/livecoding/musicjam/", "")
        header = '<h3>%s%s</h3>' % (html.escape(short),
                                    ' <span class="imp">+ import %s</span>' % html.escape(", ".join(imports)) if imports else "")
        hunks = [h for h in (render_cluster(cluster, final_lines) for cluster in clusters(rows)) if h]
        blocks.append((header, hunks))
    heading = """<h2 id="krok-{n}">Krok {n}: {title}</h2>
<p class="meta"><b>Uruchom:</b> {run}<br><b>Testy:</b> {tests}</p>""".format(n=number, title=html.escape(title), run=html.escape(run), tests=html.escape(tests))
    body = []
    for index, (header, hunks) in enumerate(blocks):
        lead = (heading if index == 0 else "") + header + hunks[0]
        body.append('<div class="keep">%s</div>' % lead)
        body.extend(hunks[1:])
    sections.append('<section class="step">\n%s\n</section>' % "\n".join(body))

head = git("log", "-1", "--format=%h %cs", branch("step-5-final")).strip()
toc = "".join('<li><a href="#krok-%d">Krok %d: %s</a></li>' % (n, n, html.escape(title)) for n, title, _, _ in STEPS)
page = """<!doctype html>
<html lang="pl"><head><meta charset="utf-8">
<title>Kod, który słychać – ściąga z rozwiązań</title>
<style>
@page { size: 170mm 227mm; margin: 9mm 8mm; }
* { box-sizing: border-box; }
body { margin: 0; color: #1b1b1f; font: 9pt/1.35 Consolas, "Courier New", monospace; }
h1 { font: bold 17pt "Segoe UI", Arial, sans-serif; margin: 0 0 2mm; }
.lead { font: 10.5pt/1.45 "Segoe UI", Arial, sans-serif; margin: 0 0 3mm; }
.legend { font: 10pt "Segoe UI", Arial, sans-serif; margin: 0 0 3mm; }
.legend span { display: inline-block; padding: 0.4mm 2mm; margin: 0 2mm 1mm 0; font-family: Consolas, monospace; border-radius: 1mm; }
ol.toc { font: 12pt/1.8 "Segoe UI", Arial, sans-serif; padding-left: 6mm; }
ol.toc a { color: #1d4ed8; text-decoration: none; }
.step { break-before: page; }
.keep { break-inside: avoid; }
h2 { font: bold 15pt "Segoe UI", Arial, sans-serif; margin: 0 0 1.5mm; padding-bottom: 1mm; border-bottom: 1.2pt solid #1b1b1f; }
.meta { font: 10pt/1.45 "Segoe UI", Arial, sans-serif; margin: 0 0 3mm; color: #333; }
h3 { font: bold 10.5pt Consolas, monospace; margin: 4mm 0 1mm; padding: 1mm 2mm; background: #eceef2; border-radius: 1mm; break-after: avoid; }
.imp { font-weight: normal; color: #444; }
.hunk { position: relative; margin: 0 0 2.5mm; padding: 0.6mm 0 0.6mm 9mm; border-left: 1pt solid #9aa3b2; break-inside: avoid; }
.at { position: absolute; left: 1mm; top: 0.8mm; font-size: 8pt; color: #6b7280; }
.hunk div { white-space: pre-wrap; word-break: break-word; padding-left: 4mm; text-indent: -4mm; }
.hunk i { font-style: normal; display: inline-block; width: 4mm; text-indent: 0; font-weight: bold; }
.a { background: #e3f4e8; font-weight: 600; }
.d { background: #fbe6e6; text-decoration: line-through; color: #7a3b3b; }
.x { font-style: italic; color: #7a3b3b; }
.legend .a { background: #e3f4e8; }
.legend .x { background: #fbe6e6; }
* { -webkit-print-color-adjust: exact; print-color-adjust: exact; }
</style></head><body>
<h1>Kod, który słychać – ściąga z rozwiązań</h1>
<p class="lead">Co zmienia się między szkieletem kroku (<code>step-N</code>) a rozwiązaniem (<code>step-N-final</code>) w <code>src/main</code>. Nad każdą zmianą sygnatura metody, w której leży; liczba z lewej to numer linii w <code>step-N-final</code>. Ścieżki plików od <code>pl/livecoding/musicjam/</code>.</p>
<p class="legend"><span class="a">+ rozwiązanie</span><span class="x">&minus; [n linii TODO] ze szkieletu</span></p>
<ol class="toc">{toc}</ol>
<p class="lead">Stan gałęzi: step-5-final @ {head}</p>
{sections}
</body></html>""".replace("{head}", html.escape(head)).replace("{toc}", toc).replace("{sections}", "\n".join(sections))

with open(OUT, "w", encoding="utf-8", newline="
") as f:
    f.write(page)
print("wrote", OUT)
