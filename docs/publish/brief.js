// The one-page product brief, as a DOCX. Recreates the layout of the original
// Claude Design brief (August 2026) so it can be regenerated with every
// release like the other published documents. Run from docs/publish:
//
//   node brief.js            # writes out/brief.docx AND out/brief.html (same content),
//                            # version from ../../pom.xml; build.sh renders the PDF
//                            # from the HTML with WeasyPrint like every other document
//
// Layout: header strip, two-line headline, problem/solution columns, eight
// capability cards (2 x 4), three navy fact strips, disclaimer footer.
const fs = require('fs');
const {
  Document, Packer, Paragraph, TextRun, Table, TableRow, TableCell, ImageRun,
  AlignmentType, WidthType, ShadingType, BorderStyle, VerticalAlign, TableLayoutType
} = require('docx');

const VERSION = (fs.readFileSync('../../pom.xml', 'utf8').match(/<version>(\d[^<]*)<\/version>/) || [])[1];
if (!VERSION) { console.error('could not read <version> from pom.xml'); process.exit(1); }

// ---- design tokens (the deck's palette) ----
const NAVY = '132250', NAVY_D = '0B1430', ICE_BG = 'F1F5FC', BODY = '3B4763', GREY = '5A6685',
      MUTED = '8A96A8', MINT = '15A06B', AMBER = 'B57200', WHITE = 'FFFFFF', RULE = 'D7DEEA';
const H = 'Arial', B = 'Calibri';

const PAGE_W = 12240, MARGIN = 720;                 // US Letter, 0.5" margins
const CONTENT_W = PAGE_W - 2 * MARGIN;             // 10800 DXA

const none = { style: BorderStyle.NONE, size: 0, color: WHITE };
const noBorders = { top: none, bottom: none, left: none, right: none, insideHorizontal: none, insideVertical: none };
const gap = (size) => ({ style: BorderStyle.SINGLE, size, color: WHITE });  // white "borders" fake the card gaps

const run = (text, o = {}) => new TextRun({ text, font: o.font || B, size: o.size || 20, bold: !!o.bold, color: o.color || BODY, characterSpacing: o.spacing || 0 });
const para = (children, o = {}) => new Paragraph({ children, alignment: o.align || AlignmentType.LEFT, spacing: { before: o.before || 0, after: o.after || 0, line: o.line || 252 }, border: o.border });
const kicker = (text, color, o = {}) => para([run(text, { font: H, size: 15, bold: true, color, spacing: 30 })], { after: o.after ?? 80, before: o.before ?? 0 });

const cell = (children, o = {}) => new TableCell({
  children, width: { size: o.w, type: WidthType.DXA },
  shading: o.fill ? { type: ShadingType.CLEAR, color: 'auto', fill: o.fill } : undefined,
  borders: o.borders || noBorders, verticalAlign: o.valign || VerticalAlign.TOP,
  margins: { top: o.pad ?? 0, bottom: o.pad ?? 0, left: o.padX ?? 0, right: o.padX ?? 0 },
});
const table = (rows, widths, o = {}) => new Table({ rows, columnWidths: widths, width: { size: widths.reduce((a, b) => a + b, 0), type: WidthType.DXA }, layout: TableLayoutType.FIXED, borders: o.borders || noBorders });

// ---- header ----
const icon = fs.readFileSync('assets/logo.png');
const headerLeft = [
  para([
    new ImageRun({ type: 'png', data: icon, transformation: { width: 30, height: 30 } }),
    run('   Access Approval Tool for Omnissa', { font: H, size: 26, bold: true, color: NAVY }),
  ], { after: 0 }),
  para([run('             PRODUCT BRIEF', { font: H, size: 14, bold: true, color: MUTED, spacing: 30 })], { after: 0 }),
];
const headerRight = [
  para([run('Self-hosted · MIT License', { size: 17, color: GREY })], { align: AlignmentType.RIGHT, after: 20 }),
  para([run('github.com/SquidlyMan/Omnissa-Access-Approvals', { size: 17, color: GREY })], { align: AlignmentType.RIGHT }),
];
const header = table([new TableRow({ children: [cell(headerLeft, { w: 6800, valign: VerticalAlign.CENTER }), cell(headerRight, { w: 4000, valign: VerticalAlign.CENTER })] })], [6800, 4000]);
const headerRule = para([run('', { size: 2 })], { after: 200, border: { bottom: { style: BorderStyle.SINGLE, size: 6, color: RULE, space: 6 } } });

// ---- headline ----
const headline = [
  para([run('Human approval for Omnissa Access,', { font: H, size: 42, bold: true, color: NAVY })], { line: 240, after: 0, before: 120 }),
  para([run('without waiting on a human being online.', { font: H, size: 42, bold: true, color: NAVY })], { line: 240, after: 200 }),
];

// ---- problem / solution ----
const colText = (t) => para([run(t, { size: 19, color: BODY })], { line: 264 });
const columns = table([new TableRow({ children: [
  cell([kicker('THE PROBLEM', AMBER), colText('Omnissa Access can require license approval before a user gets an app — but every request still needs a person to notice it, open the console, and decide. Approvers travel, sleep, and go OOO. Requests wait.')], { w: 5200, padX: 0 }),
  cell([], { w: 400 }),
  cell([kicker('THE SOLUTION', MINT), colText('A self-hosted gateway sits between the request and the decision: it receives the callout, puts it in a live queue, and lets rules — or a person from a phone, Slack, or Teams — approve it in seconds.')], { w: 5200 }),
] })], [5200, 400, 5200]);

// ---- capability cards ----
const cards = [
  ['Live approval queue', 'Real-time updates via SSE, native Omnissa Access callout integration, and a connectivity status tile.'],
  ['Approve from chat', 'Slack messages and Teams Adaptive Cards with deep-link decision buttons — approver signs in, so roles still apply.'],
  ['Time-bound access', 'JIT grants that auto-expire, plus on-demand revoke, reversible block, and permanent vs. temporary decline.'],
  ['Rules & approval chains', 'Auto-approve or auto-expire by app/group wildcard; sequential multi-stage approval by role, group, or person.'],
  ['Full audit trail', 'Records both the acting identity and who access was for, with CSV export for Admins and Auditors.'],
  ['Role-based access', 'Admin, Approver, Viewer, and Auditor roles resolved from Omnissa Access group membership.'],
  ['Claim & escalation', 'Approvers can claim, assign, or release a request; idle requests auto-escalate to the channel or approvers.'],
  ['Approved updates', 'The Dashboard spots a new release; an admin approves it; the host pins, deploys, and proves it by image digest — rolling back on failure.'],
];
const cardCell = ([title, body]) => cell([
  para([run(title, { font: H, size: 19, bold: true, color: NAVY })], { after: 50 }),
  para([run(body, { size: 17, color: GREY })], { line: 250 }),
], { w: 5400, fill: ICE_BG, pad: 170, padX: 200, borders: { top: gap(10), bottom: gap(10), left: gap(10), right: gap(10) } });
const cardRows = [];
for (let i = 0; i < cards.length; i += 2) cardRows.push(new TableRow({ children: [cardCell(cards[i]), cardCell(cards[i + 1])] }));
const cardGrid = table(cardRows, [5400, 5400]);

// ---- fact strips ----
const strips = [
  ['Deploy', 'Docker / Compose on amd64 or arm64, Caddy auto-HTTPS, or behind your own reverse proxy'],
  ['Stack', 'Java 17 / Spring Boot, React + TypeScript, embedded H2'],
  ['Notifications', 'SMTP email, plus generic/Slack/Teams webhooks'],
];
const stripCell = ([title, body]) => cell([
  para([run(title, { font: H, size: 18, bold: true, color: WHITE })], { after: 40 }),
  para([run(body, { size: 16, color: 'D6E2F8' })], { line: 240 }),
], { w: 3600, fill: NAVY_D, pad: 150, padX: 200, borders: { top: gap(10), bottom: gap(10), left: gap(12), right: gap(12) } });
const stripRow = table([new TableRow({ children: strips.map(stripCell) })], [3600, 3600, 3600]);

// ---- footer ----
const footer = para([run(
  'Independent community project — not an Omnissa product, and not affiliated with, endorsed by, or supported by Omnissa, LLC. ' +
  'Provided as-is, for testing, lab, and demo use only — not production. MIT License · © 2026 Dean Flaming · v' + VERSION,
  { size: 13, color: MUTED })], { before: 260, line: 230, border: { top: { style: BorderStyle.SINGLE, size: 6, color: RULE, space: 8 } } });

const doc = new Document({
  creator: 'Dean Flaming', title: 'Access Approval Tool for Omnissa — Product Brief v' + VERSION,
  styles: { default: { document: { run: { font: B, size: 20 } } } },
  sections: [{
    properties: { page: { size: { width: PAGE_W, height: 15840 }, margin: { top: MARGIN, bottom: MARGIN, left: MARGIN, right: MARGIN } } },
    children: [
      header, headerRule,
      ...headline,
      columns,
      kicker('KEY CAPABILITIES', NAVY, { before: 260, after: 100 }),
      cardGrid,
      para([run('', { size: 6 })], { after: 120 }),
      stripRow,
      footer,
    ],
  }],
});

// ---- the HTML twin: same content, same geometry, for WeasyPrint ----
const esc = (t) => t.replace(/&/g, '&amp;').replace(/</g, '&lt;');
const iconUri = 'data:image/png;base64,' + icon.toString('base64');
const html = `<!DOCTYPE html><html><head><meta charset="utf-8"><title>Access Approval Tool for Omnissa — Product Brief v${VERSION}</title>
<style>
  @page { size: Letter; margin: 0.5in; }
  body { font-family: Calibri, Carlito, "Helvetica Neue", Arial, sans-serif; color: #${BODY}; margin: 0; font-size: 10pt; }
  h1, .h { font-family: Arial, "Helvetica Neue", sans-serif; }
  .hdr { display: flex; justify-content: space-between; align-items: center; padding-bottom: 10pt; border-bottom: 1px solid #${RULE}; }
  .hdr .l { display: flex; align-items: center; gap: 10pt; }
  .hdr img { width: 30pt; height: 30pt; }
  .hdr .t { font-family: Arial, sans-serif; font-weight: bold; font-size: 13pt; color: #${NAVY}; line-height: 1.15; white-space: nowrap; }
  .hdr .k { font-family: Arial, sans-serif; font-weight: bold; font-size: 7pt; letter-spacing: 1.5pt; color: #${MUTED}; }
  .hdr .r { text-align: right; font-size: 8.5pt; color: #${GREY}; line-height: 1.4; }
  h1 { font-size: 23pt; color: #${NAVY}; line-height: 1.15; margin: 18pt 0 14pt; }
  .cols { display: flex; gap: 20pt; }
  .cols > div { flex: 1; font-size: 10.5pt; line-height: 1.4; }
  .kick { font-family: Arial, sans-serif; font-weight: bold; font-size: 7.5pt; letter-spacing: 1.5pt; margin: 0 0 5pt; }
  .amber { color: #${AMBER}; } .mint { color: #${MINT}; } .navy { color: #${NAVY}; }
  .cap { margin: 20pt 0 8pt; }
  .grid { display: grid; grid-template-columns: 1fr 1fr; gap: 9pt; }
  .card { background: #${ICE_BG}; border-radius: 7pt; padding: 12pt 13pt; }
  .card .ct { font-family: Arial, sans-serif; font-weight: bold; font-size: 10.5pt; color: #${NAVY}; margin-bottom: 3pt; }
  .card .cb { font-size: 9.5pt; color: #${GREY}; line-height: 1.35; }
  .strips { display: grid; grid-template-columns: 1fr 1fr 1fr; gap: 9pt; margin-top: 12pt; }
  .strip { background: #${NAVY_D}; border-radius: 7pt; padding: 11pt 13pt; color: #D6E2F8; font-size: 9pt; line-height: 1.35; }
  .strip .st { font-family: Arial, sans-serif; font-weight: bold; font-size: 10pt; color: #fff; margin-bottom: 3pt; }
  .foot { margin-top: 22pt; padding-top: 8pt; border-top: 1px solid #${RULE}; font-size: 7pt; color: #${MUTED}; line-height: 1.4; }
</style></head><body>
<div class="hdr"><div class="l"><img src="${iconUri}" alt=""><div><div class="t">Access Approval Tool for Omnissa</div><div class="k">PRODUCT BRIEF</div></div></div>
<div class="r">Self-hosted · MIT License<br>github.com/SquidlyMan/Omnissa-Access-Approvals</div></div>
<h1>Human approval for Omnissa Access,<br>without waiting on a human being online.</h1>
<div class="cols"><div><div class="kick amber">THE PROBLEM</div>Omnissa Access can require license approval before a user gets an app — but every request still needs a person to notice it, open the console, and decide. Approvers travel, sleep, and go OOO. Requests wait.</div>
<div><div class="kick mint">THE SOLUTION</div>A self-hosted gateway sits between the request and the decision: it receives the callout, puts it in a live queue, and lets rules — or a person from a phone, Slack, or Teams — approve it in seconds.</div></div>
<div class="kick navy cap">KEY CAPABILITIES</div>
<div class="grid">${cards.map(([t, b]) => `<div class="card"><div class="ct">${esc(t)}</div><div class="cb">${esc(b)}</div></div>`).join('')}</div>
<div class="strips">${strips.map(([t, b]) => `<div class="strip"><div class="st">${esc(t)}</div>${esc(b)}</div>`).join('')}</div>
<div class="foot">Independent community project — not an Omnissa product, and not affiliated with, endorsed by, or supported by Omnissa, LLC. Provided as-is, for testing, lab, and demo use only — not production. MIT License · © 2026 Dean Flaming · v${VERSION}</div>
</body></html>`;

fs.mkdirSync('out', { recursive: true });
fs.writeFileSync('out/brief.html', html);
Packer.toBuffer(doc).then(buf => { fs.writeFileSync('out/brief.docx', buf); console.log('wrote out/brief.docx and out/brief.html (v' + VERSION + ')'); });
