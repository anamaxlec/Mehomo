import AppKit
import Foundation
// Package the generated transparent foreground into Android's 108dp canvas.
let root = URL(fileURLWithPath: FileManager.default.currentDirectoryPath)
let source = NSBitmapImageRep(data: try Data(contentsOf: root.appendingPathComponent("design/icon/mehomo-miku-source.png")))!
var minX = source.pixelsWide, minY = source.pixelsHigh, maxX = 0, maxY = 0
for y in 0..<source.pixelsHigh { for x in 0..<source.pixelsWide {
    if (source.colorAt(x: x, y: y)?.alphaComponent ?? 0) > 0.02 {
        minX = min(minX,x); minY = min(minY,y); maxX = max(maxX,x); maxY = max(maxY,y)
    }
}}
let cx = Double(minX+maxX)/2, cy = Double(minY+maxY)/2
var radius = 0.0
for y in minY...maxY { for x in minX...maxX {
    if (source.colorAt(x: x, y: y)?.alphaComponent ?? 0) > 0.02 {
        radius = max(radius, hypot(Double(x)-cx,Double(y)-cy))
    }
}}
let sourceImage = NSImage(size: NSSize(width: source.pixelsWide, height: source.pixelsHigh))
sourceImage.addRepresentation(source)
func render(_ path: String, size: Int, foregroundOnly: Bool = false, circle: Bool = false, launcher: Bool = true) throws {
    let rep = NSBitmapImageRep(bitmapDataPlanes: nil,pixelsWide:size,pixelsHigh:size,bitsPerSample:8,samplesPerPixel:4,hasAlpha:true,isPlanar:false,colorSpaceName:.deviceRGB,bytesPerRow:0,bitsPerPixel:0)!
    NSGraphicsContext.saveGraphicsState()
    NSGraphicsContext.current = NSGraphicsContext(bitmapImageRep: rep)
    let s = Double(size)
    if circle { NSBezierPath(ovalIn: NSRect(x:0,y:0,width:s,height:s)).addClip() }
    if !foregroundOnly {
        NSColor(srgbRed:0.08,green:0.085,blue:0.10,alpha:1).setFill()
        NSBezierPath(rect:NSRect(x:0,y:0,width:s,height:s)).fill()
    }
    // 62dp circle inside the 66dp safe circle; preview uses the launcher's 72dp viewport.
    let viewport = launcher ? 72.0 : 108.0
    let scale = (31.0 / radius) * s / viewport
    let w = Double(source.pixelsWide)*scale, h = Double(source.pixelsHigh)*scale
    let dx = s/2 - cx*scale, dy = s/2 - (Double(source.pixelsHigh)-cy)*scale
    NSGraphicsContext.current?.imageInterpolation = .high
    sourceImage.draw(in:NSRect(x:dx,y:dy,width:w,height:h),from:.zero,operation:.sourceOver,fraction:1)
    NSGraphicsContext.restoreGraphicsState()
    try rep.retagging(with: .sRGB)!.representation(using:.png,properties:[:])!.write(to:root.appendingPathComponent(path))
}
try render("app/src/main/res/drawable-nodpi/ic_launcher_avatar.png",size:432,foregroundOnly:true,launcher:false)
try render("design/icon/mehomo-round.png",size:512,circle:true)
try render("design/icon/mehomo-play-512.png",size:512)
print("Source alpha bounds: \(minX),\(minY)...\(maxX),\(maxY); foreground within centered 62dp circle; Android foreground 432px; exports 512px sRGB RGBA")
// Android themed icons use only alpha; remove the dark capsule eyes.
let input = NSBitmapImageRep(data: try Data(contentsOf: root.appendingPathComponent("app/src/main/res/drawable-nodpi/ic_launcher_avatar.png")))!
let themed = NSBitmapImageRep(bitmapDataPlanes:nil,pixelsWide:432,pixelsHigh:432,bitsPerSample:8,samplesPerPixel:4,hasAlpha:true,isPlanar:false,colorSpaceName:.deviceRGB,bytesPerRow:0,bitsPerPixel:0)!
let pixels = input.bitmapData!, result = themed.bitmapData!
for y in 0..<432 { for x in 0..<432 {
    let i = y * input.bytesPerRow + x * 4, o = y * themed.bytesPerRow + x * 4
    let alpha = Int(pixels[i+3])
    let dark = Int(max(pixels[i],max(pixels[i+1],pixels[i+2]))) * 255 < alpha * 46
    result[o] = 255; result[o+1] = 255; result[o+2] = 255
    result[o+3] = dark ? 0 : pixels[i+3]
}}
try themed.retagging(with:.sRGB)!.representation(using:.png,properties:[:])!.write(to:root.appendingPathComponent("app/src/main/res/drawable-nodpi/ic_launcher_themed.png"))

func themedPreview(_ path: String, background: NSColor, foreground: NSColor) throws {
    let tinted = NSBitmapImageRep(bitmapDataPlanes:nil,pixelsWide:432,pixelsHigh:432,bitsPerSample:8,samplesPerPixel:4,hasAlpha:true,isPlanar:false,colorSpaceName:.deviceRGB,bytesPerRow:0,bitsPerPixel:0)!
    let data = tinted.bitmapData!
    let color = foreground.usingColorSpace(.sRGB)!
    for y in 0..<432 { for x in 0..<432 {
        let i = y * themed.bytesPerRow + x*4, o = y * tinted.bytesPerRow + x*4
        let a = Double(themed.bitmapData![i+3]) / 255
        data[o] = UInt8(color.redComponent * a * 255)
        data[o+1] = UInt8(color.greenComponent * a * 255)
        data[o+2] = UInt8(color.blueComponent * a * 255)
        data[o+3] = themed.bitmapData![i+3]
    }}
    let image = NSImage(size:NSSize(width:432,height:432)); image.addRepresentation(tinted)
    let rep = NSBitmapImageRep(bitmapDataPlanes:nil,pixelsWide:512,pixelsHigh:512,bitsPerSample:8,samplesPerPixel:4,hasAlpha:true,isPlanar:false,colorSpaceName:.deviceRGB,bytesPerRow:0,bitsPerPixel:0)!
    NSGraphicsContext.saveGraphicsState(); NSGraphicsContext.current = NSGraphicsContext(bitmapImageRep:rep)
    NSBezierPath(ovalIn:NSRect(x:0,y:0,width:512,height:512)).addClip()
    background.setFill(); NSBezierPath(rect:NSRect(x:0,y:0,width:512,height:512)).fill()
    image.draw(in:NSRect(x:-128,y:-128,width:768,height:768),from:.zero,operation:.sourceOver,fraction:1)
    NSGraphicsContext.restoreGraphicsState()
    try rep.retagging(with:.sRGB)!.representation(using:.png,properties:[:])!.write(to:root.appendingPathComponent(path))
}
try themedPreview("design/icon/mehomo-white.png",background:NSColor(srgbRed:0.08,green:0.085,blue:0.10,alpha:1),foreground:.white)
try themedPreview("design/icon/mehomo-themed-light.png",background:NSColor(srgbRed:0.91,green:0.86,blue:1,alpha:1),foreground:NSColor(srgbRed:0.21,green:0.13,blue:0.34,alpha:1))
try themedPreview("design/icon/mehomo-themed-dark.png",background:NSColor(srgbRed:0.21,green:0.13,blue:0.34,alpha:1),foreground:NSColor(srgbRed:0.91,green:0.86,blue:1,alpha:1))
// Default launcher artwork is the user's selected light purple silhouette.
try FileManager.default.removeItem(at:root.appendingPathComponent("design/icon/mehomo-round.png"))
try FileManager.default.copyItem(at:root.appendingPathComponent("design/icon/mehomo-themed-light.png"),to:root.appendingPathComponent("design/icon/mehomo-round.png"))
let round = NSImage(contentsOf:root.appendingPathComponent("design/icon/mehomo-round.png"))!
let play = NSBitmapImageRep(bitmapDataPlanes:nil,pixelsWide:512,pixelsHigh:512,bitsPerSample:8,samplesPerPixel:4,hasAlpha:true,isPlanar:false,colorSpaceName:.deviceRGB,bytesPerRow:0,bitsPerPixel:0)!
NSGraphicsContext.saveGraphicsState(); NSGraphicsContext.current = NSGraphicsContext(bitmapImageRep:play)
NSColor(srgbRed:0.91,green:0.86,blue:1,alpha:1).setFill()
NSBezierPath(rect:NSRect(x:0,y:0,width:512,height:512)).fill()
round.draw(in:NSRect(x:0,y:0,width:512,height:512),from:.zero,operation:.sourceOver,fraction:1)
NSGraphicsContext.restoreGraphicsState()
try play.retagging(with:.sRGB)!.representation(using:.png,properties:[:])!.write(to:root.appendingPathComponent("design/icon/mehomo-play-512.png"))
