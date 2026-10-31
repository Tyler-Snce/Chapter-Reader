using System;
using System.Diagnostics;
using System.Drawing;
using System.IO;
using System.Linq;
using System.Text.RegularExpressions;
using System.Threading.Tasks;
using System.Windows.Forms;

namespace StaffToolkitScan
{
    /// <summary>The scanner window: explains what it does, shows the results, and lets the player send them to staff.</summary>
    public class MainForm : Form
    {
        // Same database as Staff Toolkit; reports are picked up by the staff member's app and then deleted
        const string Db = "https://chapter-reader-default-rtdb.europe-west1.firebasedatabase.app";

        static readonly Color Bg = Color.FromArgb(17, 19, 23), Panel = Color.FromArgb(25, 28, 34), Card = Color.FromArgb(32, 36, 44);
        static readonly Color Fg = Color.FromArgb(243, 245, 249), Muted = Color.FromArgb(138, 146, 162), Accent = Color.FromArgb(63, 182, 139);
        static readonly Color Red = Color.FromArgb(255, 120, 128), Orange = Color.FromArgb(240, 180, 41);

        readonly Label status = new Label(), idLabel = new Label(), counts = new Label();
        readonly RichTextBox results = new RichTextBox();
        readonly TextBox code = new TextBox();
        readonly Button uploadBtn, copyBtn, saveBtn, deepBtn, rescanBtn;
        Report report;
        readonly bool admin;

        public MainForm(bool admin)
        {
            this.admin = admin;
            Text = "Staff Toolkit Scan" + (admin ? " (administrator)" : "");
            BackColor = Bg; ForeColor = Fg;
            Font = new Font("Segoe UI", 10f);
            Size = new Size(900, 720); MinimumSize = new Size(700, 520);
            StartPosition = FormStartPosition.CenterScreen;
            try { Icon = Icon.ExtractAssociatedIcon(Application.ExecutablePath); } catch { }

            var top = new Panel { Dock = DockStyle.Top, Height = 118, BackColor = Panel, Padding = new Padding(16, 12, 16, 8) };
            var title = new Label { Text = "Staff Toolkit Scan", Font = new Font("Segoe UI Semibold", 15f), AutoSize = true, Location = new Point(16, 10), ForeColor = Fg };
            var about = new Label
            {
                Text = "Checks this PC for Minecraft cheats (Java and Bedrock): what's attached to the game, mods, running programs and recent activity. "
                     + "It only looks – nothing is changed or deleted, and nothing is sent unless you press Upload.",
                ForeColor = Muted, Location = new Point(16, 44), Size = new Size(840, 40), Anchor = AnchorStyles.Left | AnchorStyles.Right | AnchorStyles.Top,
            };
            status.ForeColor = Accent; status.AutoSize = true; status.Location = new Point(16, 88);
            top.Controls.AddRange(new Control[] { title, about, status });

            var bottom = new Panel { Dock = DockStyle.Bottom, Height = 132, BackColor = Panel, Padding = new Padding(16, 10, 16, 10) };
            var idCaption = new Label { Text = "Report ID (staff check this matches)", ForeColor = Muted, AutoSize = true, Location = new Point(16, 10) };
            idLabel.Font = new Font("Consolas", 22f, FontStyle.Bold); idLabel.ForeColor = Fg; idLabel.AutoSize = true; idLabel.Location = new Point(12, 30); idLabel.Text = "…";
            counts.AutoSize = true; counts.Location = new Point(16, 76); counts.ForeColor = Muted;
            var codeCaption = new Label { Text = "Code from staff", ForeColor = Muted, AutoSize = true, Location = new Point(380, 10) };
            code.Location = new Point(380, 32); code.Width = 150; code.Font = new Font("Consolas", 16f); code.CharacterCasing = CharacterCasing.Upper;
            code.BackColor = Color.FromArgb(18, 21, 26); code.ForeColor = Fg; code.BorderStyle = BorderStyle.FixedSingle; code.MaxLength = 9;
            uploadBtn = MakeButton("Upload to staff", 540, 31, 150, true);
            copyBtn = MakeButton("Copy report", 380, 78, 110, false);
            saveBtn = MakeButton("Save report", 496, 78, 110, false);
            deepBtn = MakeButton(admin ? "Deep check on" : "Deep check (admin)", 612, 78, 150, false);
            rescanBtn = MakeButton("Scan again", 700, 31, 110, false);
            deepBtn.Enabled = !admin;
            bottom.Controls.AddRange(new Control[] { idCaption, idLabel, counts, codeCaption, code, uploadBtn, copyBtn, saveBtn, deepBtn, rescanBtn });

            results.Dock = DockStyle.Fill; results.ReadOnly = true; results.BorderStyle = BorderStyle.None;
            results.BackColor = Bg; results.ForeColor = Fg; results.Font = new Font("Segoe UI", 10f); results.DetectUrls = false;
            var wrap = new Panel { Dock = DockStyle.Fill, Padding = new Padding(16, 10, 16, 10), BackColor = Bg };
            wrap.Controls.Add(results);

            Controls.Add(wrap); Controls.Add(bottom); Controls.Add(top);

            uploadBtn.Click += async (s, e) => await Upload();
            copyBtn.Click += (s, e) => { if (report != null) { Clipboard.SetText(report.ToText()); status.Text = "Report copied – paste it to staff"; } };
            saveBtn.Click += (s, e) => SaveReport();
            deepBtn.Click += (s, e) => RestartAsAdmin();
            rescanBtn.Click += async (s, e) => await RunScan();
            Shown += async (s, e) => await RunScan();
        }

        Button MakeButton(string text, int x, int y, int w, bool primary)
        {
            var b = new Button
            {
                Text = text, Location = new Point(x, y), Size = new Size(w, 36), FlatStyle = FlatStyle.Flat,
                BackColor = primary ? Accent : Card, ForeColor = primary ? Color.FromArgb(6, 20, 14) : Fg, Cursor = Cursors.Hand,
            };
            b.FlatAppearance.BorderSize = 0;
            return b;
        }

        async Task RunScan()
        {
            foreach (var b in new[] { uploadBtn, copyBtn, saveBtn, rescanBtn }) b.Enabled = false;
            results.Clear();
            idLabel.Text = "…";
            counts.Text = "";
            var sc = new Scanner();
            sc.Progress = msg => BeginInvoke((Action)(() => status.Text = msg));
            report = await Task.Run(() => sc.Run(admin));
            ShowReport(report);
            foreach (var b in new[] { uploadBtn, copyBtn, saveBtn, rescanBtn }) b.Enabled = true;
            status.Text = "Scan finished at " + report.Time.ToString("HH:mm:ss") + (admin ? "" : ". For a fuller check, staff may ask you to press Deep check.");
        }

        void ShowReport(Report r)
        {
            idLabel.Text = r.Id;
            counts.Text = r.Count(Level.Serious) + " serious  ·  " + r.Count(Level.Suspicious) + " suspicious  ·  " + r.Count(Level.Info) + " info";
            results.Clear();
            Write("Games running: ", Muted, false); Write(r.Games.Count > 0 ? string.Join("; ", r.Games) : "none", Fg, true);
            if (r.Accounts.Count > 0) { Write("Minecraft accounts: ", Muted, false); Write(string.Join(", ", r.Accounts), Fg, true); }
            foreach (var lvl in new[] { Level.Serious, Level.Suspicious, Level.Info })
            {
                var list = r.Findings.Where(f => f.Level == lvl).ToList();
                if (list.Count == 0) continue;
                var color = lvl == Level.Serious ? Red : lvl == Level.Suspicious ? Orange : Muted;
                Write("\n" + (lvl == Level.Serious ? "● SERIOUS" : lvl == Level.Suspicious ? "● SUSPICIOUS" : "● INFO") + "  (" + list.Count + ")", color, true, true);
                foreach (var f in list)
                {
                    Write("[" + f.Area + "]  ", Muted, false);
                    Write(f.Title, lvl == Level.Info ? Fg : color, true);
                    if (!string.IsNullOrEmpty(f.Detail)) Write("      " + f.Detail.Replace("\n", "\n      "), Muted, true);
                }
            }
            if (r.Count(Level.Serious) == 0 && r.Count(Level.Suspicious) == 0) Write("\nNothing serious or suspicious was found.", Accent, true, true);
            results.SelectionStart = 0; results.ScrollToCaret();
        }

        void Write(string text, Color c, bool newline, bool bold = false)
        {
            results.SelectionStart = results.TextLength;
            results.SelectionColor = c;
            results.SelectionFont = new Font(results.Font, bold ? FontStyle.Bold : FontStyle.Regular);
            results.AppendText(text + (newline ? "\n" : ""));
        }

        async Task Upload()
        {
            if (report == null) return;
            var c = Regex.Replace(code.Text.ToUpperInvariant(), "[^A-Z0-9]", "");
            if (c.Length != 6) { status.ForeColor = Orange; status.Text = "Type the 6-character code the staff member gives you (e.g. K7P-4QX)"; code.Focus(); return; }
            uploadBtn.Enabled = false;
            status.ForeColor = Accent; status.Text = "Sending…";
            try
            {
                var json = report.ToJson();
                await Task.Run(() => Scanner.Http("PUT", Db + "/progress/ss-" + c + ".json", json));
                status.Text = "Sent to staff ✓  (report " + report.Id + ")";
            }
            catch (Exception e)
            {
                status.ForeColor = Orange;
                status.Text = "Couldn't send (" + e.Message + "). Use Copy report and paste it to staff instead.";
                uploadBtn.Enabled = true;
            }
        }

        void SaveReport()
        {
            if (report == null) return;
            using (var d = new SaveFileDialog { FileName = "Scan report " + report.Id + ".txt", Filter = "Text|*.txt" })
                if (d.ShowDialog(this) == DialogResult.OK) { File.WriteAllText(d.FileName, report.ToText()); status.Text = "Saved"; }
        }

        void RestartAsAdmin()
        {
            try
            {
                Process.Start(new ProcessStartInfo(Application.ExecutablePath, "--deep") { Verb = "runas", UseShellExecute = true });
                Close();
            }
            catch { status.ForeColor = Orange; status.Text = "The deep check needs you to press Yes on the Windows prompt."; }
        }
    }
}
