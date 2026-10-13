package libcore

import (
	"context"
	"fmt"
	"libcore/device"
	"os"

	"github.com/sagernet/sing-box/adapter"
	"github.com/sagernet/sing-box/experimental/cachefile"
	boxLog "github.com/sagernet/sing-box/log"
	"github.com/sagernet/sing/service"
	"github.com/sagernet/sing/service/filemanager"
)

func LoadMASQUEConfigFromCache(tag string, cacheFilePath string) (config string, err error) {
	defer device.DeferPanicToError("LoadMASQUEConfigFromCache", func(err_ error) { err = err_ })
	if tag == "" {
		return "", fmt.Errorf("empty MASQUE tag")
	}
	ctx := filemanager.WithDefault(service.ContextWithDefaultRegistry(context.Background()), workingPath, tempPath, os.Getuid(), os.Getgid())
	// Open the cache file read-only. This shares the same cache.db that the
	// running box owns. Opening it read-write here would race with the box on
	// Android (bbolt uses per-process POSIX locks there, so a second read-write
	// handle in the same process does not block the box) and corrupt the
	// freelist ("page N already freed") on profile switches.
	scope := adapter.NewScope(ctx, boxLog.NewNOPFactory().Logger())
	defer scope.Close()
	cacheFile := cachefile.NewReadOnly(ctx, cacheFilePath)
	if err = cacheFile.Start(adapter.StartStateInitialize, scope); err != nil {
		if os.IsNotExist(err) {
			// No cache file yet: nothing to load, and not an error to surface.
			return "", nil
		}
		return "", err
	}
	savedConfig := cacheFile.LoadMASQUEConfig(tag)
	if savedConfig == nil {
		return "", nil
	}
	return string(savedConfig.Content), nil
}
