# Reads the text in images using Windows' built-in text recognition (offline).
# Started once by Chapter Reader; give it one image path per line on stdin and
# it answers with one line of JSON per image: {"lines":[{"text","l","t","r","b"}]}.
$ErrorActionPreference = 'Stop'
try {
  Add-Type -AssemblyName System.Runtime.WindowsRuntime
  $null = [Windows.Storage.StorageFile, Windows.Storage, ContentType = WindowsRuntime]
  $null = [Windows.Storage.Streams.IRandomAccessStream, Windows.Storage.Streams, ContentType = WindowsRuntime]
  $null = [Windows.Media.Ocr.OcrEngine, Windows.Foundation, ContentType = WindowsRuntime]
  $null = [Windows.Graphics.Imaging.BitmapDecoder, Windows.Graphics, ContentType = WindowsRuntime]
  $null = [Windows.Globalization.Language, Windows.Globalization, ContentType = WindowsRuntime]

  $asTask = ([System.WindowsRuntimeSystemExtensions].GetMethods() | Where-Object {
      $_.Name -eq 'AsTask' -and $_.GetParameters().Count -eq 1 -and
      $_.GetParameters()[0].ParameterType.Name -eq 'IAsyncOperation`1' })[0]
  function Await($op, [Type]$type) {
    $task = $asTask.MakeGenericMethod($type).Invoke($null, @($op))
    $null = $task.Wait(-1)
    $task.Result
  }

  $engine = [Windows.Media.Ocr.OcrEngine]::TryCreateFromUserProfileLanguages()
  if ($null -eq $engine) {
    $engine = [Windows.Media.Ocr.OcrEngine]::TryCreateFromLanguage((New-Object Windows.Globalization.Language 'en-US'))
  }
  if ($null -eq $engine) {
    '{"error":"Windows has no text recognition language installed. Add English in Settings > Time & language > Language."}'
    exit 1
  }
  '{"ready":true,"max":' + [Windows.Media.Ocr.OcrEngine]::MaxImageDimension + '}'
  [Console]::Out.Flush()
} catch {
  ConvertTo-Json -Compress -InputObject @{ error = ('Could not start Windows text recognition: ' + $_.Exception.Message) }
  exit 1
}

while ($null -ne ($line = [Console]::In.ReadLine())) {
  # Each request is "id<TAB>path"; the answer carries the same id
  $parts = $line.Trim().Split("`t", 2)
  if ($parts.Count -lt 2) { continue }
  $id = $parts[0]
  $path = $parts[1]
  try {
    $file = Await ([Windows.Storage.StorageFile]::GetFileFromPathAsync($path)) ([Windows.Storage.StorageFile])
    $stream = Await ($file.OpenAsync([Windows.Storage.FileAccessMode]::Read)) ([Windows.Storage.Streams.IRandomAccessStream])
    $decoder = Await ([Windows.Graphics.Imaging.BitmapDecoder]::CreateAsync($stream)) ([Windows.Graphics.Imaging.BitmapDecoder])
    $bitmap = Await ($decoder.GetSoftwareBitmapAsync()) ([Windows.Graphics.Imaging.SoftwareBitmap])
    $result = Await ($engine.RecognizeAsync($bitmap)) ([Windows.Media.Ocr.OcrResult])
    $lines = New-Object System.Collections.ArrayList
    foreach ($ln in $result.Lines) {
      $l = [double]::MaxValue; $t = [double]::MaxValue; $r = 0.0; $b = 0.0
      foreach ($w in $ln.Words) {
        $box = $w.BoundingRect
        if ($box.X -lt $l) { $l = $box.X }
        if ($box.Y -lt $t) { $t = $box.Y }
        if ($box.X + $box.Width -gt $r) { $r = $box.X + $box.Width }
        if ($box.Y + $box.Height -gt $b) { $b = $box.Y + $box.Height }
      }
      [void]$lines.Add([ordered]@{ text = $ln.Text; l = $l; t = $t; r = $r; b = $b })
    }
    $stream.Dispose()
    $bitmap.Dispose()
    ConvertTo-Json -Compress -Depth 4 -InputObject @{ id = $id; lines = $lines.ToArray() }
  } catch {
    ConvertTo-Json -Compress -InputObject @{ id = $id; error = $_.Exception.Message }
  }
  [Console]::Out.Flush()
}
