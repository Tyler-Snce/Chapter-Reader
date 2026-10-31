// Staff Toolkit Scan: checks a PC for signs of Minecraft cheats (Java and Windows Bedrock).
// It only looks; it doesn't change or delete anything, and nothing is sent unless the player presses Upload.
using System;
using System.Collections.Generic;
using System.Diagnostics;
using System.IO;
using System.IO.Compression;
using System.Linq;
using System.Management;
using System.Net;
using System.Security.Cryptography;
using System.Security.Cryptography.X509Certificates;
using System.Security.Principal;
using System.Text;
using System.Text.RegularExpressions;
using System.Web.Script.Serialization;
using Microsoft.Win32;

namespace StaffToolkitScan
{
    public enum Level { Serious = 0, Suspicious = 1, Info = 2 }

    public class Finding
    {
        public Level Level;
        public string Area;
        public string Title;
        public string Detail;
    }

    public class Report
    {
        public string Id;
        public DateTime Time = DateTime.Now;
        public bool Admin;
        public List<string> Accounts = new List<string>();
        public List<string> Games = new List<string>();
        public List<Finding> Findings = new List<Finding>();
        public string Computer = Environment.MachineName;
        public const string Version = "1.0";

        public int Count(Level l) => Findings.Count(f => f.Level == l);

        public string ToText()
        {
            var sb = new StringBuilder();
            sb.AppendLine("Staff Toolkit Scan " + Version + "  -  report " + Id);
            sb.AppendLine("Time: " + Time.ToString("yyyy-MM-dd HH:mm:ss") + (Admin ? "  (deep check, administrator)" : "  (standard check)"));
            if (Accounts.Count > 0) sb.AppendLine("Minecraft accounts: " + string.Join(", ", Accounts));
            sb.AppendLine("Games running: " + (Games.Count > 0 ? string.Join("; ", Games) : "none found"));
            sb.AppendLine("Serious: " + Count(Level.Serious) + "   Suspicious: " + Count(Level.Suspicious) + "   Info: " + Count(Level.Info));
            foreach (var lvl in new[] { Level.Serious, Level.Suspicious, Level.Info })
            {
                var list = Findings.Where(f => f.Level == lvl).ToList();
                if (list.Count == 0) continue;
                sb.AppendLine();
                sb.AppendLine("== " + lvl.ToString().ToUpper() + " ==");
                foreach (var f in list)
                {
                    sb.AppendLine("[" + f.Area + "] " + f.Title);
                    if (!string.IsNullOrEmpty(f.Detail)) foreach (var line in f.Detail.Split('\n')) sb.AppendLine("    " + line);
                }
            }
            return sb.ToString();
        }

        public string ToJson()
        {
            var o = new Dictionary<string, object>
            {
                ["id"] = Id, ["time"] = Time.ToString("o"), ["version"] = Version, ["admin"] = Admin, ["computer"] = Computer,
                ["accounts"] = Accounts, ["games"] = Games,
                ["counts"] = new Dictionary<string, int> { ["serious"] = Count(Level.Serious), ["suspicious"] = Count(Level.Suspicious), ["info"] = Count(Level.Info) },
                ["findings"] = Findings.Select(f => new Dictionary<string, string> { ["level"] = f.Level.ToString().ToLower(), ["area"] = f.Area, ["title"] = f.Title, ["detail"] = f.Detail ?? "" }).ToList(),
                ["text"] = ToText(),
            };
            return new JavaScriptSerializer { MaxJsonLength = int.MaxValue }.Serialize(o);
        }
    }

    public class Scanner
    {
        public Action<string> Progress = _ => { };
        readonly Report r = new Report();
        readonly HashSet<string> seenFiles = new HashSet<string>(StringComparer.OrdinalIgnoreCase);
        static readonly string Home = Environment.GetFolderPath(Environment.SpecialFolder.UserProfile);
        static readonly string AppData = Environment.GetFolderPath(Environment.SpecialFolder.ApplicationData);
        static readonly string LocalAppData = Environment.GetFolderPath(Environment.SpecialFolder.LocalApplicationData);
        static readonly string Docs = Environment.GetFolderPath(Environment.SpecialFolder.MyDocuments);
        static readonly string Win = Environment.GetFolderPath(Environment.SpecialFolder.Windows);

        // Known cheat clients and tools (names as they usually appear in files and processes)
        static readonly string[] CheatNames = {
            "wurst", "meteor-client", "meteorclient", "aristois", "impact client", "liquidbounce", "rusherhack", "future client", "futureclient",
            "inertia", "sigma", "vape", "raven b", "ravenb", "raven-b", "drip lite", "dripclient", "slinky", "entropy client", "doomsday",
            "novoline", "rise client", "riseclient", "tenacity", "fdpclient", "wolfram", "kami blue", "kamiblue", "salhack", "konas", "phobos",
            "boze", "thunderhack", "bleachhack", "coffee client", "prestige", "akrien", "whiteout", "cheatbreaker-hack", "astolfo", "exhibition",
            "horion", "zephyr client", "packet client", "nitr0", "ambrosial", "lakeside", "badman", "fate client", "crystalware", "elixir",
            "killaura", "autoclicker", "auto clicker", "auto-clicker", "triggerbot", "aimassist", "aim assist", "ghostclient", "ghost client", "injector",
        };
        // Words inside a mod's files that point to cheat features
        static readonly string[] CheatClassWords = {
            "killaura", "aimassist", "aimbot", "triggerbot", "autoclicker", "reach", "velocity", "antiknockback", "scaffold", "nofall", "fly",
            "xray", "esp", "tracers", "freecam", "autototem", "crystalaura", "nuker", "speedhack", "blink", "fastplace", "autoclick", "hitbox",
        };
        static readonly string[] Injectors = { "extreme injector", "xenos", "process hacker", "systeminformer", "cheat engine", "cheatengine", "gh injector", "dll injector", "injector" };
        static readonly string[] Clickers = { "op auto clicker", "opautoclicker", "gs auto clicker", "autoclicker", "auto clicker", "auto-clicker", "speedautoclicker", "jitterclick", "dripclicker", "ghostclicker" };
        static readonly string[] Macros = { "autohotkey", "tinytask", "macro recorder", "jitbit", "pulover", "macro" };
        // Bedrock clients many servers allow (only reported as information)
        static readonly string[] BedrockPvpClients = { "onix", "flarial", "latite", "lunar" };

        public Report Run(bool admin)
        {
            r.Id = NewId();
            r.Admin = admin;
            Step("Looking at running games", ScanGames);
            Step("Checking mods and client folders", ScanFolders);
            Step("Checking running programs", ScanProcesses);
            Step("Looking for hidden or disguised programs", ScanFiles);
            Step("Checking recent activity", ScanRecent);
            Step("Looking for signs of cleaning", ScanCleaning);
            if (admin) Step("Deep check (administrator)", ScanDeep);
            Step("Finding Minecraft accounts", ScanAccounts);
            r.Findings = r.Findings.OrderBy(f => f.Level).ToList();
            Progress("Done");
            return r;
        }

        void Step(string label, Action a)
        {
            Progress(label + "\u2026");
            try { a(); } catch (Exception e) { Add(Level.Info, "Scanner", "Part of the check couldn't run: " + label, e.Message); }
        }

        void Add(Level l, string area, string title, string detail = null)
        {
            if (r.Findings.Any(f => f.Title == title && f.Area == area)) return;
            r.Findings.Add(new Finding { Level = l, Area = area, Title = title, Detail = detail });
        }

        static string NewId()
        {
            const string chars = "ABCDEFGHJKLMNPQRSTUVWXYZ23456789";
            var b = new byte[8];
            using (var rng = RandomNumberGenerator.Create()) rng.GetBytes(b);
            return new string(b.Select(x => chars[x % chars.Length]).ToArray()).Insert(4, "-");
        }

        static string CheatMatch(string text)
        {
            var t = (text ?? "").ToLowerInvariant().Replace('_', ' ');
            foreach (var n in CheatNames)
                if (Regex.IsMatch(t, "(^|[^a-z])" + Regex.Escape(n) + "([^a-z]|$)")) return n;
            return null;
        }

        static string Ago(DateTime t)
        {
            var d = DateTime.Now - t;
            if (d.TotalMinutes < 1) return "just now";
            if (d.TotalHours < 1) return (int)d.TotalMinutes + " min ago";
            if (d.TotalDays < 1) return (int)d.TotalHours + " h ago";
            return (int)d.TotalDays + " days ago (" + t.ToString("dd MMM HH:mm") + ")";
        }

        static string Short(string p) => (p ?? "").Replace(Home, "%USERPROFILE%");

        // ---------- running games ----------

        class GameProc { public Process P; public string Cmd; public bool Java; public string Client; }

        static Dictionary<int, string> CommandLines()
        {
            var map = new Dictionary<int, string>();
            using (var s = new ManagementObjectSearcher("SELECT ProcessId, CommandLine FROM Win32_Process"))
                foreach (ManagementObject o in s.Get())
                    map[Convert.ToInt32(o["ProcessId"])] = (o["CommandLine"] as string) ?? "";
            return map;
        }

        List<GameProc> FindGames()
        {
            var cmds = CommandLines();
            var list = new List<GameProc>();
            foreach (var p in Process.GetProcesses())
            {
                string name;
                try { name = p.ProcessName.ToLowerInvariant(); } catch { continue; }
                cmds.TryGetValue(p.Id, out var cmd);
                cmd = cmd ?? "";
                var lc = cmd.ToLowerInvariant();
                if ((name == "javaw" || name == "java") && Regex.IsMatch(lc, "minecraft|lunar|badlion|feather|fabric|forge|cpw\\.mods|--gamedir|--assetsdir"))
                {
                    var client = lc.Contains("lunar") ? "Lunar Client" : lc.Contains("feather") ? "Feather" : lc.Contains("badlion") ? "Badlion"
                        : lc.Contains("fabric") ? "Fabric" : (lc.Contains("forge") || lc.Contains("cpw.mods")) ? "Forge" : "Minecraft Java";
                    list.Add(new GameProc { P = p, Cmd = cmd, Java = true, Client = client });
                }
                else if (name == "minecraft.windows")
                    list.Add(new GameProc { P = p, Cmd = cmd, Java = false, Client = "Minecraft Bedrock (Windows)" });
            }
            return list;
        }

        void ScanGames()
        {
            var games = FindGames();
            if (games.Count == 0)
            {
                Add(Level.Suspicious, "Game", "Minecraft isn't running", "Ask the player to have the game open (as they were playing) and scan again.");
                return;
            }
            foreach (var g in games)
            {
                string started = "";
                try { started = g.P.StartTime.ToString("HH:mm"); } catch { }
                var ver = Regex.Match(g.Cmd, "--version\\s+\"?([^\"\\s]+)").Groups[1].Value;
                var user = Regex.Match(g.Cmd, "--username\\s+\"?([^\"\\s]+)").Groups[1].Value;
                if (user.Length > 0 && !r.Accounts.Contains(user)) r.Accounts.Add(user);
                r.Games.Add(g.Client + (ver.Length > 0 ? " " + ver : "") + (started.Length > 0 ? ", started " + started : ""));
                if (g.Java) CheckJvmArgs(g);
                CheckModules(g);
            }
        }

        void CheckJvmArgs(GameProc g)
        {
            var cmd = g.Cmd;
            foreach (Match m in Regex.Matches(cmd, "-javaagent:(\"[^\"]+\"|[^\\s]+)"))
            {
                var path = m.Groups[1].Value.Trim('"');
                var jar = path.Split('=')[0];
                var inClient = Regex.IsMatch(jar.ToLowerInvariant(), "\\\\\\.lunarclient\\\\|\\\\feather|\\\\badlion|\\\\\\.minecraft\\\\|\\\\prismlauncher\\\\|\\\\modrinthapp\\\\");
                var cheat = CheatMatch(Path.GetFileName(jar));
                var exists = File.Exists(jar);
                if (cheat != null) Add(Level.Serious, "Game", "A cheat is attached to Minecraft as a Java agent (" + cheat + ")", Short(jar));
                else if (!inClient) Add(Level.Suspicious, "Game", "Extra code attached to Minecraft (Java agent): " + Path.GetFileName(jar),
                    Short(jar) + (exists ? "" : "\nThe file no longer exists (deleted while the game is running)."));
                else Add(Level.Info, "Game", "Client's own Java agent: " + Path.GetFileName(jar), Short(jar));
                if (exists) InspectJar(jar, "Game");
            }
            if (Regex.IsMatch(cmd, "-agentpath:|-agentlib:(?!jdwp)")) Add(Level.Suspicious, "Game", "Native code attached to Minecraft at start-up (-agentpath / -agentlib)", Regex.Match(cmd, "-agent(path|lib):[^\\s]+").Value);
            if (cmd.Contains("-agentlib:jdwp")) Add(Level.Suspicious, "Game", "Minecraft was started with debugging switched on (used by some injectors)");
            if (cmd.Contains("-Xbootclasspath")) Add(Level.Suspicious, "Game", "Minecraft's core Java classes were changed at start-up (-Xbootclasspath)", Regex.Match(cmd, "-Xbootclasspath[^\\s]*").Value);
        }

        void CheckModules(GameProc g)
        {
            ProcessModuleCollection mods;
            try { mods = g.P.Modules; }
            catch (Exception e) { Add(Level.Info, "Game", "Couldn't look inside " + g.Client + " (" + e.Message + ")", "Running the deep check as administrator may help."); return; }
            string exeDir = "";
            try { exeDir = Path.GetDirectoryName(Path.GetDirectoryName(g.P.MainModule.FileName)) ?? ""; } catch { }
            var allowed = new List<string> { Win, exeDir, @"C:\Program Files\WindowsApps", Path.Combine(Home, ".lunarclient"), Path.Combine(LocalAppData, "Packages") };
            var knownVendors = new[] { "nvidia", "amd", "ati technologies", "intel", "microsoft", "discord", "valve", "steam", "obs", "overwolf", "rivatuner", "msi", "razer", "logitech",
                "medal", "corsair", "steelseries", "asus", "nahimic", "oracle", "eclipse", "azul", "adoptium", "lunar", "feather", "badlion", "mojang", "realtek", "elgato", "streamlabs", "xsplit", "geforce", "radeon" };
            int odd = 0;
            foreach (ProcessModule m in mods)
            {
                string path;
                try { path = m.FileName; } catch { continue; }
                if (allowed.Any(a => a.Length > 0 && path.StartsWith(a, StringComparison.OrdinalIgnoreCase))) continue;
                var signer = Signer(path);
                var lp = path.ToLowerInvariant();
                var cheat = CheatMatch(Path.GetFileName(path));
                var risky = lp.Contains(@"\temp\") || lp.Contains(@"\downloads\") || lp.Contains(@"\desktop\") || lp.StartsWith(AppData.ToLowerInvariant()) || lp.StartsWith(LocalAppData.ToLowerInvariant());
                if (cheat != null) { Add(Level.Serious, "Game", "Cheat file loaded into " + g.Client + ": " + Path.GetFileName(path), Short(path)); odd++; }
                else if (signer == null && risky) { Add(Level.Serious, "Game", "Unsigned DLL from a user folder loaded into " + g.Client + ": " + Path.GetFileName(path), Short(path) + "\nModified " + Ago(File.GetLastWriteTime(path))); odd++; }
                else if (signer == null) { Add(Level.Suspicious, "Game", "Unsigned DLL loaded into " + g.Client + ": " + Path.GetFileName(path), Short(path)); odd++; }
                else if (!knownVendors.Any(v => signer.ToLowerInvariant().Contains(v))) Add(Level.Info, "Game", "DLL from " + signer + " loaded into " + g.Client + ": " + Path.GetFileName(path), Short(path));
            }
            if (odd == 0) Add(Level.Info, "Game", "Nothing unusual loaded into " + g.Client);
        }

        static string Signer(string file)
        {
            try
            {
                var c = X509Certificate.CreateFromSignedFile(file);
                var m = Regex.Match(c.Subject, "O=\"?([^,\"]+)");
                return m.Success ? m.Groups[1].Value : c.Subject;
            }
            catch { return null; }
        }

        // ---------- mods and client folders ----------

        IEnumerable<string> ModFolders()
        {
            var roots = new List<string> {
                Path.Combine(AppData, ".minecraft", "mods"), Path.Combine(AppData, ".minecraft", "versions"),
                Path.Combine(Home, ".lunarclient", "offline"), Path.Combine(Home, ".lunarclient", "profiles"),
                Path.Combine(AppData, ".feather"), Path.Combine(AppData, ".minecraft", "feather"),
                Path.Combine(AppData, "PrismLauncher", "instances"), Path.Combine(AppData, "ModrinthApp", "profiles"),
                Path.Combine(Docs, "curseforge", "minecraft", "Instances"), Path.Combine(AppData, "com.modrinth.theseus", "profiles"),
                Path.Combine(AppData, ".minecraft", "libraries", "net", "minecraftforge"),
            };
            foreach (var d in Directory.GetDirectories(AppData).Concat(Directory.GetDirectories(Home)))
                if (Regex.IsMatch(Path.GetFileName(d), "orbit|tlauncher|labymod|badlion|gdlauncher|atlauncher|multimc", RegexOptions.IgnoreCase)) roots.Add(d);
            return roots.Where(Directory.Exists);
        }

        IEnumerable<string> FilesUnder(string root, string pattern, int max = 3000)
        {
            var stack = new Stack<string>();
            stack.Push(root);
            int n = 0;
            while (stack.Count > 0 && n < max)
            {
                var d = stack.Pop();
                string[] files = new string[0], dirs = new string[0];
                try { files = Directory.GetFiles(d, pattern); dirs = Directory.GetDirectories(d); } catch { }
                foreach (var f in files) { n++; yield return f; }
                foreach (var s in dirs)
                {
                    var name = Path.GetFileName(s).ToLowerInvariant();
                    if (name == "assets" || name == "natives" || name == "jre" || name == "runtime" || name == "cache" || name == "screenshots" || name == "saves" || name == "resourcepacks" || name == "shaderpacks") continue;
                    stack.Push(s);
                }
            }
        }

        readonly Dictionary<string, string> jarHashes = new Dictionary<string, string>();

        void ScanFolders()
        {
            int jars = 0;
            foreach (var root in ModFolders())
                foreach (var f in FilesUnder(root, "*.jar"))
                {
                    if (!seenFiles.Add(f)) continue;
                    jars++;
                    var name = Path.GetFileName(f);
                    var cheat = CheatMatch(name);
                    if (cheat != null) Add(Level.Serious, "Mods", "Cheat client file: " + name + " (" + cheat + ")", Short(f) + "\nModified " + Ago(File.GetLastWriteTime(f)));
                    var inMods = f.ToLowerInvariant().Contains(@"\mods\");
                    if (inMods && (DateTime.Now - File.GetLastWriteTime(f)).TotalHours < 2)
                        Add(Level.Suspicious, "Mods", "Mod changed in the last 2 hours: " + name, Short(f) + "\nModified " + Ago(File.GetLastWriteTime(f)));
                    if (inMods || cheat != null) InspectJar(f, "Mods");
                }
            LookupModrinth();
            ScanBedrockFolders();
            Add(Level.Info, "Mods", "Checked " + jars + " Java files in game and launcher folders");
        }

        /** Looks inside a .jar for cheat features, so renamed cheats are still caught. */
        void InspectJar(string file, string area)
        {
            try
            {
                using (var zip = ZipFile.OpenRead(file))
                {
                    var hits = new HashSet<string>();
                    var inModuleDirs = new HashSet<string>();
                    string modName = null;
                    // The clients' own game files are scrambled (thousands of tiny class names) and are
                    // known-good, so only mods the player added are checked for cheat features
                    var lf = file.ToLowerInvariant();
                    var official = Regex.IsMatch(lf, @"\\\.lunarclient\\(offline|jre|licenses|logs|textures)\\|\\badlion client\\|\\\.feather\\(libraries|game|jre)\\");
                    var classes = zip.Entries.Where(e => e.FullName.EndsWith(".class", StringComparison.OrdinalIgnoreCase)).ToList();
                    var shortNames = classes.Count(e => Path.GetFileNameWithoutExtension(e.Name).Length <= 3);
                    var scrambled = classes.Count > 200 && shortNames > classes.Count * 0.4;
                    foreach (var e in zip.Entries)
                    {
                        var n = e.FullName.ToLowerInvariant();
                        if (!official && n.EndsWith(".class"))
                        {
                            var leaf = Path.GetFileNameWithoutExtension(n).Split('$')[0];
                            var dir = Path.GetDirectoryName(n.Replace('/', '\\')) ?? "";
                            var moduleDir = Regex.IsMatch(dir, @"(^|\\)(modules?|features?|hacks?|cheats?|combat|movement)(\\|$)");
                            if (scrambled && !moduleDir) continue;
                            foreach (var w in CheatClassWords)
                            {
                                // Short words ("fly", "esp") only count when clearly labelled as a cheat module
                                var labelled = Regex.IsMatch(leaf, "^(module)?" + w + "(module|hack|cheat)$") || Regex.IsMatch(leaf, "^module" + w + "$");
                                var exact = leaf == w && (w.Length >= 6 || moduleDir);
                                if (labelled || exact) { hits.Add(w); if (moduleDir) inModuleDirs.Add(w); }
                            }
                        }
                        if (n.EndsWith("fabric.mod.json") || n.EndsWith("mods.toml") || n.EndsWith("mcmod.info"))
                        {
                            using (var sr = new StreamReader(e.Open()))
                            {
                                var txt = sr.ReadToEnd();
                                var m = Regex.Match(txt, "\"name\"\\s*:\\s*\"([^\"]+)\"|displayName\\s*=\\s*\"([^\"]+)\"");
                                if (m.Success) modName = m.Groups[1].Success && m.Groups[1].Value.Length > 0 ? m.Groups[1].Value : m.Groups[2].Value;
                                var c = CheatMatch(txt.Length > 4000 ? txt.Substring(0, 4000) : txt);
                                if (c != null) Add(Level.Serious, area, "Mod describes itself as a cheat client (" + c + "): " + Path.GetFileName(file), Short(file));
                            }
                        }
                    }
                    // Several cheat modules grouped together is a strong sign; one or two on their own are often normal mods
                    if (hits.Count >= 3 && inModuleDirs.Count >= 2) Add(Level.Serious, area, "Mod contains cheat features: " + (modName ?? Path.GetFileName(file)), Short(file) + "\nFeatures: " + string.Join(", ", hits));
                    else if (hits.Count >= 2) Add(Level.Suspicious, area, "Mod contains possible cheat features: " + (modName ?? Path.GetFileName(file)), Short(file) + "\nFeatures: " + string.Join(", ", hits));
                }
                using (var sha = SHA1.Create())
                using (var s = File.OpenRead(file))
                    jarHashes[BitConverter.ToString(sha.ComputeHash(s)).Replace("-", "").ToLowerInvariant()] = file;
            }
            catch (Exception e)
            {
                if (e is InvalidDataException) Add(Level.Suspicious, area, "A .jar that isn't a normal Java file (possibly disguised): " + Path.GetFileName(file), Short(file));
            }
        }

        /** Asks Modrinth which mods these are, so staff see real names (renamed mods show their true name). */
        void LookupModrinth()
        {
            var mods = jarHashes.Where(k => k.Value.ToLowerInvariant().Contains(@"\mods\")).ToList();
            if (mods.Count == 0) return;
            try
            {
                var js = new JavaScriptSerializer { MaxJsonLength = int.MaxValue };
                var body = js.Serialize(new Dictionary<string, object> { ["hashes"] = mods.Select(m => m.Key).ToList(), ["algorithm"] = "sha1" });
                var res = Http("POST", "https://api.modrinth.com/v2/version_files", body);
                var map = js.Deserialize<Dictionary<string, Dictionary<string, object>>>(res);
                var known = new List<string>();
                var unknown = new List<string>();
                foreach (var m in mods)
                {
                    var file = Path.GetFileName(m.Value);
                    if (map != null && map.TryGetValue(m.Key, out var v))
                    {
                        var vname = v.ContainsKey("name") ? v["name"] as string : null;
                        known.Add(file + (vname != null && !file.ToLowerInvariant().Contains((vname.Split(' ')[0] ?? "").ToLowerInvariant()) ? "  (really: " + vname + ")" : ""));
                    }
                    else unknown.Add(file);
                }
                if (known.Count > 0) Add(Level.Info, "Mods", known.Count + " mods matched known Modrinth mods", string.Join("\n", known));
                if (unknown.Count > 0) Add(Level.Info, "Mods", unknown.Count + " mods aren't on Modrinth (could be CurseForge mods, or custom/cheat files: check these)", string.Join("\n", unknown));
            }
            catch (Exception e) { Add(Level.Info, "Mods", "Couldn't look mods up online (" + e.Message + ")"); }
        }

        void ScanBedrockFolders()
        {
            var pkg = Path.Combine(LocalAppData, "Packages", "Microsoft.MinecraftUWP_8wekyb3d8bbwe");
            var roaming = Path.Combine(pkg, "RoamingState");
            var places = new List<string> { roaming, Path.Combine(AppData, "Minecraft Bedrock"), Path.Combine(LocalAppData, "Packages", "Microsoft.MinecraftUWP_8wekyb3d8bbwe", "LocalState") };
            foreach (var d in places.Where(Directory.Exists))
            {
                foreach (var sub in SafeDirs(d))
                {
                    var n = Path.GetFileName(sub);
                    var cheat = CheatMatch(n);
                    if (cheat != null) Add(Level.Serious, "Bedrock", "Bedrock cheat client folder: " + n, Short(sub) + "\nModified " + Ago(Directory.GetLastWriteTime(sub)));
                    else if (BedrockPvpClients.Any(c => n.ToLowerInvariant().Contains(c))) Add(Level.Info, "Bedrock", "Bedrock PvP client installed: " + n + " (allowed on many servers)", Short(sub));
                }
                foreach (var f in FilesUnder(d, "*.dll", 500))
                {
                    var cheat = CheatMatch(Path.GetFileName(f));
                    if (cheat != null) Add(Level.Serious, "Bedrock", "Bedrock cheat DLL: " + Path.GetFileName(f), Short(f));
                    else if (Signer(f) == null) Add(Level.Suspicious, "Bedrock", "Unsigned DLL in a Bedrock folder: " + Path.GetFileName(f), Short(f) + "\nModified " + Ago(File.GetLastWriteTime(f)));
                }
            }
        }

        static IEnumerable<string> SafeDirs(string d) { try { return Directory.GetDirectories(d); } catch { return new string[0]; } }

        // ---------- hidden or disguised programs ----------
        // Looks at what files really are, not what they're called: a cheat renamed to "MC ADDON"
        // or "notes.txt" is still a program, and Java injectors contain the code to hook a running game.

        static readonly HashSet<string> ProgramExts = new HashSet<string>(StringComparer.OrdinalIgnoreCase) {
            ".exe", ".dll", ".sys", ".ocx", ".cpl", ".scr", ".com", ".efi", ".mui", ".ax", ".drv", ".node", ".pyd", ".winmd", ".tlb",
            ".bin", ".dat", ".tmp", ".msi", ".msp", ".mun", ".acm", ".tsp", ".ime", ".rll", ".fon", ".olb", ".xll", ".asi", ".api",
            ".bpl", ".vst3", ".aex", ".8bf", ".flt", ".old", ".bak", ".partial", ".crdownload", ".download", ".pdb" };
        static readonly string[] PackerSections = { ".vmp0", ".vmp1", ".vmp2", ".themida", ".winlice", ".enigma1", ".enigma2", ".obsidium", ".boot\0" };

        IEnumerable<Tuple<string, int>> FileRoots()
        {
            var list = new List<Tuple<string, int>>();
            foreach (var d in new[] { "Desktop", "Downloads", "Documents", "Videos", "Pictures", "Music" }) list.Add(Tuple.Create(Path.Combine(Home, d), 6));
            foreach (var d in SafeDirs(Home)) if (Path.GetFileName(d).StartsWith("OneDrive", StringComparison.OrdinalIgnoreCase)) list.Add(Tuple.Create(d, 6));
            list.Add(Tuple.Create(Home, 1));
            list.Add(Tuple.Create(AppData, 4));
            list.Add(Tuple.Create(LocalAppData, 3));
            list.Add(Tuple.Create(Path.GetTempPath(), 2));
            foreach (var drive in DriveInfo.GetDrives().Where(x => x.DriveType == DriveType.Fixed || x.DriveType == DriveType.Removable))
                list.Add(Tuple.Create(drive.RootDirectory.FullName, drive.RootDirectory.FullName.StartsWith(Path.GetPathRoot(Win), StringComparison.OrdinalIgnoreCase) ? 2 : 3));
            return list.Where(t => Directory.Exists(t.Item1));
        }

        static readonly Regex SkipDirs = new Regex(@"^(windows|program files( \(x86\))?|programdata|\$recycle\.bin|system volume information|steamapps|steamlibrary|xboxgames|windowsapps|node_modules|\.git|packages|microsoft|google|mozilla|nvidia|amd|intel|code|npm|npm-cache|pip|\.nuget|\.gradle|\.m2|\.vscode|discord|spotify|steam|epic games|riot games|battle\.net|ubisoft|ea games|origin|jetbrains|adobe|zoom|teams|slack|obs-studio|crashdumps|d3dscache|cache|caches|temp\\low)$", RegexOptions.IgnoreCase);

        void ScanFiles()
        {
            var watch = Stopwatch.StartNew();
            int looked = 0;
            var visited = new HashSet<string>(StringComparer.OrdinalIgnoreCase);
            foreach (var root in FileRoots())
            {
                var stack = new Stack<Tuple<string, int>>();
                stack.Push(Tuple.Create(root.Item1, 0));
                while (stack.Count > 0)
                {
                    if (looked % 500 == 0) Progress("Looking for hidden or disguised programs\u2026 (" + looked + " files)");
                    if (watch.Elapsed.TotalSeconds > 90) { ListUnsigned(); Add(Level.Info, "Files", "The file check stopped after 90 seconds (" + looked + " files checked)", "There are a lot of files; the most likely places were checked first."); return; }
                    var cur = stack.Pop();
                    if (!visited.Add(cur.Item1)) continue;
                    string[] files = new string[0], dirs = new string[0];
                    try { files = Directory.GetFiles(cur.Item1); } catch { }
                    if (cur.Item2 < root.Item2) try { dirs = Directory.GetDirectories(cur.Item1); } catch { }
                    foreach (var f in files) { looked++; CheckFile(f); }
                    foreach (var d in dirs)
                    {
                        var name = Path.GetFileName(d);
                        if (SkipDirs.IsMatch(name)) continue;
                        try { if ((File.GetAttributes(d) & FileAttributes.ReparsePoint) != 0) continue; } catch { continue; }
                        stack.Push(Tuple.Create(d, cur.Item2 + 1));
                    }
                }
            }
            ListUnsigned();
            Add(Level.Info, "Files", "Checked " + looked + " files in user folders and drives for hidden programs");
        }

        readonly List<string> unsignedPrograms = new List<string>();

        void ListUnsigned()
        {
            if (unsignedPrograms.Count == 0) return;
            Add(Level.Info, "Files", unsignedPrograms.Count + " programs in your own folders have no publisher (staff: ask about any you don't recognise)",
                string.Join("\n", unsignedPrograms.Take(60)) + (unsignedPrograms.Count > 60 ? "\n\u2026and " + (unsignedPrograms.Count - 60) + " more" : ""));
        }

        void CheckFile(string f)
        {
            long size;
            try { size = new FileInfo(f).Length; } catch { return; }
            if (size < 1024 || size > 300L * 1024 * 1024) return;
            var ext = Path.GetExtension(f);
            var head = new byte[4];
            try { using (var fs = new FileStream(f, FileMode.Open, FileAccess.Read, FileShare.ReadWrite)) if (fs.Read(head, 0, 4) < 4) return; } catch { return; }
            var isProgram = head[0] == 'M' && head[1] == 'Z';
            var isZip = head[0] == 'P' && head[1] == 'K' && head[2] == 3 && head[3] == 4;
            var lf = f.ToLowerInvariant();
            var userFolder = Regex.IsMatch(lf, @"\\(desktop|downloads|documents|videos|pictures|music|onedrive[^\\]*)\\");
            var when = "Modified " + Ago(File.GetLastWriteTime(f)) + ", " + (size / 1024) + " KB";

            // Places cheats are usually kept; elsewhere (game and program installs) only names are checked
            var hotspot = userFolder || lf.StartsWith(AppData.ToLowerInvariant()) || lf.StartsWith(LocalAppData.ToLowerInvariant())
                || lf.StartsWith(Path.GetTempPath().ToLowerInvariant()) || Path.GetDirectoryName(f).Equals(Home, StringComparison.OrdinalIgnoreCase)
                || (Path.GetDirectoryName(Path.GetDirectoryName(f) ?? "") ?? "").Length <= 3;
            if (isProgram && ext.StartsWith(".exe", StringComparison.OrdinalIgnoreCase)) ext = ".exe";
            if (isProgram)
            {
                if (!ProgramExts.Contains(ext))
                {
                    // A program wearing a different name ("MC ADDON", "notes.txt", "pack.mcaddon"...)
                    var lvl = userFolder || ext.Length == 0 ? Level.Serious : Level.Suspicious;
                    Add(lvl, "Files", "A program disguised as " + (ext.Length == 0 ? "a file with no type" : "a " + ext + " file") + ": " + Path.GetFileName(f), Short(f) + "\n" + when);
                    InspectProgram(f, size, true);
                }
                else if (ext.Equals(".exe", StringComparison.OrdinalIgnoreCase) || ext.Equals(".dll", StringComparison.OrdinalIgnoreCase))
                {
                    var cheat = CheatMatch(Path.GetFileName(f));
                    if (cheat != null) Add(Level.Serious, "Files", "Cheat-related program: " + Path.GetFileName(f) + " (" + cheat + ")", Short(f) + "\n" + when);
                    var isExe = ext.Equals(".exe", StringComparison.OrdinalIgnoreCase);
                    if (hotspot && (isExe || userFolder)) InspectProgram(f, size, false);
                }
            }
            else if (isZip)
            {
                if (ext.Equals(".jar", StringComparison.OrdinalIgnoreCase)) { if (seenFiles.Add(f)) InspectJar(f, "Files"); }
                else if (!Regex.IsMatch(ext, @"^\.(zip|mcpack|mcaddon|mcworld|mctemplate|docx|xlsx|pptx|apk|aab|odt|ods|epub|nupkg|vsix|whl|xpi|crx|kmz|3mf|sketch|fig|ora|kra|zipx)$", RegexOptions.IgnoreCase) || Regex.IsMatch(ext, @"^\.(mcaddon|mcpack)$", RegexOptions.IgnoreCase))
                {
                    // A Java program (has .class files) hiding under another name
                    try
                    {
                        using (var zip = ZipFile.OpenRead(f))
                            if (zip.Entries.Any(e => e.FullName.EndsWith(".class", StringComparison.OrdinalIgnoreCase)))
                            {
                                Add(Level.Serious, "Files", "A Java program disguised as " + (ext.Length == 0 ? "a file with no type" : "a " + ext + " file") + ": " + Path.GetFileName(f), Short(f) + "\n" + when);
                                InspectJar(f, "Files");
                            }
                    }
                    catch { }
                }
            }
        }

        /** Looks inside a program for signs it's a Java cheat injector, or a protected unsigned loader. */
        void InspectProgram(string f, long size, bool disguised)
        {
            if (size > (disguised ? 60L : 20L) * 1024 * 1024) return;
            var signer = Signer(f);
            if (signer != null && !disguised) return;   // signed by a company: not a cheat loader
            // Every unsigned program in the player's own folders is listed, so a renamed cheat can't blend in
            if (signer == null && Regex.IsMatch(f.ToLowerInvariant(), @"\\(desktop|downloads|documents|videos|pictures|music|onedrive[^\\]*)\\") && f.EndsWith(".exe", StringComparison.OrdinalIgnoreCase))
                unsignedPrograms.Add(Short(f) + "   (" + File.GetLastWriteTime(f).ToString("dd MMM yyyy") + ", " + (size / 1024) + " KB)");
            byte[] bytes;
            try { using (var fs = new FileStream(f, FileMode.Open, FileAccess.Read, FileShare.ReadWrite)) { bytes = new byte[fs.Length]; int got = 0; while (got < bytes.Length) { var n = fs.Read(bytes, got, bytes.Length - got); if (n <= 0) break; got += n; } } }
            catch { return; }
            var latin = Encoding.GetEncoding(28591).GetString(bytes);
            var name = Path.GetFileName(f);
            var lf = f.ToLowerInvariant();
            var javaHome = Regex.IsMatch(lf, @"\\(java|jdk[^\\]*|jre[^\\]*|runtime|\.lunarclient|\.minecraft|feather|badlion)\\");
            var when = "Modified " + Ago(File.GetLastWriteTime(f)) + (signer != null ? ", signed by " + signer : ", not signed");

            // Known cheat names inside the file (also in UTF-16)
            foreach (var marker in new[] { "vape.gg", "Vape Lite", "VapeLite", "vapeclient", "Vape V4", "manthe.dev", "drip.gg", "dripclient", "slinky.gg", "entropy.club", "raven b+", "doomsday client", "liquidbounce", "horion" })
            {
                if (latin.IndexOf(marker, StringComparison.OrdinalIgnoreCase) >= 0 || latin.IndexOf(Utf16(marker), StringComparison.OrdinalIgnoreCase) >= 0)
                { Add(Level.Serious, "Files", "Cheat client found inside " + name + " (\u201c" + marker + "\u201d)", Short(f) + "\n" + when); return; }
            }
            // Code for attaching to an already-running Java game (how ghost clients like Vape inject)
            if (!javaHome && latin.Contains("JNI_GetCreatedJavaVMs"))
            { Add(Level.Serious, "Files", "Program built to attach to a running Java game: " + name, Short(f) + "\n" + when + "\nThis is how injected ghost clients work; normal programs very rarely do this."); return; }
            // Classic DLL injection toolkit aimed at Java
            if (latin.Contains("WriteProcessMemory") && latin.Contains("CreateRemoteThread") && (latin.IndexOf("javaw", StringComparison.OrdinalIgnoreCase) >= 0 || latin.IndexOf(Utf16("javaw"), StringComparison.OrdinalIgnoreCase) >= 0))
            { Add(Level.Serious, "Files", "Injector aimed at Java/Minecraft: " + name, Short(f) + "\n" + when); return; }
            // Wrapped in the copy-protection most cheat loaders use, and not signed by anyone
            if (signer == null && PackerSections.Any(sec => latin.IndexOf(sec, 0, Math.Min(latin.Length, 4096), StringComparison.Ordinal) >= 0))
                Add(Level.Suspicious, "Files", "Protected (packed) program with no publisher: " + name, Short(f) + "\n" + when + "\nCheat loaders are usually packed like this; some legitimate tools are too.");
        }

        static string Utf16(string s) => Encoding.GetEncoding(28591).GetString(Encoding.Unicode.GetBytes(s));

        // ---------- running programs ----------

        void ScanProcesses()
        {
            foreach (var p in Process.GetProcesses())
            {
                string name = "", title = "", path = "";
                try { name = p.ProcessName; title = p.MainWindowTitle; } catch { }
                try { path = p.MainModule.FileName; } catch { }
                var text = (name + " " + title + " " + Path.GetFileName(path)).ToLowerInvariant();
                var where = (path.Length > 0 ? Short(path) : name) + (title.Length > 0 ? "\nWindow: " + title : "");
                var cheat = CheatMatch(text);
                if (cheat != null) Add(Level.Serious, "Programs", "Cheat-related program running: " + name + " (" + cheat + ")", where);
                else if (Injectors.Any(i => text.Contains(i))) Add(Level.Serious, "Programs", "Injector / memory tool running: " + name, where);
                else if (Clickers.Any(i => text.Contains(i))) Add(Level.Serious, "Programs", "Autoclicker running: " + name, where);
                else if (Macros.Any(i => text.Contains(i))) Add(Level.Suspicious, "Programs", "Macro program running: " + name, where);
                if (path.Length > 0)
                {
                    var lp = path.ToLowerInvariant();
                    if ((lp.Contains(@"\temp\") || lp.Contains(@"\downloads\")) && Signer(path) == null)
                        Add(Level.Suspicious, "Programs", "Unsigned program running from " + (lp.Contains(@"\temp\") ? "Temp" : "Downloads") + ": " + name, Short(path));
                }
            }
        }

        // ---------- recent activity ----------

        void ScanRecent()
        {
            // Programs started from the Start menu / Explorer (Windows records these)
            try
            {
                using (var ua = Registry.CurrentUser.OpenSubKey(@"Software\Microsoft\Windows\CurrentVersion\Explorer\UserAssist"))
                    foreach (var guid in ua?.GetSubKeyNames() ?? new string[0])
                        using (var count = ua.OpenSubKey(guid + @"\Count"))
                            foreach (var v in count?.GetValueNames() ?? new string[0])
                            {
                                var nameDecoded = Rot13(v);
                                var data = count.GetValue(v) as byte[];
                                DateTime last = DateTime.MinValue;
                                if (data != null && data.Length >= 68) { var ft = BitConverter.ToInt64(data, 60); if (ft > 0) try { last = DateTime.FromFileTime(ft); } catch { } }
                                var cheat = CheatMatch(Path.GetFileName(nameDecoded));
                                if (cheat != null) Add(Level.Serious, "Recent", "Cheat-related program was run: " + Path.GetFileName(nameDecoded), Short(nameDecoded) + (last > DateTime.MinValue ? "\nLast run " + Ago(last) : ""));
                                else if ((Injectors.Concat(Clickers)).Any(i => nameDecoded.ToLowerInvariant().Contains(i)))
                                    Add(Level.Suspicious, "Recent", "Injector or clicker was run: " + Path.GetFileName(nameDecoded), Short(nameDecoded) + (last > DateTime.MinValue ? "\nLast run " + Ago(last) : ""));
                            }
            }
            catch { }

            // Recently opened files
            var recent = Path.Combine(AppData, "Microsoft", "Windows", "Recent");
            try
            {
                foreach (var lnk in Directory.GetFiles(recent, "*.lnk"))
                {
                    var n = Path.GetFileNameWithoutExtension(lnk);
                    var t = File.GetLastWriteTime(lnk);
                    var cheat = CheatMatch(n);
                    if (cheat != null) Add(Level.Serious, "Recent", "Recently opened a cheat-related file: " + n, "Opened " + Ago(t));
                    else if (Regex.IsMatch(n, "\\.(jar|dll|exe)$", RegexOptions.IgnoreCase) && (DateTime.Now - t).TotalDays < 2)
                        Add(Level.Info, "Recent", "Recently opened: " + n, "Opened " + Ago(t));
                }
            }
            catch { }

            // Downloads in the last week
            var dl = Path.Combine(Home, "Downloads");
            if (Directory.Exists(dl))
                foreach (var f in Directory.GetFiles(dl))
                {
                    var t = File.GetLastWriteTime(f);
                    if ((DateTime.Now - t).TotalDays > 7 || !Regex.IsMatch(f, "\\.(jar|dll|exe|zip|rar|7z)$", RegexOptions.IgnoreCase)) continue;
                    var n = Path.GetFileName(f);
                    var cheat = CheatMatch(n);
                    if (cheat != null) Add(Level.Serious, "Recent", "Cheat-related download: " + n, Short(f) + "\nDownloaded " + Ago(t));
                    else Add(Level.Info, "Recent", "Downloaded recently: " + n, "Downloaded " + Ago(t));
                    if (f.EndsWith(".jar", StringComparison.OrdinalIgnoreCase)) InspectJar(f, "Recent");
                }

            // Recycle Bin: deleted programs and mods
            foreach (var item in RecycleBin())
            {
                var n = Path.GetFileName(item.Item1);
                if (!Regex.IsMatch(n, "\\.(jar|dll|exe|zip|rar)$", RegexOptions.IgnoreCase)) continue;
                var cheat = CheatMatch(n);
                if (cheat != null) Add(Level.Serious, "Recent", "Deleted a cheat-related file: " + n, Short(item.Item1) + "\nDeleted " + Ago(item.Item2));
                else if ((DateTime.Now - item.Item2).TotalDays < 2) Add(Level.Suspicious, "Recent", "Deleted recently: " + n, Short(item.Item1) + "\nDeleted " + Ago(item.Item2));
            }
        }

        static string Rot13(string s) => new string(s.Select(c => c >= 'a' && c <= 'z' ? (char)('a' + (c - 'a' + 13) % 26) : c >= 'A' && c <= 'Z' ? (char)('A' + (c - 'A' + 13) % 26) : c).ToArray());

        /** Files in the Recycle Bin: (original path, when deleted). */
        static List<Tuple<string, DateTime>> RecycleBin()
        {
            var list = new List<Tuple<string, DateTime>>();
            try
            {
                var sid = WindowsIdentity.GetCurrent().User.Value;
                foreach (var drive in DriveInfo.GetDrives().Where(d => d.DriveType == DriveType.Fixed))
                {
                    var bin = Path.Combine(drive.RootDirectory.FullName, "$Recycle.Bin", sid);
                    if (!Directory.Exists(bin)) continue;
                    foreach (var info in Directory.GetFiles(bin, "$I*"))
                    {
                        try
                        {
                            var b = File.ReadAllBytes(info);
                            if (b.Length < 28) continue;
                            var when = DateTime.FromFileTime(BitConverter.ToInt64(b, 16));
                            var version = BitConverter.ToInt64(b, 0);
                            string orig = version >= 2 ? Encoding.Unicode.GetString(b, 28, BitConverter.ToInt32(b, 24) * 2) : Encoding.Unicode.GetString(b, 24, Math.Min(520, b.Length - 24));
                            list.Add(Tuple.Create(orig.TrimEnd('\0'), when));
                        }
                        catch { }
                    }
                }
            }
            catch { }
            return list;
        }

        // ---------- signs of cleaning ----------

        void ScanCleaning()
        {
            var boot = DateTime.Now - TimeSpan.FromMilliseconds(Environment.TickCount & int.MaxValue);
            if ((DateTime.Now - boot).TotalMinutes < 20) Add(Level.Suspicious, "Cleaning", "The PC was restarted " + Ago(boot), "A restart just before a screenshare can clear what's in memory.");
            else Add(Level.Info, "Cleaning", "PC last started " + Ago(boot));
            try
            {
                var explorer = Process.GetProcessesByName("explorer").Select(p => { try { return p.StartTime; } catch { return DateTime.MinValue; } }).Where(t => t > DateTime.MinValue).DefaultIfEmpty(DateTime.MinValue).Min();
                if (explorer > boot.AddMinutes(10) && (DateTime.Now - explorer).TotalMinutes < 30) Add(Level.Suspicious, "Cleaning", "Windows Explorer was restarted " + Ago(explorer), "Sometimes used to hide recent activity.");
            }
            catch { }
            var recent = Path.Combine(AppData, "Microsoft", "Windows", "Recent");
            try
            {
                var n = Directory.GetFiles(recent, "*.lnk").Length;
                if (n < 5 && (DateTime.Now - boot).TotalHours > 1) Add(Level.Suspicious, "Cleaning", "The recent files list is almost empty (" + n + " items)", "It may have been cleared.");
            }
            catch { }
            var ps = Path.Combine(AppData, "Microsoft", "Windows", "PowerShell", "PSReadLine", "ConsoleHost_history.txt");
            try
            {
                if (File.Exists(ps))
                {
                    var lines = File.ReadAllLines(ps).Reverse().Take(500);
                    foreach (var l in lines)
                        if (Regex.IsMatch(l, "wevtutil\\s+cl|Clear-EventLog|fsutil\\s+usn\\s+deletejournal|Prefetch|Remove-Item.+(Recent|\\$Recycle)|Clear-RecycleBin", RegexOptions.IgnoreCase))
                            Add(Level.Serious, "Cleaning", "A command for wiping traces was run in PowerShell", l.Trim());
                }
            }
            catch { }
        }

        // ---------- deep check (administrator) ----------

        void ScanDeep()
        {
            // Every program run recently, including ones deleted afterwards (Background Activity Moderator)
            try
            {
                using (var bam = Registry.LocalMachine.OpenSubKey(@"SYSTEM\CurrentControlSet\Services\bam\State\UserSettings"))
                    foreach (var sid in bam?.GetSubKeyNames() ?? new string[0])
                        using (var k = bam.OpenSubKey(sid))
                            foreach (var v in k?.GetValueNames() ?? new string[0])
                            {
                                if (!v.StartsWith(@"\Device\")) continue;
                                var data = k.GetValue(v) as byte[];
                                if (data == null || data.Length < 8) continue;
                                DateTime last;
                                try { last = DateTime.FromFileTime(BitConverter.ToInt64(data, 0)); } catch { continue; }
                                var path = Regex.Replace(v, @"^\\Device\\HarddiskVolume\d+", "?:");
                                var n = Path.GetFileName(path);
                                var cheat = CheatMatch(n);
                                var age = DateTime.Now - last;
                                if (cheat != null) Add(Level.Serious, "Deep check", "Cheat-related program was run: " + n, path + "\nLast run " + Ago(last));
                                else if (Injectors.Concat(Clickers).Any(i => n.ToLowerInvariant().Contains(i))) Add(Level.Serious, "Deep check", "Injector or clicker was run: " + n, path + "\nLast run " + Ago(last));
                                else if (age.TotalHours < 24 && !ExistsAnyDrive(path) && Regex.IsMatch(n, "\\.exe$", RegexOptions.IgnoreCase))
                                    Add(Level.Suspicious, "Deep check", "Program run in the last day that has since been deleted: " + n, path + "\nLast run " + Ago(last));
                            }
            }
            catch (Exception e) { Add(Level.Info, "Deep check", "Couldn't read the program history (" + e.Message + ")"); }

            // Prefetch (Windows' record of programs run)
            var pf = Path.Combine(Win, "Prefetch");
            try
            {
                var en = Registry.LocalMachine.OpenSubKey(@"SYSTEM\CurrentControlSet\Control\Session Manager\Memory Management\PrefetchParameters")?.GetValue("EnablePrefetcher");
                if (en is int i && i == 0) Add(Level.Suspicious, "Deep check", "Windows' program history (Prefetch) has been switched off");
                var files = Directory.GetFiles(pf, "*.pf");
                if (files.Length < 20) Add(Level.Suspicious, "Deep check", "Windows' program history (Prefetch) is almost empty (" + files.Length + " entries)", "It may have been cleared.");
                foreach (var f in files)
                {
                    var n = Regex.Replace(Path.GetFileNameWithoutExtension(f), "-[0-9A-F]{8}$", "");
                    var cheat = CheatMatch(n);
                    if (cheat != null) Add(Level.Serious, "Deep check", "Cheat-related program was run: " + n, "Last run " + Ago(File.GetLastWriteTime(f)));
                    else if (Injectors.Concat(Clickers).Any(x => n.ToLowerInvariant().Contains(x.Replace(" ", "")))) Add(Level.Serious, "Deep check", "Injector or clicker was run: " + n, "Last run " + Ago(File.GetLastWriteTime(f)));
                }
            }
            catch (Exception e) { Add(Level.Info, "Deep check", "Couldn't read Prefetch (" + e.Message + ")"); }

            // Cleared event logs
            foreach (var spec in new[] { Tuple.Create("System", 104L), Tuple.Create("Security", 1102L) })
            {
                try
                {
                    using (var log = new EventLog(spec.Item1))
                    {
                        var entries = log.Entries;
                        for (int i = entries.Count - 1; i >= 0 && i >= entries.Count - 3000; i--)
                        {
                            var e = entries[i];
                            if ((DateTime.Now - e.TimeGenerated).TotalDays > 7) break;
                            if (e.InstanceId == spec.Item2 || (e.InstanceId & 0xFFFF) == spec.Item2)
                            { Add(Level.Serious, "Deep check", "Windows event logs were cleared " + Ago(e.TimeGenerated), spec.Item1 + " log"); break; }
                        }
                    }
                }
                catch { }
            }
        }

        static bool ExistsAnyDrive(string pathWithQ)
        {
            foreach (var d in DriveInfo.GetDrives())
                try { if (File.Exists(pathWithQ.Replace("?:", d.Name.TrimEnd('\\')))) return true; } catch { }
            return false;
        }

        // ---------- Minecraft accounts ----------

        void ScanAccounts()
        {
            // Names in the game's logs and launcher account files
            var places = new[] {
                Path.Combine(AppData, ".minecraft", "logs", "latest.log"),
            };
            foreach (var f in places.Where(File.Exists))
                try
                {
                    using (var fs = new FileStream(f, FileMode.Open, FileAccess.Read, FileShare.ReadWrite))
                    using (var sr = new StreamReader(fs))
                    {
                        var txt = sr.ReadToEnd();
                        foreach (Match m in Regex.Matches(txt, "Setting user: ([A-Za-z0-9_]{2,16})"))
                            if (!r.Accounts.Contains(m.Groups[1].Value)) r.Accounts.Add(m.Groups[1].Value);
                    }
                }
                catch { }
            foreach (var f in new[] { Path.Combine(AppData, ".minecraft", "launcher_accounts.json"), Path.Combine(AppData, ".minecraft", "launcher_accounts_microsoft_store.json"), Path.Combine(Home, ".lunarclient", "settings", "game", "accounts.json") }.Where(File.Exists))
                try
                {
                    foreach (Match m in Regex.Matches(File.ReadAllText(f), "\"(?:name|username)\"\\s*:\\s*\"([A-Za-z0-9_]{2,16})\""))
                        if (!r.Accounts.Contains(m.Groups[1].Value)) r.Accounts.Add(m.Groups[1].Value);
                }
                catch { }
        }

        // ---------- helpers ----------

        public static string Http(string method, string url, string body = null)
        {
            ServicePointManager.SecurityProtocol = SecurityProtocolType.Tls12;
            var req = (HttpWebRequest)WebRequest.Create(url);
            req.Method = method;
            req.UserAgent = "StaffToolkitScan/" + Report.Version;
            req.Timeout = 20000;
            if (body != null)
            {
                var bytes = Encoding.UTF8.GetBytes(body);
                req.ContentType = "application/json";
                req.ContentLength = bytes.Length;
                using (var s = req.GetRequestStream()) s.Write(bytes, 0, bytes.Length);
            }
            using (var res = (HttpWebResponse)req.GetResponse())
            using (var sr = new StreamReader(res.GetResponseStream())) return sr.ReadToEnd();
        }

        public static bool IsAdmin() => new WindowsPrincipal(WindowsIdentity.GetCurrent()).IsInRole(WindowsBuiltInRole.Administrator);
    }
}
