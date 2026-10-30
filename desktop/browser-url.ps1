# Prints the address of the page open in your browser (Edge, Chrome, Brave...).
# Used when you tap "Read this page" on the bubble.
Add-Type -AssemblyName UIAutomationClient
Add-Type -AssemblyName UIAutomationTypes
Add-Type @"
using System;
using System.Runtime.InteropServices;
public class CRWin {
  [DllImport("user32.dll")] public static extern IntPtr GetForegroundWindow();
  [DllImport("user32.dll")] public static extern uint GetWindowThreadProcessId(IntPtr h, out uint pid);
}
"@

$names = @('msedge', 'chrome', 'brave', 'opera', 'vivaldi', 'firefox')
$handles = New-Object System.Collections.ArrayList

# The browser window you were last using comes first
$fg = [CRWin]::GetForegroundWindow()
[uint32]$fgpid = 0
[void][CRWin]::GetWindowThreadProcessId($fg, [ref]$fgpid)
try {
  $fp = Get-Process -Id $fgpid -ErrorAction Stop
  if ($names -contains $fp.ProcessName) { [void]$handles.Add($fg) }
} catch {}

Get-Process -Name $names -ErrorAction SilentlyContinue |
  Where-Object { $_.MainWindowHandle -ne [IntPtr]::Zero } |
  ForEach-Object { [void]$handles.Add($_.MainWindowHandle) }

$cond = New-Object System.Windows.Automation.PropertyCondition(
  [System.Windows.Automation.AutomationElement]::ControlTypeProperty,
  [System.Windows.Automation.ControlType]::Edit)

foreach ($h in $handles) {
  try {
    $root = [System.Windows.Automation.AutomationElement]::FromHandle($h)
    $edit = $root.FindFirst([System.Windows.Automation.TreeScope]::Descendants, $cond)
    if ($edit -ne $null) {
      $vp = $edit.GetCurrentPattern([System.Windows.Automation.ValuePattern]::Pattern)
      $v = $vp.Current.Value
      if ($v) { Write-Output $v; exit 0 }
    }
  } catch {}
}
exit 1
