package main

import (
	"bytes"
	"image"
	"image/color"
	"image/png"
	"math"
)

// buildTrayTemplatePNG 生成 32x32 Retina 黑色透明模板图标，macOS / Windows / Linux 自动适配亮暗色菜单栏。
func buildTrayTemplatePNG() []byte {
	const sz = 32
	img := image.NewNRGBA(image.Rect(0, 0, sz, sz))
	cx, cy := 15.5, 15.5
	bars := [][2]float64{
		{-8.0, 5.0},
		{-2.7, 10.0},
		{2.7, 7.5},
		{8.0, 4.0},
	}
	barRadius := 1.45

	for y := 0; y < sz; y++ {
		for x := 0; x < sz; x++ {
			var maxAlpha float64
			for _, b := range bars {
				bx := cx + b[0]
				bh := b[1]
				dx := math.Abs(float64(x) - bx)
				dy := math.Max(math.Abs(float64(y)-cy)-bh, 0)
				dist := math.Hypot(dx, dy) - barRadius
				if dist < 0.7 {
					a := 1.0
					if dist > -0.7 {
						a = (0.7 - dist) / 1.4
					}
					if a > maxAlpha {
						maxAlpha = a
					}
				}
			}
			if maxAlpha > 0 {
				img.SetNRGBA(x, y, color.NRGBA{R: 0, G: 0, B: 0, A: uint8(maxAlpha * 235)})
			}
		}
	}
	var buf bytes.Buffer
	_ = png.Encode(&buf, img)
	return buf.Bytes()
}
