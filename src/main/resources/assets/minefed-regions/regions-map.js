(function (root) {
    "use strict";

    // One distance determines the whole viewport's category in both BlueMap map camera modes.
    function levelForDistance(distance, maximumLevel = 6) {
        const maximum = Math.max(0, Math.min(63, maximumLevel));
        if (distance === Infinity) return 0;
        if (!Number.isFinite(distance) || distance <= 0) return maximum;
        // BlueMap's map camera stops at distance 5. Compress unusually deep trees so every
        // configured level remains reachable; normal country/province/city trees keep familiar steps.
        const step = maximum > 6 ? Math.pow(16000 / 5, 1 / maximum) : 4;
        return Math.max(0, Math.min(maximum, Math.floor(Math.log(16000 / distance) / Math.log(step)) + 1));
    }

    function parseKey(key) {
        const match = /^r\.([A-Za-z0-9_-]+)\.([A-Za-z0-9_-]*)\.(\d+)\.(shape|label)\.\d+$/.exec(key);
        return match ? {id: match[1], parentId: match[2], level: Number(match[3])} : null;
    }

    // Keep the deepest eligible region on each branch; branches ending early stay visible.
    // Per-region overrides also work when an intermediate ancestor has a higher display level.
    function selectVisibleIds(regions, level) {
        const byId = new Map(regions.map(region => [region.id, region]));
        const visible = new Set(regions.filter(region => region.level <= level).map(region => region.id));
        for (const region of regions) {
            if (region.level > level) continue;
            let parentId = region.parentId;
            const visited = new Set([region.id]);
            while (parentId && !visited.has(parentId)) {
                visited.add(parentId);
                visible.delete(parentId);
                parentId = byId.get(parentId)?.parentId;
            }
        }
        return visible;
    }

    const functions = {levelForDistance, parseKey, selectVisibleIds};
    if (typeof module !== "undefined" && module.exports) module.exports = functions;
    if (!root?.document) return;

    function install() {
        const app = root.bluemap;
        if (!app?.mapViewer?.markers || !app.events) return;
        if (app.__minefedRegionsInstalled) return;
        app.__minefedRegionsInstalled = true;
        function update() {
            const set = app.mapViewer.markers.markerSets.get("minefed-regions");
            if (!set) return;
            const regions = new Map();
            const entries = [];
            set.markers.forEach((marker, key) => {
                const region = parseKey(key);
                if (!region) return;
                regions.set(region.id, region);
                entries.push([marker, region]);
            });
            const maximum = Math.max(0, ...Array.from(regions.values(), region => region.level));
            const level = levelForDistance(app.mapViewer.controlsManager.distance, maximum);
            const visible = selectVisibleIds(Array.from(regions.values()), level);
            for (const [marker, region] of entries) marker.visible = visible.has(region.id);
        }
        // This runs immediately before marker rendering, including after live marker replacement.
        app.events.addEventListener("bluemapRenderFrame", update);
        update();
        app.mapViewer.redraw();
    }
    install();
})(typeof window === "undefined" ? null : window);
