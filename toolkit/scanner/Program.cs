using System;
using System.IO;
using System.Windows.Forms;

namespace StaffToolkitScan
{
    static class Program
    {
        [STAThread]
        static int Main(string[] args)
        {
            // Build check: "--test out.json" runs a scan without the window and saves the result
            if (args.Length == 2 && args[0] == "--test")
            {
                var r = new Scanner().Run(Scanner.IsAdmin());
                var ms = (long)(DateTime.UtcNow - new DateTime(1970, 1, 1)).TotalMilliseconds;
                File.WriteAllText(args[1], "{\"url\":\"scan\",\"para\":0,\"title\":\"Scan " + r.Id + "\",\"updated\":" + ms + ",\"device\":\"scanner\",\"report\":" + r.ToJson() + "}");
                File.WriteAllText(Path.ChangeExtension(args[1], ".txt"), r.ToText());
                return 0;
            }
            Application.EnableVisualStyles();
            Application.SetCompatibleTextRenderingDefault(false);
            Application.Run(new MainForm(Scanner.IsAdmin()));
            return 0;
        }
    }
}
