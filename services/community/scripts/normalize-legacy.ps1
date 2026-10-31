param([Parameter(Mandatory)][string]$SourceList, [Parameter(Mandatory)][string]$OutputDirectory)
$ErrorActionPreference = 'Stop'
Add-Type -AssemblyName System.Drawing
Add-Type -AssemblyName System.IO.Compression
$destination = [IO.Path]::GetFullPath($OutputDirectory)
[void][IO.Directory]::CreateDirectory($destination)
$urls = Get-Content -LiteralPath $SourceList -Raw | ConvertFrom-Json
$index = @()
foreach ($source in $urls) {
    $uri = [Uri]$source
    if (!$source.StartsWith('https://github.com/avavo/WinXclipse/releases/download/community-configs/') -or
        $uri.Query -or $uri.Fragment -or $uri.UserInfo -or $uri.Host -ne 'github.com') { throw 'Unofficial source' }
    $name = [IO.Path]::GetFileName($uri.LocalPath)
    if ($name -notmatch '^[a-zA-Z0-9_.-]+\.zip$') { throw 'Unsupported asset name' }
    $original = Join-Path $destination ('.source-' + $name)
    $output = Join-Path $destination $name
    Invoke-WebRequest -Uri $source -OutFile $original
    try {
        if ((Get-Item -LiteralPath $original).Length -gt 20MB) { throw 'Archive too large' }
        $zip = [IO.Compression.ZipFile]::OpenRead($original)
        try {
            $config = $zip.Entries | Where-Object { $_.FullName -in @('config.txt','manifest.json') } | Select-Object -First 1
            if (!$config -or $config.Length -gt 5MB) { throw 'Missing or oversized config' }
            $manifest = [IO.MemoryStream]::new(); $input = $config.Open()
            try { $input.CopyTo($manifest) } finally { $input.Dispose() }
            $cover = $null
            $entry = $zip.Entries | Where-Object { $_.Name -match '\.(png|jpe?g|bmp)$' } | Sort-Object @{Expression={if($_.Name -eq 'cover.png'){0}else{1}}} | Select-Object -First 1
            if ($entry) {
                if ($entry.Length -gt 16MB) { throw 'Cover too large' }
                $input = $entry.Open(); $imageData = [IO.MemoryStream]::new()
                try { $input.CopyTo($imageData) } finally { $input.Dispose() }
                $imageData.Position = 0
                $image = [Drawing.Image]::FromStream($imageData, $true, $true)
                try {
                    if ($image.Width -gt 16384 -or $image.Height -gt 16384 -or [long]$image.Width * $image.Height -gt 64000000) { throw 'Cover dimensions too large' }
                    $scale = [Math]::Min(1.0, 1280.0 / [Math]::Max($image.Width, $image.Height))
                    $bitmap = [Drawing.Bitmap]::new([Math]::Max(1,[int]($image.Width*$scale)), [Math]::Max(1,[int]($image.Height*$scale)))
                    try {
                        $graphics = [Drawing.Graphics]::FromImage($bitmap)
                        try { $graphics.DrawImage($image,0,0,$bitmap.Width,$bitmap.Height) } finally { $graphics.Dispose() }
                        $cover = [IO.MemoryStream]::new(); $bitmap.Save($cover,[Drawing.Imaging.ImageFormat]::Png)
                    } finally { $bitmap.Dispose() }
                } finally { $image.Dispose(); $imageData.Dispose() }
            }
            $file = [IO.File]::Open($output,[IO.FileMode]::Create,[IO.FileAccess]::Write)
            $prepared = [IO.Compression.ZipArchive]::new($file,[IO.Compression.ZipArchiveMode]::Create)
            try {
                $stream = $prepared.CreateEntry('config.txt').Open()
                try { $manifest.Position=0; $manifest.CopyTo($stream) } finally { $stream.Dispose() }
                if ($cover) { $stream = $prepared.CreateEntry('cover.png').Open()
                    try { $cover.Position=0; $cover.CopyTo($stream) } finally { $stream.Dispose(); $cover.Dispose() } }
            } finally { $prepared.Dispose(); $file.Dispose(); $manifest.Dispose() }
            $index += @{source=$source;archive=$output}
            Write-Output ('Prepared ' + $name)
        } finally { $zip.Dispose() }
    } finally { Remove-Item -LiteralPath $original -Force }
}
$index | ConvertTo-Json | Set-Content -LiteralPath (Join-Path $destination 'index.json') -Encoding utf8
