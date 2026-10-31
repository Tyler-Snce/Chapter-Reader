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
                File.WriteAllText(args[1], r.ToJson());
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
