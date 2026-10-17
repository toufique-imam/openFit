# Windows renderer for the original SVG geometry and store copy in docs/store.
$ErrorActionPreference = 'Stop'
Add-Type -AssemblyName System.Drawing
$directory = Join-Path (Split-Path $PSScriptRoot -Parent) 'docs/store'
function Color([string]$hex) { [Drawing.ColorTranslator]::FromHtml($hex) }
function Brush([string]$hex) { [Drawing.SolidBrush]::new((Color $hex)) }
function Save-Icon {
    $bitmap = [Drawing.Bitmap]::new(512, 512)
    $graphics = [Drawing.Graphics]::FromImage($bitmap)
    $graphics.SmoothingMode = [Drawing.Drawing2D.SmoothingMode]::AntiAlias
    $graphics.Clear((Color '#111827'))
    $accent = Brush '#A8C7FF'
    $graphics.FillRectangle($accent, 136, 256, 64, 120)
    $graphics.FillRectangle($accent, 224, 196, 64, 180)
    $graphics.FillRectangle($accent, 312, 136, 64, 240)
    $bitmap.Save((Join-Path $directory 'icon.png'), [Drawing.Imaging.ImageFormat]::Png)
    $accent.Dispose(); $graphics.Dispose(); $bitmap.Dispose()
}
function Save-FeatureGraphic {
    $bitmap = [Drawing.Bitmap]::new(1024, 500)
    $graphics = [Drawing.Graphics]::FromImage($bitmap)
    $graphics.SmoothingMode = [Drawing.Drawing2D.SmoothingMode]::AntiAlias
    $graphics.TextRenderingHint = [Drawing.Text.TextRenderingHint]::AntiAliasGridFit
    $graphics.Clear((Color '#111827'))
    $text = Brush '#F1F5FB'
    $secondary = Brush '#BDCADB'
    $accent = Brush '#A8C7FF'
    $card = Brush '#1B2536'
    $title = [Drawing.Font]::new('Segoe UI', 72, [Drawing.FontStyle]::Bold, [Drawing.GraphicsUnit]::Pixel)
    $body = [Drawing.Font]::new('Segoe UI', 30, [Drawing.FontStyle]::Regular, [Drawing.GraphicsUnit]::Pixel)
    $small = [Drawing.Font]::new('Segoe UI', 22, [Drawing.FontStyle]::Regular, [Drawing.GraphicsUnit]::Pixel)
    $caption = [Drawing.Font]::new('Segoe UI', 18, [Drawing.FontStyle]::Regular, [Drawing.GraphicsUnit]::Pixel)
    $graphics.DrawString('openFit', $title, $text, 56, 68)
    $graphics.DrawString('Make room for your health.', $body, $secondary, 60, 182)
    $graphics.DrawString('Activity / Sleep / Recovery', $small, $accent, 60, 247)
    $graphics.DrawString('No openFit account. On your phone.', $caption, $secondary, 60, 398)
    $shape = [Drawing.Drawing2D.GraphicsPath]::new()
    $shape.AddArc(660, 64, 48, 48, 180, 90)
    $shape.AddArc(912, 64, 48, 48, 270, 90)
    $shape.AddArc(912, 388, 48, 48, 0, 90)
    $shape.AddArc(660, 388, 48, 48, 90, 90)
    $shape.CloseFigure()
    $graphics.FillPath($card, $shape)
    $graphics.FillRectangle($accent, 695, 250, 50, 100)
    $graphics.FillRectangle($accent, 775, 200, 50, 150)
    $graphics.FillRectangle($accent, 855, 150, 50, 200)
    $bitmap.Save((Join-Path $directory 'feature-graphic.png'), [Drawing.Imaging.ImageFormat]::Png)
    @($shape, $title, $body, $small, $caption, $text, $secondary, $accent, $card, $graphics, $bitmap) | ForEach-Object { $_.Dispose() }
}
Save-Icon
Save-FeatureGraphic
