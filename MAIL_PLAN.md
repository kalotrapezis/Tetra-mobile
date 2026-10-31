# Tetra Mail: plan (draft for discussion, 31 Oct 2026)

What the user asked for (31 Oct): a mail applet because Thunderbird's Android app does not become a proper
two-pane mail app on a tablet; mail and attachments kept **locally** like the rest of Tetra, in a system folder
the AI can read and report on (no connector that breaks on permissions); **unified folders that expand to each
account's folders** as in Thunderbird; the archive as an **option**, with auto-clearing of old mail or old mail
with attachments; **filters** and a **spam folder**; and Thunderbird as the reference for everything else.

**Only mail.** Calendar and contacts are Tetra's own applets already; the mail applet does not grow its own.

Accounts to support first: Hotmail (outlook.com), Gmail (kalotrapezis@gmail.com), sch.gr (goodtable@sch.gr),
nup.ac.cy (t.kalotrapezis@…, Microsoft 365).

## 1. Who fetches the mail

- **The desktop syncs like Thunderbird does:** IMAP both ways (new mail in; read/star/move/delete back to the
  server) and SMTP to send. It is always the one with the full copy.
- **Phone and tablet** read and act on the same mail through Tetra's sync (worst case, as the user put it). Actions
  taken on the phone (read, archive, delete, reply) are written as small requests that the desktop carries out.
- **Shadow accounts** (the user's name): an account the phone does not fetch itself (e.g. one whose sign-in works only on
  the PC) but receives through the PC. It is in the phone's folder list like any other, dimmed, with a ⇄ PC badge;
  its mail can be opened and explored as far as it has synced. Every other account the phone may also fetch itself when
  it cannot reach the PC; the same file layout means the two never disagree on where a mail lives.

| Account | How it signs in | Notes |
|---|---|---|
| sch.gr | IMAP/SMTP with the normal password | mx.sch.gr; simplest, do it first |
| Gmail | IMAP with an **app password** (Google 2-step verification on) | Google sign-in (OAuth) later if wanted |
| Hotmail | **Microsoft sign-in (OAuth2) only**; plain passwords are refused | Register "Tetra" with Microsoft once (free) |
| nup.ac.cy | Microsoft 365, the same OAuth2 | **Risk:** the university may require IT approval for a new app. Fallback: forward to Gmail |

Desktop libraries (all MIT, well kept): `imapflow` (IMAP, IDLE push), `nodemailer` (SMTP), `mailparser` (MIME).
Passwords and tokens go in the OS keychain (Electron safeStorage), never in the synced folder.

## 2. Where the mail lives (the folder the AI reads)

```
~/Tetra/.System/Mail/
  accounts.json                         names, servers, colours (no secrets)
  <account>/<server folder>/<yyyy>/<yyyy-mm-dd hhmm> <from> - <subject>.eml
  <account>/<server folder>/<yyyy>/<same name>.files/   attachments as normal files (PDF, docx, jpg…)
  index.jsonl                           one line per mail: id, account, folder, date, from, to, subject,
                                        flags, tags, size, has-attachments, snippet — fast lists and search
  filters.json   junk.json   rules.json  the user's filters, the spam filter's word counts, the clean-up rules
  outbox/                               mail written on the phone, sent by the desktop
```

- `.eml` is the standard: any mail program opens it, Claude and the Tetra assistant read it as text.
- Attachments next to their mail as real files, so the AI and Files can open them without decoding MIME.
- Sync to the phone: `index.jsonl` and the mail of the last N months always; older bodies and attachments on demand
  (setting: "Attachments on this device: last month / last year / all / none").

## 3. Archive and clean-up (the user's "archive as an option")

**Auto-delete, two places with their own ages** (the user, 31 Oct: "server or/and backups as well, separate dates"):

| Where | Mail with attachments | Mail without attachments | Default |
|---|---|---|---|
| **Server** (frees the mailbox) | older than **3 years** | older than **2 years** | **on** |
| **Local archive** (frees the disk) | older than *N* | older than *N* | off |

- Each place is its own switch with its own two ages (months or years), so one can be on, both, or neither.
- *Archive locally* is **on** by default. On the server, only mail already green (saved and checked locally) is
  ever deleted; with the local archive switched on too, its ages should be longer than the server's, and Tetra warns
  when they are not ("this would leave no copy anywhere").
- Per account, overridable per folder.

**Backup badges, to see at a glance if anything is wrong:** every mail row has a small dot, every folder's count a
colour — **green** = saved locally and checked; **orange** = only on the server (not fetched yet, too big, or failed).
A folder is orange while any of its mail is; tapping the orange count lists what is missing and why.

Per account, and overridable per folder, as Thunderbird's *Disk space* and *Archive options*:

- **Archive button**: moves to Archive, by **year** or **year/month** (Thunderbird's "archive granularity").
- **Keep a local copy when deleted on the server**: on/off. On = Tetra is the long-term archive even if the
  mailbox is cleaned to save space.
- **Clean up automatically** (each its own switch):
  - mail older than *N* days/months/years;
  - mail with attachments older than *N* — keep the mail, drop only the attachments (Thunderbird's "detach");
  - keep only the newest *N* in a folder;
  - empty Spam after *N* days, Trash after *N* days;
  - always keep: starred, tagged "Keep" (each a switch).
- **Free space on the server**: optionally delete from the server mail already archived locally and older than *N*.
- A preview before the first clean-up: "This will remove 1 240 mails (3.1 GB) — Show them".

## 4. Folders: unified, expanding

As in the user's Thunderbird screenshot: **Inbox (271)** expands to Gmail 224 · sch.gr 2 · nup 43. The same for
Drafts, Sent, Archive, Spam, Trash, Templates. Below them each account's own extra folders (e.g. Gmail › Αγορές).
Counts are unread. Long-press a folder: favourite, rename, colour, clean-up rules, "Keep on this device".

## 5. Spam

- Trust the server first: whatever Gmail/Outlook put in their Junk/Spam folder shows in Tetra's unified **Spam**.
- A local learning filter (naive Bayes, as Thunderbird's "adaptive junk filter"): *Spam* / *Not spam* buttons
  teach it; it moves likely spam to Spam. Never spam: anyone you have written to.
- Remote images blocked by default (tracking pixels), "Show images" once or always for this sender.
- "Unsubscribe" button when the mail carries a List-Unsubscribe header.

## 6. Filters (Thunderbird's message filters)

- **When:** on new mail · after the spam check · when sending · run now on a folder.
- **If** (all / any): from, to/cc, subject, body, has attachment, size, date/age, account, tag, list id.
- **Then:** move/copy to folder, tag, star, mark read, archive, delete, forward, reply with a template,
  **summarise with the assistant**, **save attachments to a Files folder** (e.g. invoices → Files/Τιμολόγια).
- Ordered list, each switchable; "Create a filter from this mail" fills it from the open mail.

## 7. Other Thunderbird features, in order of need

- **Soon:** threads (conversations); search (local index, plus the server's search); quick filters (Unread ·
  Starred · Attachments · From contact); tags with colours; star; signatures per address; reply / reply all /
  forward; drafts saved as you type; address autocomplete (from addresses already in the mail); "forgot the attachment?" check;
  notifications per account. (No calendar or address book inside Mail: Tetra's own applets cover those.)
- **Later:** templates; send later; saved searches (virtual folders); several addresses per account; read receipts;
  print / PDF; import from Thunderbird (mbox/eml); OpenPGP / S/MIME.

## 8. The assistant

- Reads `~/Tetra/.System/Mail` directly: "what came from the university this week", "what needs an answer",
  "find the invoice from …".
- **Mail report**: a brief (morning, after the alarm — ties in with the scheduled briefs): new important mail,
  what needs an answer, deadlines found in mail, and anything orange (not backed up).
- Draft a reply in the user's voice; summary at the top of a long thread.

## 9. Screens (Tetra's islands; the user's corrections, 31 Oct)

- **The folders (locations) are at the side**, never in the bottom island: the unified folders expanding into the
  accounts, shadow accounts dimmed with ⇄ PC, every count green or orange.
- **The bottom island holds actions:** ＋ New · ⟳ Sync · Filter (a **funnel** icon, Material "filter_alt"; the quick filters: Unread · ★ · 📎 · account) ·
  Archive (the **same box icon as Notes' Archive**, `ic_archive`). **Pulled up**, the less used: Spam / Not spam, Move, Tag, Clean up now, Filters (rules), Accounts, Settings.
  With mails selected, the actions apply to them (Archive, Delete, Move, Read, Spam).
- **The Filter modal** (from the island's Filter): quick filters on top — Unread · ★ · 📎 · account · backup state
  (green/orange) · from · date range · size · tag — and **advanced sorting** below (the user, 31 Oct): sort by date,
  sender, subject, size, account, last reply; ascending/descending; unread first, starred first, with attachments
  first; group by day · sender · account · conversation (threads on/off). The choice is kept per folder, and a
  combination can be saved as a named view (Thunderbird's saved search).
- **Tablet, wide:** three columns — folders · list · open mail, with **two islands** (the user agreed, 31 Oct): the
  **list actions** island under the list (New · Sync · Filter · Archive, pulled up for the rest) and the **mail
  actions** island under the open mail.
- **Mail actions island: big buttons with words, few of them** (the user, 31 Oct: "the buttons are small, add words,
  fewer options"). Reading: **↩ Απάντηση** only. Writing: **Αποστολή** and **Αποθήκευση πρόχειρου**. Everything else
  is in the panel pulled up from that island: reading — Σε όλους, Προώθηση, Αρχειοθέτηση, Διαγραφή, Ανεπιθύμητο,
  Μετακίνηση, Ετικέτα, Εκτύπωση, Πηγή; writing — Συνημμένο, Κοιν./Κρυφή κοιν., Υπογραφή, Αποστολή αργότερα, Απόρριψη.
- **Phone, narrow:** Mailboxes (the folder list) → the list → the mail, Back going up one level each time (as iOS Mail);
  the island at the bottom of each.
- **Very big screens (desktop, a large window):** a desktop layout — the same three columns, and the island's tools
  move to the **top of the screen as an action bar** (New, Sync, Filter, Archive, then Reply · Reply all · Forward ·
  Delete · Spam for the open mail, and ⋯ for the rest).
- **Open mail:** sender and subject at the top; the mail actions island at the bottom (in the action bar on the desktop). Attachments as cards (open in Files/Office/PDF, save). The assistant's summary
  on top of a long mail or thread.
- **♥ at the right of every mail** (the user, 31 Oct): tap to make it a favourite (Tetra's heart, as Contacts and
  Photos; it takes the place of Thunderbird's star — the IMAP "flagged" flag, so Gmail and Outlook show it starred).
  Filters and sorting say "Αγαπημένα".
- **Selecting:** long-press a mail, then tap others (or Όλα). The top turns into ✕ · "3 επιλεγμένα" · Όλα; each row gets
  a ✓ circle. The list island turns into the **selection island**: three big labelled actions — Αρχειοθέτηση ·
  Διαγραφή · Αναγνωσμένα (Αδιάβαστα when all are read) — and, pulled up: ♥ Στα αγαπημένα, Μετακίνηση, Ανεπιθύμητα,
  Ετικέτα, Προώθηση ως συνημμένα, Λήψη τοπικά (the orange ones), Σύνοψη με τον βοηθό. On a tablet the right pane shows
  what is selected (subjects, sizes, green/orange, accounts) and "Σύνοψη και των N"; with one selected, that mail stays
  open with its own island. ✕ or Back ends the selection.
- **Layout setting** (the user, 31 Oct): *Mail beside the list* or *Mail under the list* (list on top, the open mail
  below, a handle between them to share the height), chosen **per screen size** — phone, tablet portrait, tablet
  landscape, desktop — e.g. side by side on the landscape tablet and the desktop, list over mail on the portrait tablet.
- **Swipes** in the list: right = Archive, left = Delete (changeable in Settings).
- Account colour as a thin bar on each row in unified views.

## 9b. Adding an account: as painless as it can be

**Three steps: the address → sign in → done.** Everything else is found automatically or has a default.

1. **The address.** One field, «Η διεύθυνσή σου». Tetra finds the servers itself, as Thunderbird does:
   known providers built in (gmail.com, outlook.com/hotmail.com/live.com) → Thunderbird's public provider database
   (`autoconfig.thunderbird.net`) → the domain's own `autoconfig.` / Microsoft `autodiscover` → the domain's MX
   record (nup.ac.cy → Microsoft 365, sch.gr → mx.sch.gr) → try the usual names (`imap.`, `mail.`) with the usual ports.
   A name for the account and its colour are filled in (editable later).
2. **Sign in**, whichever way that provider needs, explained in one line:
   - **Microsoft (Hotmail, nup.ac.cy): «Σύνδεση με Microsoft»** — the browser opens Microsoft's own page, the user signs
     in there (with their phone code if they use one), and comes back. Tetra never sees the password. If the university
     says the app needs approval, Tetra says so in plain words and offers the two ways out (ask IT / forward to Gmail).
   - **Gmail: «Σύνδεση με Google»** if Tetra is approved by Google by then; until then an **app password**, guided: a
     button opens Google's App passwords page, the user copies the 16 letters, Tetra reads them from the clipboard when
     they come back (no typing). If 2-step verification is off, the same button first opens that page.
   - **Others (sch.gr): the password** in one field.
3. **Done.** Tetra checks the connection, finds Inbox/Sent/Drafts/Spam/Trash/Archive by the server's own markings,
   starts downloading, and shows one summary card with the defaults — *archive locally* on, server clean-up after 3/2
   years, notifications on — and a single **Έτοιμο** button; «Αλλαγή» opens the account's settings for anyone who wants.

- **Add it once, see it everywhere:** an account added on the PC appears on the phone and tablet by itself (as a
  shadow account, ⇄ PC); one added on the phone appears on the PC the same way. The secret (password or Microsoft/
  Google sign-in) stays only on the device where it was entered, in its keychain.
- **When something is wrong** (wrong password, server unreachable, approval needed), one plain sentence saying what
  happened and the one thing to do, never a raw error; «Λεπτομέρειες» shows the technical part.
- **Manual setup** only behind «Ρυθμίσεις χειροκίνητα» (server names, ports, security) for the rare provider not found.
- **Later:** import from Thunderbird on a computer that has it (accounts, and its stored mail into the archive);
  this PC has no Thunderbird profile, so it is not in the first version.

**One-time work for the developer:** register "Tetra Mail" with Microsoft (free, Azure portal, personal and work/school
accounts, IMAP/SMTP permissions) and, for «Σύνδεση με Google», with Google (Gmail is a "restricted" scope: Google's
review takes weeks; until then the app password).

## 9c. Settings › Αλληλογραφία (the user, 31 Oct)

A **Mail category** in Tetra's Settings (the redesigned Settings: categories on the left, the page on the right on a
tablet; one at a time on a phone). Its page lists sub-pages, each a row with ›:

- **Λογαριασμοί** — the accounts (colour, name, address, green/orange state, ⇄ PC for shadow accounts) and
  **Προσθήκη λογαριασμού**, which opens the account creation page (§9b). Tapping an account: name, colour, sign in
  again, folders to sync, «Αφαίρεση» (removes it from Tetra; asks whether to keep its local archive).
- **Υπογραφές** — an **HTML signature** per address: a rich editor (bold, links, an image/logo, colours) with an
  «HTML» switch to paste or edit the source; a live preview; when to add it (new mail · replies & forwards), above or
  below the quoted text; a plain-text version made automatically for plain-text mail.
- **Ασφάλεια και απόρρητο:**
  - **Φόρτωση εξωτερικού περιεχομένου** (images, fonts from the web — the user: "without it, ask every time"):
    off by default → every such mail shows a bar «Εμφάνιση εικόνων» · «Πάντα από αυτόν τον αποστολέα»; the list of
    allowed senders/domains is editable here; switched on, everything loads.
  - Block tracking pixels even when images load: on.
  - Warn when a link's text and its real address differ (phishing): on.
  - Warn before opening risky attachments (.exe, .js, .bat, macros in Office files): on.
  - Read receipts: never send · ask · always.
  - Show the real sender address next to the name: on.
- **Εμφάνιση** — mail beside / under the list per screen size (§9), threads, preview lines, density.
- **Αρχειοθέτηση και καθαρισμός** — §3: local archive, server and local auto-delete with their ages.
- **Ανεπιθύμητα και φίλτρα** — §5 and §6.
- **Ειδοποιήσεις** — per account and folder; quiet hours.

## 10. Order of work

1. **Desktop engine, read only:** sch.gr and Gmail; sync Inbox, Sent, Archive, Spam, Trash to `.eml` + index;
   unified folders; the desktop's three-column screen; search. The AI can already read it.
2. **Phone/tablet read-only:** the Mail applet on the synced folder, wide and narrow layouts, attachments.
3. **Actions both ways:** read, star, archive, delete, move; from the phone through requests to the desktop.
4. **Writing:** compose, reply, forward, drafts, signatures, outbox.
5. **Microsoft sign-in:** Hotmail, then nup.ac.cy (ask the university's IT if it is blocked).
6. **Clean-up rules, filters, local spam filter.**
7. **Assistant mail tools and the mail report.**

## 11. The shared folder and the phone's requests (as built, desktop `mail.js`, 31 Oct)

**Sync.** `Mail` is one of the apps' shared folders (`system.SHARED` on the desktop, `TetraSystem.SHARED` on Android):
sync names its files `Mail/…`, on disk `.System/Mail/Shared/…`, hidden from Files. Every file is in the desktop's `have`
list on every sync; the phone downloads what it wants (`index.jsonl`, `accounts.json`, recent `.eml` and `.files/`)
and leaves the rest. An older one is fetched when opened with **`GET /mail/file?path=Mail/<address>/…`** (same auth as
`/file/<sha>`; any file under `Mail/`, no hash needed), which also serves one the phone let go to free space (sync no
longer offers a file the device held and dropped). A phone dropping a Mail file never deletes it on the desktop.
The desktop writes its own temporary files as dotfiles (`.<name>.part`), which sync never lists.

**index.jsonl** (one JSON per line, the last line per `id` wins): `id` (opaque), `account`, `folder` (Inbox, Drafts,
Sent, Archive, Spam, Trash), `box` (the server folder, e.g. `Archive/2026`), `uidValidity`, `uid`, `date` (ms),
`from`, `fromAddress`, `to[]`, `subject`, `messageId`, `seen`, `flagged` (♥), `size`, `attachments` (count),
`snippet`, `saved` (green), `file` (path under `Mail/`, absent until downloaded, null once moved), `gone` (not on
the server in that folder any more: hide it), `movedTo` (the id it became after a move).

**Requests (phone → desktop).** One action per file, written by the phone into the synced folder:

```
Mail/requests/<device>-<ms>.json
{ "op": "read" | "unread" | "favorite" | "unfavorite" | "archive" | "delete" | "move" | "spam" | "notSpam",
  "ids": ["<index id>", …],          // any accounts, any folders; at most 5000
  "to": "Inbox" | "Archive" | …,      // only with "move": one of the six folder roles
  "at": 1793439266004,                // ms; requests are carried out oldest first
  "device": "<device name>" }         // for the log only
```

- The desktop sees it as soon as sync writes it (it watches the folder; and every minute), carries it out on the
  server, writes the result into `index.jsonl` (flags at once; a moved mail is `gone` at once and appears in its new
  folder after the next sync, with its file moved there, not downloaded again), then **deletes the request file**.
- It cannot be done now (no connection, a password to fix): the file stays and is tried again. It can never be done
  (an unknown `op`, a bad `to`): the file is deleted and the desktop's log says why. Ids no longer in the index, or
  already `gone`, are skipped.
- The phone shows its own action at once (optimistically) and then trusts `index.jsonl`; it deletes its copy of the
  request once the upload is done (the desktop removed it; sync does not bring it back).
- `delete`: to the account's Trash; from the Trash, deleted on the server for good. `archive`: to the account's
  Archive (made as `Archive` if the server has none — a label on Gmail), in a folder per year, per month or none
  (`archiveBy` in `accounts.json`, default year, as Thunderbird). `spam` → Spam, `notSpam` → Inbox. **The copy on
  the desktop is never deleted** by any action.

**The phone's side (step 2, Tetra-mobile `Mail.kt`).** It keeps the index and accounts always, mail for 6 months and
attachments for 1 month by default (Settings › Αλληλογραφία › Σε αυτή τη συσκευή: mail 1/6/12 months/all, attachments
none/1 month/1 year/all), judged by the date in the file's path; older ones show a green ring ("on the PC") and come by
`/mail/file` when opened or tapped. A file outside the window is let go at a whole sync once it has not been opened
for a week. It never downloads `Mail/requests/` (another device's request must not travel back) and deletes its own
once uploaded. A request is sent at once by the scoped sync `mail` (desktop `SCOPES.mail = Mail/requests`), or by a
whole sync where the desktop has no such scope. An attachment it does not hold is named as the desktop's `store()`
names it (clean(filename, 150), " (2)" for repeats, "attachment" when nameless), read from the `.eml`.

**A device's own accounts (step 2 continued).** An account added on the phone or tablet is fetched there by its own
IMAP engine (Angus Mail): the password in that device's keystore, the account (no secret) in `Mail/accounts.<device>.json`
(the desktop's entry format plus `smtp`, `server.starttls`, `addedOn: "phone"`, `device`), its index lines in
`Mail/index.<device>.jsonl`, its mail in the same paths with the same names and ids as the desktop's. Nobody writes
another's file. Readers take `accounts.json` then every `accounts.*.json`, `index.jsonl` then every `index.*.jsonl` (the
reading device's last); a line without a file never hides the file another line has. An account both fetch: the same
ids, and a body already saved by either is adopted, not fetched again. A mail moved on the server moves its file
(matched by Message-ID, else date + sender + subject + size). Actions on a device's own accounts go to the server at
once; on the PC's accounts, as requests. `<device>` is Build.MODEL with only [A-Za-z0-9_-].
Writing: own accounts send by SMTP (a copy into Sent, except Gmail, which files its own) and save drafts into Drafts;
their Sent/Drafts copies reach `index.<device>.jsonl` by the look at the account that follows. The PC's accounts go
through the outbox (§12): the phone writes `outbox/<device>-<ms>.json` (+ `.files/` for its own attachments; the
archive's by path), the body as HTML with the signature in `<div class="tetra-signature">`; its sync uploads the
`.json` files after everything else and deletes its copies once uploaded; it takes back only `*.failed.json`, says
them once and deletes them. Signatures are `signatures.json`'s, edited on the phone as plain text.

## 12. Writing: the desktop sends, the phone's outbox (as built, desktop `mail.js`, 31 Oct)

**On the desktop.** New, Reply, Reply all, Forward (inline, with the attachments) and Forward as attachment (the
`.eml`). Sent by SMTP with the account's own password (`smtp` in `accounts.json`: built in for Gmail and sch.gr, from
Thunderbird's database, or the IMAP host on 465). The same bytes are kept at once in `<address>/Sent/…` (green, a
`local` index line) and appended to the server's Sent (Gmail files its own); when the sync finds the server's copy
with the same Message-ID and size, that line takes over the file (`movedTo`), nothing downloaded. A draft is saved
four seconds after typing stops: one file in `<address>/Drafts/…` and one message (`\Draft`) on the server, each
replacing the last version (same Message-ID); sending removes it from both. The text copy (`text/plain`) is made
from the HTML. Bcc goes on the envelope only.

**Signatures** (§9c): `Mail/signatures.json` = `{ "<address>": { "html", "newMail": bool, "replies": bool,
"position": "above" | "below" } }` (above or below the quoted text). Synced like the rest, so the phone can use the
same one; it is put in by whoever builds the mail (the desktop for its own drafts, the phone for its own), in a
`<div class="tetra-signature">-- <br>…</div>`.

**The outbox (phone → desktop).** A mail written on the phone, to send or keep as a draft:

```
Mail/outbox/<device>-<ms>.json
{ "account": "<address>",                        // which account sends it
  "action": "send" | "draft" | "discard",        // discard: the draft with this messageId goes, here and on the server
  "at": 1793439266004,                           // ms; done oldest first
  "draft": {
    "to":  [{ "name": "Γραμματεία", "address": "s@x.gr" }], "cc": [...], "bcc": [...],   // bcc: envelope only
    "subject": "…", "html": "<p>…</p>",          // the whole body: the reply's quote and the signature already in it
    "inReplyTo": "<id@x>", "references": ["<id@x>"],                                     // for a reply, else omit
    "messageId": "<…>",                          // keep one per draft: saving it again replaces the last version
    "attachments": [{ "name": "photo.jpg", "file": "outbox/<device>-<ms>.files/photo.jpg" },   // the phone's own file
                    { "name": "Guide.pdf", "file": "<address>/Inbox/2026/….files/Guide.pdf" }, // one already in the archive
                    { "name": "x.eml", "file": "<address>/Inbox/2026/x.eml", "type": "message/rfc822" }] },
  "eml": "<device>-<ms>.eml" }                   // optional: a ready MIME mail beside it, sent as it is (then
                                                 //   to/cc/subject come from it; bcc still from "draft")
```

- `file` paths are relative to `Mail/` (the synced folder). Upload the attachments (and the `.eml`) **first**, the
  `.json` **last**: the `.json` is what starts it. One whose `.eml` has not arrived yet waits.
- Done: the `.json`, its `.eml` and its `.files/` are deleted by the desktop; the result shows in `index.jsonl` (the
  mail in Sent, green, or the draft in Drafts). Delete your own copies once uploaded.
- It cannot be done now (no connection, a password to fix): it waits and is tried again every minute. It failed for
  good (no recipients, a refused address, an attachment not there): it becomes `<device>-<ms>.failed.json`, the
  same JSON plus `"error"` (`noRecipients`, `other`, …) and `"detail"`, for the phone to show; the desktop also says
  it in a notification. The phone deletes the `.failed.json` once it has shown it.
- Sync: the scope `"mail"` covers `Mail/requests` and `Mail/outbox`, so these go at once.

## 13. Microsoft sign-in: registering Tetra once (for the user; as built, desktop `msauth.js`, 31 Oct)

Hotmail/Outlook.com and Microsoft 365 (nup.ac.cy) refuse passwords; they sign in through an app registered with
Microsoft. That registration is done **once, by you**, with your own Microsoft account; it is free. Tetra then needs
only its **Application (client) ID** (a code like `1a2b3c4d-…`), entered in Tetra › Ρυθμίσεις › Αλληλογραφία ›
«Σύνδεση Microsoft». No secret is created or needed (Tetra is a "public client": the browser sign-in with PKCE).

1. Open **https://entra.microsoft.com** (or portal.azure.com → "Microsoft Entra ID") and sign in with your Hotmail
   (a personal account works; if it asks to create a free tenant/directory, accept).
2. **Applications → App registrations → New registration.**
   - Name: `Tetra Mail`.
   - Supported account types: **"Accounts in any organizational directory and personal Microsoft accounts"**
     (multitenant + personal) — so both Hotmail and nup.ac.cy can use it.
   - Redirect URI: platform **"Public client/native (mobile & desktop)"**, value **`http://localhost`**
     (Microsoft lets a desktop app use any port on localhost; Tetra picks a free one each time).
   - Register.
3. On the app's **Overview** page, copy **Application (client) ID** → paste it in Tetra's setting.
4. **Authentication**: under "Advanced settings", set **Allow public client flows = Yes**; save.
5. **API permissions → Add a permission → APIs my organization uses → type "Office 365 Exchange Online"**
   → Delegated permissions → tick **`IMAP.AccessAsUser.All`** and **`SMTP.Send`** → Add. (`offline_access` is asked for by
   Tetra itself; "Microsoft Graph › User.Read" may stay.) Do **not** press "Grant admin consent" — it is not needed for
   your own accounts.
6. In Tetra: Αλληλογραφία › Λογαριασμοί › Προσθήκη → your Hotmail address → «Σύνδεση με Microsoft» → Microsoft's page
   opens in the browser → sign in (and approve "Tetra Mail" the first time) → the browser says you can go back.

**nup.ac.cy (Microsoft 365):** the university's IT may require that they approve new apps. Then Microsoft's page says
"Need admin approval" and Tetra says so in plain words (`err_approval`). The two ways out: ask IT to approve
"Tetra Mail" (send them the Application ID and the two permissions above), or forward the university mail to Gmail.

**SMTP for Hotmail:** Microsoft has SMTP AUTH on by default for personal accounts; for Microsoft 365 the IT may have it
switched off per mailbox ("Authenticated SMTP"). Then reading works and sending fails with a plain sentence; ask IT
to enable it for your mailbox, or send from another account.

How it works inside: the authorization code with PKCE (S256) and a loopback redirect on 127.0.0.1; scopes
`offline_access`, `https://outlook.office.com/IMAP.AccessAsUser.All`, `https://outlook.office.com/SMTP.Send`; IMAP
`outlook.office365.com:993` and SMTP `smtp.office365.com:587` (STARTTLS) by XOAUTH2. The refresh token is kept
encrypted (safeStorage) in this computer's settings only, renewed when Microsoft sends a new one; access tokens live
an hour, in memory. A phone does not sign in to Microsoft: it reads these accounts as shadow accounts (⇄ PC).

## 14. How much mail each device keeps (the user, 31 Oct; as built on the phone, `Mail.kt`)

On each device's card on **the PC's Devices page** — the PC is the hub, as for files and photos (the user, 31 Oct:
"the sync options only on the desktop cards, not on the phone; the phone will take it from the desktop"; "no controls
about the sync" on the phone: its Mail settings are its own look, signatures, outside content and so on). The phone
only follows it:

- **Αντιγραφή (copy):** the device keeps its own copy of the newest mail up to the limit, filled from the PC too.
- **Μετακίνηση (move):** it takes nothing from the PC; mail it fetched itself it lets go once the PC holds it,
  keeping what fits the limit. Older mail opens from the PC as before.
- **Κράτα έως** 0,5 / 1 / 2 / 5 / 10 GB / Όλα (decimal GB, as Android and the PC show sizes); default copy, 1 GB.
- How much Mail takes on that device now.

**Budget.** Files without a date in their path (index, accounts, signatures, the devices' files) always, uncounted;
then mail by the date in its path, newest first — first the `.eml` files, then, with what is left, the `.files/`
attachments — until the limit; the first that does not fit ends it (nothing older is kept). A file outside it is let
go at a whole sync only if the PC holds it (it did not ask for it, or just received it verified) and it was not opened
here in the last week. An account only this device fetches has every body downloaded (until the PC has it, the
device's copy is the only one); one the PC fetches too, only what fits.

**Files** (synced like the rest of `Mail/`, hidden from Files):

```
Mail/devices/<device>.json          { "device": "<device>", "name": "Pixel 8",       // shown on the cards
                                      "mode": "copy" | "move",
                                      "limit": 1000000000,                          // bytes; -1 = everything
                                      "setAt": 1793444028498,                       // ms; the later wins
                                      "setBy": "<device>" | "desktop" }
Mail/devices/<device>.usage.json    { "used": 34512, "files": 18, "at": 1793444028498 }   // only that device writes it
```

- Only the PC writes `<device>.json` (the whole object, `setAt` now) when the user changes it there. No file: copy,
  1 GB. The device reads it at every sync and never writes it.
- `<device>.usage.json` is rewritten by the device after a whole sync when its Mail changed by a megabyte or more.
  `used` counts every file of `Mail/` on that device except `devices/`.
- `<device>` is the same name as in `index.<device>.jsonl` (Build.MODEL with only [A-Za-z0-9_-]).

## 15. The phone on its own, conversations, the assistant (the user, 31 Oct; as built on the phone)

- **Standalone.** Tetra on a phone with no PC works: accounts are added on the phone (Gmail with an app password,
  sch.gr and others with their password, **Hotmail/Outlook/Microsoft 365 with «Σύνδεση με Microsoft»** — the
  desktop's msauth.js on Android: the browser, a one-time server on 127.0.0.1, PKCE, the refresh token in the phone's
  keystore, XOAUTH2 for IMAP and SMTP; the app id is the one registered for the PC, §13, typed once on the phone).
  The empty Mail screen offers «Προσθήκη λογαριασμού»; the Sync button looks at the servers (and asks the PC only
  when one is paired). With no PC, an account the phone fetches keeps only what fits the device's limit (default
  1 GB); older mail stays on the server and opens from there. With a PC, it downloads every body for the PC to keep.
  `accounts.<device>.json` marks such an account `"provider": "microsoft"`.
- **Conversations** (Settings › Αλληλογραφία › Εμφάνιση › Συζητήσεις, off by default): the list one row per
  conversation (account + subject without Re:/Fwd:, every folder but Spam and Trash), opened as its mails one
  under another, each a card with its header — the device's own at the right, the others' at the left, in the
  account's colour — with only what each says itself. A card opens that mail; a draft's card is greyed, and opens
  in the compose page to finish and send (sent or saved anew, the old draft goes; the PC's by the same Message-ID).
  Replies stay proper mails: «Απάντηση» opens the compose page with the subject and recipient filled in.
- **The assistant in a reply.** «✦ Πρόταση από τον βοηθό» in a reply's compose page: AI Assist (Mistral, its key in
  Settings › AI Assist) drafts the body from the conversation and whatever the user typed as notes, as a proper mail
  in the conversation's language; the user edits it before sending.
- **Islands** at the top, the bottom or by themselves (top with a mouse) — Office's rule, on a tablet or a big
  screen only; a phone keeps them at the bottom.

## 16. The phone installed: the PC's accounts, checking for new mail, one account on both (the user, 31 Oct)

Formats are the desktop's (step 1's sync.js/mail.js of the same day); the phone follows them.

- **Why the PC's accounts were empty.** The phone fetched each file by its hash (GET /file/<sha>), and the PC rewrites
  `index.jsonl` while it fetches: by the time the phone asked, the PC answered "not what is here any more", and the index
  also waited behind thousands of .eml files. The PC now keeps a growing index as offered for ten minutes. The phone
  takes the PC's Mail files it only reads — `index.jsonl`, `accounts.json`, `privacy/filters/junk.json`,
  `devices/<other>.json`, other devices' `index.<d>.jsonl`/`accounts.<d>.json` — **first**, replacing its copy in place
  (by hash, else GET /mail/file as it is now), and never sends them back. Its own files, `signatures.json` and
  `settings.json` (both write them) go the usual way. Stray "index (2).jsonl"-style copies are deleted.
- **Έλεγχος για νέα**: `Mail/settings.json` `check` — "push" (IDLE while Mail is open, every 15 minutes otherwise; the
  default), 5, 15, 30, or "manual" — one setting for the PC and every device (Settings › Αλληλογραφία on either). The
  phone's background look is one JobScheduler job set again after each run. Opening Mail or coming back to it looks at
  once: the phone's own accounts at their servers; for the PC's, POST /mail/sync (the PC looks now), then a whole sync 10 s later.
- **Per device** (`Mail/devices/<device>.json`, written from the PC's device card): `off` (nothing of Mail crosses; the
  phone deletes the PC's mail and files it holds, keeping its own accounts'), `mode` copy/move, and what stays —
  `keepDays` (365, 30) by the date in the path, or `auto` (everything, as photos and files: nothing goes by itself; at
  90 % full the PC suggests moving what is over 2 years old, or 1, and sets an ordinary Move once the user confirms),
  else `limit` bytes. Each sync's list carries `device: { free, size, mail }` — the phone's free and whole
  storage and Mail's name for it — so the card can suggest moving old things when the phone is nearly full.
- **Four rows per device** (GET /connections): photos, files, mail, other — each { content, direction, keep, keepDays,
  keepFavorites, keepAuto, keepBytes }. A path follows its own row: `Mail/…` mail; a top folder of the system's shared
  ones but the Trash (Calendar, Contacts, Phone, Data, Timer, Maps, AI Assist) other; the rest, the Trash too, files.
  The phone's Drive sync runs while any of files, mail and other is on, and decides sending, receiving and a Move's
  letting go per path by its row; Mail goes both ways unless off. A PC from before the rows: mail and other as files.
- **A received mail's time** on the phone is when it arrived (not the mail's date, which the PC gives its .eml files);
  opening it touches it again. The let-go rule's "not opened here this week" goes by that.
- **Χρήση και σε αυτή τη συσκευή** on a ⇄ PC account: POST /mail/credentials { account } over the paired, pinned-TLS
  connection; the PC answers { account, address, name, server, smtp, provider, user, password }, or signIn: "microsoft"
  (sign in again here; no token leaves the PC). The password is checked at the server, kept only in the phone's
  keystore, and the account written to `accounts.<device>.json` with the same id and colour: the phone fetches it itself
  from then on, its mail still one list.

## 17. Αποθήκευση, and what the user's phone showed (31 Oct, over USB)

- **«Αποθήκευση»** (folder icon): in Mail's pulled-up panel, and in each account's dialog in Settings › Αλληλογραφία.
  Per account: name, address, "size · N mails", the folder's path, «Άνοιγμα φακέλου»; then the total and the whole
  folder. The folder opens in Tetra's own Files (Mail/<address>, though it is under .System), and a .eml opened there
  opens in Mail. The same words as the desktop's (its translations).
- **The limit was applied**: the Xiaomi's card says the last year (keepDays 365, no byte limit), and the phone held mail
  from 2025-10 on, 1.6 GB. But the phone's own fetching read only the byte limit: it now follows the same rule (last
  year/month, Automatic = all, Off, N GB) over all mail on the device, the PC's and its own together.
- **One mail, one file**: the phone fetching kalotrapezis@gmail.com itself gave 13,048 of 13,050 mails the PC's very
  name and bytes (it takes the PC's name when the PC saved the mail first). The 2 others were subjects cut inside an
  emoji ("?" on the phone, half a surrogate on the PC): names are now cut between characters only (the PC to do the same).
  The 23 "(2).eml" on the phone are different mails of the same minute and subject; each index points at the right one.
- **Leftover .part files** of transfers cut off go after a day, at app start and before a whole sync; a mail fetched by
  path leaves none when it fails.

## 18. Tetra as the email app (the user, 31 Oct: "can I set as the default email client?" — "Yes, add it")

- The MailShare alias of MainActivity: SENDTO and VIEW for mailto: (so Android offers Tetra as the email app), and SEND /
  SEND_MULTIPLE for any file as «Email (Tetra)» in the share menu, beside «Send to computer».
- Each opens Mail's compose page with what it brings: the mailto: fields (to, cc, bcc, subject, body; RFC 6068,
  percent-decoded UTF-8, "+" kept), EXTRA_EMAIL/CC/BCC/SUBJECT/TEXT, and EXTRA_STREAM files as attachments, copied into
  the app's cache at once (another app's content:// address stops working once it is done with us).
- No account at all: «Κανένας λογαριασμός δεν μπορεί να στείλει από εδώ», with add account; a ⇄ PC account sends
  through the PC's outbox as before.
- A mailto: link inside a mail opens compose here, not Android's chooser.
- Settings › Αλληλογραφία › «Προεπιλεγμένη εφαρμογή email»: which app opens email links now; opens Android's default apps.

## 19. Storage as the mail's own file manager (the user, 31 Oct: "the mail app to be the file manager … find an important mail I deleted by mistake")

- **«Αποθήκευση» at the bottom of the folder list**: the same folders, list, search, filter and pages over every mail
  kept on this device (MailArchive), the PC's accounts' and this device's own; one the server no longer has, or that no
  index line names, is marked «μόνο τοπικά». Read only: no action there reaches a server.
- **Attachments** (⋮ on each card): «Εξαγωγή στα Έγγραφα» (Tetra/Documents, synced to the PC's; never over a file,
  « (2)» instead), «Αποθήκευση ως…» (Android's picker), «Κοινοποίηση». The mail's panel: «Εξαγωγή μηνύματος» (.eml).
  A copy says «Αντιγράφηκε: …» with «Εμφάνιση», which opens Documents in Files.
- **Files** showed Mail's year folders as hundreds of "….files" attachment folders first (folders before files), the
  mails far below: inside Mail's folders each mail now sits beside its attachments' folder.
- **AI Assist** (Mail allowed): mail_stored_find (sender, words, attachment names, dates; local-only ones too) and,
  only when asked, mail_export (a mail or one attachment into Tetra/Documents, saying where).

## Open questions for the user

- Away from home, does the phone sync with the desktop over the internet, or only on the same Wi-Fi?
- Attachments on the phone: last month / last year / all?
- Keep a local copy of mail deleted on the server: on by default?
- Desktop first (as above), or the tablet screen first with the desktop engine behind it?
