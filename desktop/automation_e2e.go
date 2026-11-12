//go:build e2e

package main

import (
	"embed"
	"io/fs"

	"github.com/egoist/mygo"
	_ "github.com/egoist/mygo/plugins/automation"
)

//go:embed all:dist
var e2eDist embed.FS

func init() {
	if web, err := fs.Sub(e2eDist, "dist"); err == nil {
		mygo.SetFrontend(web)
	}
}
