(function (root) {
    "use strict";

    const SET_ID = "minefed-regions";

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
        const match = /^r\.([A-Za-z0-9_-]+)\.([A-Za-z0-9_-]*)\.(\d+)\.(shape|casing|label)\.\d+$/.exec(key);
        return match ? {id: match[1], parentId: match[2], level: Number(match[3]), part: match[4]} : null;
    }

    // Labels: keep the deepest eligible region on each branch; branches ending early stay visible.
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

    // Boundaries: parents stay drawn while their subdivisions appear, so zooming in adds detail.
    function selectBoundaryIds(regions, level) {
        return new Set(regions.filter(region => region.level <= level).map(region => region.id));
    }

    function ringContains(ring, x, z) {
        let inside = false;
        for (let i = 0, j = ring.length - 1; i < ring.length; j = i++) {
            const a = ring[i], b = ring[j];
            if ((a.z > z) !== (b.z > z) && x < (b.x - a.x) * (z - a.z) / (b.z - a.z) + a.x) inside = !inside;
        }
        return inside;
    }

    function polygonContains(polygon, x, z) {
        if (!ringContains(polygon.shape, x, z)) return false;
        return !(polygon.holes || []).some(hole => ringContains(hole, x, z));
    }

    function ringArea(ring) {
        let area = 0;
        for (let i = 0, j = ring.length - 1; i < ring.length; j = i++) area += (ring[j].x + ring[i].x) * (ring[j].z - ring[i].z);
        return Math.abs(area / 2);
    }

    function polygonArea(polygon) {
        return Math.max(0, ringArea(polygon.shape) - (polygon.holes || []).reduce((sum, hole) => sum + ringArea(hole), 0));
    }

    /** Deepest region containing the point, or null. */
    function regionAt(regions, x, z) {
        let best = null;
        for (const region of regions) {
            if (best && region.level <= best.level) continue;
            if (region.polygons.some(polygon => polygonContains(polygon, x, z))) best = region;
        }
        return best;
    }

    const functions = {levelForDistance, parseKey, selectVisibleIds, selectBoundaryIds, polygonContains, polygonArea, regionAt};
    if (typeof module !== "undefined" && module.exports) module.exports = functions;
    if (!root?.document) return;

    const numberFormat = value => Math.round(value).toLocaleString("ko-KR");

    function install() {
        const app = root.bluemap;
        if (!app?.mapViewer?.markers || !app.events) return;
        if (app.__minefedRegionsInstalled) return;
        app.__minefedRegionsInstalled = true;

        function update() {
            const set = app.mapViewer.markers.markerSets.get(SET_ID);
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
            const all = Array.from(regions.values());
            const labels = selectVisibleIds(all, level);
            const boundaries = selectBoundaryIds(all, level);
            for (const [marker, region] of entries) {
                if (region.part === "label") {
                    marker.visible = labels.has(region.id);
                    continue;
                }
                marker.visible = boundaries.has(region.id);
                // Draw casings first and keep the transparent fills from swallowing map clicks.
                if (marker.border) marker.border.renderOrder = region.part === "casing" ? -3 - region.level * 2 : -2 - region.level * 2;
                if (!marker.__minefedRegionsPassive) {
                    marker.__minefedRegionsPassive = true;
                    for (const part of [marker.fill, marker.border]) if (part) part.raycast = () => {};
                }
            }
        }
        // This runs immediately before marker rendering, including after live marker replacement.
        app.events.addEventListener("bluemapRenderFrame", update);
        update();

        if (app.ui && typeof app.ui.openPlace === "function") installPanel(app, app.ui);
        else if (typeof root.document.addEventListener === "function") {
            // Without the map UI, a click on a label toggles its address.
            root.document.addEventListener("click", event => {
                const label = event.target && event.target.closest && event.target.closest(".minefed-region-label");
                if (label) label.classList.toggle("minefed-region-open");
            });
        }
        app.mapViewer.redraw();
    }

    function installPanel(app, ui) {
        let cache = null, cachedAt = 0;

        function index() {
            const now = Date.now();
            if (cache && now - cachedAt < 2000) return cache;
            const set = app.mapViewer.markers.markerSets.get(SET_ID);
            const result = new Map();
            if (set) set.markers.forEach((marker, key) => {
                const meta = parseKey(key);
                if (!meta || meta.part === "casing") return;
                let region = result.get(meta.id);
                if (!region) {
                    region = {id: meta.id, parentId: meta.parentId, level: meta.level, name: "", address: "", polygons: [], anchor: null, label: null};
                    result.set(meta.id, region);
                }
                if (meta.part === "label") {
                    region.name = region.name || marker.data.label || "";
                    const address = marker.element && marker.element.querySelector(".minefed-region-address");
                    region.address = address ? address.textContent : region.address;
                    if (!region.anchor) region.anchor = {x: marker.position.x, y: marker.position.y, z: marker.position.z};
                    region.label = marker;
                } else {
                    const data = marker._markerData || {};
                    if (Array.isArray(data.shape) && data.shape.length > 2) {
                        region.polygons.push({shape: data.shape, holes: Array.isArray(data.holes) ? data.holes : [], y: data.shapeY || 64});
                    }
                    region.name = region.name || marker.data.label || "";
                }
            });
            for (const region of result.values()) {
                region.area = region.polygons.reduce((sum, polygon) => sum + polygonArea(polygon), 0);
                if (!region.anchor && region.polygons.length) {
                    const ring = region.polygons[0].shape;
                    region.anchor = {x: ring.reduce((s, p) => s + p.x, 0) / ring.length, y: 64, z: ring.reduce((s, p) => s + p.z, 0) / ring.length};
                }
            }
            cache = result;
            cachedAt = now;
            return result;
        }

        const children = id => [...index().values()].filter(region => region.parentId === id)
            .sort((a, b) => a.name.localeCompare(b.name, "ko"));

        function openRegion(id, options = {}) {
            const regions = index(), region = regions.get(id);
            if (!region) return false;
            const parent = region.parentId ? regions.get(region.parentId) : null;
            const subdivisions = children(id);
            const largest = region.polygons.slice().sort((a, b) => polygonArea(b) - polygonArea(a))[0];
            const size = Math.sqrt(Math.max(region.area, 1));
            ui.openPlace({
                key: `region:${id}`,
                kind: "행정구역",
                title: region.name || "행정구역",
                subtitle: parent ? parent.name : "",
                icon: "region",
                color: "#5e35b1",
                position: region.anchor,
                previewScale: Math.max(0.4, Math.min(30, size / 200)),
                push: !!options.push,
                rows: () => {
                    const rows = [];
                    if (region.address) rows.push({icon: "place", text: region.address, sub: "주소"});
                    rows.push({icon: "ruler", text: `약 ${numberFormat(region.area)}블록²`, sub: "면적"});
                    if (parent) rows.push({icon: "layers", text: parent.name, sub: "상위 행정구역", onClick: () => openRegion(parent.id, {push: true, fly: true})});
                    return rows;
                },
                mount: element => {
                    if (largest) ui.highlightShape(largest.shape, largest.y, largest.holes);
                    if (subdivisions.length) element.appendChild(childList(subdivisions));
                    return () => ui.clearHighlight();
                }
            });
            if (options.fly && region.anchor) ui.flyTo(region.anchor, {distance: Math.max(120, Math.min(8000, size * 1.4))});
            return true;
        }

        function childList(regions) {
            const section = root.document.createElement("section");
            section.className = "minefed-region-children";
            const heading = root.document.createElement("h3");
            heading.textContent = `하위 행정구역 ${regions.length}곳`;
            const hint = root.document.createElement("p");
            hint.textContent = "지도를 확대하면 경계가 나타납니다.";
            section.append(heading, hint);
            for (const child of regions) {
                const button = root.document.createElement("button"), name = root.document.createElement("span"), meta = root.document.createElement("span");
                button.type = "button";
                button.className = "minefed-region-child";
                name.className = "minefed-region-child-name";
                name.textContent = child.name;
                meta.className = "minefed-region-child-meta";
                meta.textContent = `약 ${numberFormat(child.area)}블록²`;
                button.append(name, meta);
                button.addEventListener("click", () => openRegion(child.id, {push: true, fly: true}));
                section.appendChild(button);
            }
            return section;
        }

        ui.registerMarkerHandler(marker => {
            const set = app.mapViewer.markers.markerSets.get(SET_ID);
            if (!set) return false;
            for (const [key, candidate] of set.markers) {
                if (candidate !== marker) continue;
                const meta = parseKey(key);
                return meta ? openRegion(meta.id) : false;
            }
            return false;
        });

        ui.registerAddressProvider((x, z) => {
            const region = regionAt([...index().values()], x, z);
            return region ? {label: region.address || region.name, open: () => openRegion(region.id, {push: true})} : null;
        });

        ui.registerSearchProvider("regions", query => {
            const q = query.toLowerCase(), regions = index(), results = [];
            for (const region of regions.values()) {
                if (!region.name.toLowerCase().includes(q) && !region.address.toLowerCase().includes(q)) continue;
                const parent = region.parentId ? regions.get(region.parentId) : null;
                results.push({
                    id: region.id, title: region.name, keywords: region.address,
                    subtitle: ["행정구역", parent ? parent.name : ""].filter(Boolean).join(" · "),
                    icon: "region", color: "#5e35b1",
                    select: () => openRegion(region.id, {fly: true})
                });
            }
            return results;
        });

        ui.registerChip({
            id: "regions", label: "행정구역", icon: "region", order: 50,
            visible: () => index().size > 0,
            open: () => ui.openList({
                id: "regions", chipId: "regions", title: "행정구역",
                empty: "공개된 행정구역이 없습니다",
                items: () => {
                    const regions = index();
                    return [...regions.values()]
                        .filter(region => !region.parentId || !regions.has(region.parentId))
                        .sort((a, b) => a.name.localeCompare(b.name, "ko"))
                        .map(region => ({
                            key: `region:${region.id}`,
                            title: region.name,
                            subtitle: [children(region.id).length ? `하위 구역 ${children(region.id).length}곳` : "", `약 ${numberFormat(region.area)}블록²`].filter(Boolean).join(" · "),
                            icon: "region", color: "#5e35b1",
                            onClick: () => openRegion(region.id, {push: true, fly: true})
                        }));
                }
            })
        });

        ui.registerResolver("region", key => openRegion(key.slice("region:".length), {push: true, fly: true}));
    }

    install();
})(typeof window === "undefined" ? null : window);
