package trayapp

import (
	"bytes"
	"encoding/binary"
	"image"
	"image/color"
	"image/png"
)

// iconBytes is a small, generated-at-init .ico (not loaded from an external
// asset file): a solid teal square with a darker ring, built from a PNG via
// the standard library and wrapped in a minimal single-image ICO container.
// Modern Windows (Vista+) accepts a PNG-compressed image inside an ICO
// entry directly, so no BMP/DIB encoding is needed.
var iconBytes = buildICO(generateIconPNG(32))

func generateIconPNG(size int) []byte {
	img := image.NewRGBA(image.Rect(0, 0, size, size))
	teal := color.RGBA{R: 0x4f, G: 0xe3, B: 0xc9, A: 0xff}
	dark := color.RGBA{R: 0x0b, G: 0x12, B: 0x10, A: 0xff}
	center := float64(size) / 2
	radius := float64(size) / 2
	for y := 0; y < size; y++ {
		for x := 0; x < size; x++ {
			dx := float64(x) + 0.5 - center
			dy := float64(y) + 0.5 - center
			dist := dx*dx + dy*dy
			if dist <= radius*radius {
				if dist >= (radius*0.62)*(radius*0.62) {
					img.Set(x, y, dark)
				} else {
					img.Set(x, y, teal)
				}
			} else {
				img.Set(x, y, color.RGBA{})
			}
		}
	}
	var buf bytes.Buffer
	_ = png.Encode(&buf, img)
	return buf.Bytes()
}

// buildICO wraps one PNG image as a single-entry .ico file.
func buildICO(pngData []byte) []byte {
	var buf bytes.Buffer

	// ICONDIR
	binary.Write(&buf, binary.LittleEndian, uint16(0)) // reserved
	binary.Write(&buf, binary.LittleEndian, uint16(1)) // type: 1 = icon
	binary.Write(&buf, binary.LittleEndian, uint16(1)) // image count

	// ICONDIRENTRY (16 bytes)
	cfg, _ := png.DecodeConfig(bytes.NewReader(pngData))
	widthByte := byte(cfg.Width)
	heightByte := byte(cfg.Height)
	if cfg.Width >= 256 {
		widthByte = 0
	}
	if cfg.Height >= 256 {
		heightByte = 0
	}
	buf.WriteByte(widthByte)
	buf.WriteByte(heightByte)
	buf.WriteByte(0)                                       // color palette
	buf.WriteByte(0)                                       // reserved
	binary.Write(&buf, binary.LittleEndian, uint16(1))     // color planes
	binary.Write(&buf, binary.LittleEndian, uint16(32))    // bits per pixel
	binary.Write(&buf, binary.LittleEndian, uint32(len(pngData))) // image data size
	binary.Write(&buf, binary.LittleEndian, uint32(22))    // offset: 6 (ICONDIR) + 16 (one ICONDIRENTRY)

	buf.Write(pngData)
	return buf.Bytes()
}
