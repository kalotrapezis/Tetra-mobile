# Usage: python3 tools/mail-sample.py <folder> [yyyy-mm-dd]
# A small mail archive in the computer's format (Tetra desktop/mail.js: accounts.json, index.jsonl, <address>/<Folder>/<yyyy>/<…>.eml
# and <…>.files/), for Mail's tests (app/src/test/resources/mail) and for trying the screens before real mail has synced:
#   adb push <folder>/. /sdcard/Tetra/.System/Mail/Shared/
import sys, os, json, base64, datetime
from email.message import EmailMessage
from email.utils import format_datetime

out = sys.argv[1]
today = datetime.datetime.fromisoformat((sys.argv[2] if len(sys.argv) > 2 else "2026-10-31") + "T12:00:00").astimezone()
accounts = [
    {"id": "kalotrapezis@gmail.com", "address": "kalotrapezis@gmail.com", "name": "Gmail", "color": "#e5534b", "addedOn": "desktop"},
    {"id": "goodtable@sch.gr", "address": "goodtable@sch.gr", "name": "sch.gr", "color": "#4f8ff7", "addedOn": "desktop"},
    {"id": "t.kalotrapezis@nup.ac.cy", "address": "t.kalotrapezis@nup.ac.cy", "name": "nup.ac.cy", "color": "#e0a43a", "addedOn": "desktop"},
]
PNG = base64.b64decode("iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAYAAAAfFcSJAAAADUlEQVR42mP8z8BQDwAEhQGAhKmMIQAAAABJRU5ErkJggg==")
# (account, folder, days ago, hh:mm, from name, from address, subject, kind, seen, flagged, saved, attachments)
mails = [
    (2, "Inbox", 0, "09:12", "Γραμματεία ΜΠΣ", "secretariat@nup.ac.cy", "Προθεσμία εργασίας: 15 Νοεμβρίου", "html", False, True, True, ["Οδηγίες_εργασίας.pdf", "Rubric.docx"]),
    (1, "Inbox", 0, "08:40", "Διεύθυνση Σχολείου", "dnsi@sch.gr", "Σύλλογος διδασκόντων Δευτέρα", "qp", False, False, True, []),
    (0, "Inbox", 0, "07:55", "Skroutz", "orders@skroutz.gr", "Η παραγγελία σου στάλθηκε", "remote", True, False, True, []),
    (0, "Inbox", 1, "18:20", "GitHub", "notifications@github.com", "[kalotrapezis/Voice] Release v0.1.0-beta.3", "text", True, True, True, []),
    (0, "Inbox", 1, "11:02", "Microsoft", "account-security@microsoft.com", "Η σύνοψη του λογαριασμού σας", "cid", True, False, True, []),
    (2, "Inbox", 1, "10:15", "Moodle NUP", "moodle@nup.ac.cy", "Νέα ανακοίνωση: Εβδομάδα 7", "text", False, False, False, []),
    (0, "Inbox", 3, "21:40", "Ταχυδρομείο", "noreply@elta.gr", "Το δέμα σας είναι στο κατάστημα", "text", True, False, True, ["απόδειξη.png"]),
    (1, "Sent", 2, "13:00", "Θεολόγος Καλοτραπέζης", "goodtable@sch.gr", "Re: Σύλλογος διδασκόντων", "text", True, False, True, []),
    (0, "Drafts", 4, "22:10", "Θεολόγος Καλοτραπέζης", "kalotrapezis@gmail.com", "Πρόχειρο: ερωτήσεις για το Moodle", "text", True, False, True, []),
    (0, "Spam", 5, "03:33", "Lottery", "win@example.com", "You have won!!!", "text", False, False, True, []),
    (0, "Inbox", 9, "08:00", "Παλιό", "old@example.com", "Ένα που έφυγε από τον server", "text", True, False, True, []),
]
BODY = "Αγαπητοί φοιτητές,\n\nσας υπενθυμίζουμε ότι η τελική εργασία του μαθήματος παραδίδεται έως τις 15 Νοεμβρίου μέσω της πλατφόρμας Moodle.\n\nΜε εκτίμηση"

def clean(s, n):
    s = " ".join("".join(" " if c in '/\\\0:*?"<>|\r\n\t' else c for c in s).split()).lstrip(".")
    while len(s.encode()) > n: s = s[:-1]
    return s.strip()

lines = []
for i, (a, folder, ago, hm, name, addr, subject, kind, seen, flagged, saved, files) in enumerate(mails):
    acc = accounts[a]
    h, m = map(int, hm.split(":"))
    date = (today - datetime.timedelta(days=ago)).replace(hour=h, minute=m, second=0, microsecond=0)
    msg = EmailMessage()
    msg["From"] = f"{name} <{addr}>"; msg["To"] = acc["address"]; msg["Subject"] = subject; msg["Date"] = format_datetime(date)
    if kind == "html": msg["Cc"] = "Καθηγητής <prof@nup.ac.cy>"
    if kind == "qp":
        msg.set_content("Συνάδελφοι,\n\nη συνεδρίαση θα γίνει στις 13:30 στο γραφείο του Διευθυντή.", cte="quoted-printable")
    elif kind in ("html", "remote", "cid"):
        msg.set_content(BODY)
        img = '<img src="https://track.example.com/p.gif" width="1" height="1">' if kind == "remote" else ""
        pic = '<img src="cid:logo@tetra" alt="logo">' if kind == "cid" else ""
        remote = '<p><img src="https://images.example.com/boya.jpg" alt="Boya BY-V20" width="200"></p>' if kind == "remote" else ""
        msg.add_alternative(f'<html><body><p><b>{name}</b></p>{pic}<p>{BODY.replace(chr(10), "<br>")}</p>{remote}{img}'
                            f'<p><a href="https://example.com">Σύνδεσμος</a></p></body></html>', subtype="html")
        if kind == "cid": msg.get_payload()[1].add_related(PNG, "image", "png", cid="<logo@tetra>")
    else:
        msg.set_content(BODY)
    for f in files: msg.add_attachment(b"%PDF-1.4 sample" if f.endswith(".pdf") else b"sample", maintype="application", subtype="octet-stream", filename=f)
    raw = bytes(msg)
    y = date.year
    rel = f"{acc['id']}/{folder}/{y}/{date:%Y-%m-%d %H%M} {clean(name, 60)} - {clean(subject, 120)}.eml"
    entry = {"id": f"{acc['id']}/{folder}/1/{i + 1}", "account": acc["id"], "folder": folder, "uidValidity": "1", "uid": i + 1,
             "date": int(date.timestamp() * 1000), "from": name, "fromAddress": addr, "to": [acc["address"]], "subject": subject, "messageId": f"<{i}@sample>",
             "seen": seen, "flagged": flagged, "size": len(raw), "attachments": len(files), "snippet": "", "saved": False}
    lines.append(entry)  # the headers first, orange, as the computer writes them
    if saved:
        os.makedirs(os.path.dirname(f"{out}/{rel}"), exist_ok=True)
        open(f"{out}/{rel}", "wb").write(raw)
        if files:
            os.makedirs(f"{out}/{rel[:-4]}.files", exist_ok=True)
            for f in files: open(f"{out}/{rel[:-4]}.files/{f}", "wb").write(PNG if f.endswith(".png") else b"sample")
        lines.append({**entry, "file": rel, "saved": True, "snippet": " ".join(BODY.split())[:160]})
    if name == "Παλιό": lines.append({**entry, "gone": True})
os.makedirs(out, exist_ok=True)
json.dump(accounts, open(f"{out}/accounts.json", "w"), ensure_ascii=False, indent=2)
with open(f"{out}/index.jsonl", "w") as f:
    for e in lines: f.write(json.dumps(e, ensure_ascii=False) + "\n")
    f.write('{"id": "cut by a crash\n')
print(f"{len(mails)} mails, {len(lines)} lines in {out}")
