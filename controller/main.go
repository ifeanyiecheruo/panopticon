package main

import (
	"context"
	"embed"
	"fmt"
	"log"
	"net/http"
	"os"
	"strings"
	"time"

	"panopticon-controller/internal/appdirs"
	"panopticon-controller/internal/dbstore"
	"panopticon-controller/internal/identity"
	"panopticon-controller/internal/singleinstance"
	"panopticon-controller/internal/syncer"
	"panopticon-controller/internal/trayapp"

	"github.com/wailsapp/wails/v2"
	"github.com/wailsapp/wails/v2/pkg/options"
	"github.com/wailsapp/wails/v2/pkg/options/assetserver"
	wailsRuntime "github.com/wailsapp/wails/v2/pkg/runtime"
)

//go:embed all:frontend/dist
var assets embed.FS

// syncPollInterval is how often the background sync loop polls each paired
// phone's GET /api/clips. The handoff doc leaves cadence as an open item
// ("Cadence/backoff strategy for the background sync loop... how often") —
// 30s is this slice's concrete pick, kept as a single named constant so
// it's trivial to retune.
const syncPollInterval = 30 * time.Second

func main() {
	dirs, err := appdirs.Resolve()
	if err != nil {
		fatalf("resolve app directories: %v", err)
	}

	// Single-instance guard: a second launch must not run a duplicate sync
	// loop or duplicate tray icon (see internal/singleinstance's doc comment
	// for why — the old prototype hit real degradation from exactly this).
	lock, err := singleinstance.Acquire(dirs.LockPath())
	if err != nil {
		if err == singleinstance.ErrAlreadyRunning {
			fmt.Println("Panopticon is already running (check your system tray).")
			os.Exit(0)
		}
		fatalf("acquire single-instance lock: %v", err)
	}
	defer lock.Release()

	store, err := dbstore.Open(dirs.DBPath())
	if err != nil {
		fatalf("open database: %v", err)
	}
	defer store.Close()

	if _, err := identity.LoadOrCreate(store); err != nil {
		fatalf("load/create controller identity: %v", err)
	}

	syncMgr := syncer.NewManager(store, dirs, syncPollInterval)
	syncMgr.Start()
	defer syncMgr.Stop()

	app := NewApp(store, dirs, syncMgr)

	// Tray icon: the app's persistent presence. Runs its own Win32 message
	// loop on its own goroutine/OS thread — see trayapp.Run's doc comment
	// for why this is safe alongside wails.Run()'s own message loop below.
	go trayapp.Run(trayapp.Callbacks{
		OnOpen: func() { app.ShowWindow() },
		OnQuit: func() { app.RequestQuit() },
	})

	// Serve the local clip archive to the webview at /archive/<phoneId>/<filename>
	// so the Gallery's <video>/<img> tags can load synced files directly,
	// alongside the embedded frontend/dist assets.
	archiveHandler := http.StripPrefix("/archive/", http.FileServer(http.Dir(dirs.ArchiveDir)))
	middleware := func(next http.Handler) http.Handler {
		return http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
			if strings.HasPrefix(r.URL.Path, "/archive/") {
				archiveHandler.ServeHTTP(w, r)
				return
			}
			next.ServeHTTP(w, r)
		})
	}

	err = wails.Run(&options.App{
		Title:  "Panopticon",
		Width:  1180,
		Height: 800,
		AssetServer: &assetserver.Options{
			Assets:     assets,
			Middleware: middleware,
		},
		BackgroundColour: &options.RGBA{R: 11, G: 18, B: 16, A: 1},
		OnStartup:        app.startup,
		OnBeforeClose: func(ctx context.Context) (prevent bool) {
			if app.IsQuitting() {
				return false // allow the real close/quit to proceed
			}
			// Closing the window must not stop background sync — hide
			// instead, per the tray-app product shape.
			wailsRuntime.WindowHide(ctx)
			return true
		},
		Bind: []interface{}{app},
	})

	trayapp.Quit()

	if err != nil {
		log.Println("wails run error:", err)
	}
}

func fatalf(format string, args ...interface{}) {
	fmt.Fprintf(os.Stderr, format+"\n", args...)
	os.Exit(1)
}
