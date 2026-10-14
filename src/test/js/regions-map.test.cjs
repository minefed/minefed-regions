const {test} = require("node:test");
const assert = require("node:assert/strict");
const fs = require("node:fs");
const vm = require("node:vm");
const path = require("node:path");
const script = path.join(__dirname, "../../main/resources/assets/minefed-regions/regions-map.js");
const {levelForDistance, parseKey, selectVisibleIds, selectBoundaryIds, polygonContains, polygonArea, regionAt} = require(script);

test("one camera distance selects one synchronized category", () => {
    assert.equal(levelForDistance(50000), 0);
    assert.equal(levelForDistance(8000), 1);
    assert.equal(levelForDistance(2000), 2);
    assert.equal(levelForDistance(500), 3);
    assert.equal(levelForDistance(100), 4);
});

test("variable-depth branches keep their last region visible", () => {
    const regions = [
        {id: "country", parentId: "", level: 0},
        {id: "suin", parentId: "country", level: 1},
        {id: "province", parentId: "country", level: 1},
        {id: "city", parentId: "province", level: 2}
    ];
    assert.deepEqual([...selectVisibleIds(regions, 0)], ["country"]);
    assert.deepEqual([...selectVisibleIds(regions, 1)], ["suin", "province"]);
    assert.deepEqual([...selectVisibleIds(regions, 4)], ["suin", "city"]);
});

test("all 64 levels remain reachable within BlueMap's supported zoom range", () => {
    assert.equal(levelForDistance(50000, 63), 0);
    assert.equal(levelForDistance(5, 63), 63);
    assert.equal(levelForDistance(Infinity, 63), 0);
    const step = Math.pow(3200, 1 / 63);
    for (let level = 1; level < 63; level++) {
        assert.equal(levelForDistance(16000 / Math.pow(step, level - 0.5), 63), level);
    }
});

test("display override and cyclic malformed metadata do not hang", () => {
    const regions = [
        {id: "a", parentId: "", level: 0},
        {id: "b", parentId: "a", level: 4},
        {id: "c", parentId: "b", level: 2}
    ];
    assert.deepEqual([...selectVisibleIds(regions, 2)], ["c"]);
    assert.equal(selectVisibleIds([{id:"a",parentId:"b",level:0},{id:"b",parentId:"a",level:0}], 1).size, 0);
});

test("parses only owned marker metadata", () => {
    assert.deepEqual(parseKey("r.YQ..0.shape.2"), {id:"YQ",parentId:"",level:0,part:"shape"});
    assert.equal(parseKey("mtr.station"), null);
    assert.equal(parseKey("r.YQ..NaN.label.0"), null);
});

test("live render updates only the owned set and preserves its user toggle", () => {
    const country = {visible:true}, city = {visible:false}, other = {visible:true};
    const regionSet = {visible:false, markers:new Map([
        ["r.YQ..0.label.0", country], ["r.Yg.YQ.1.label.0", city]
    ])};
    const listeners = new Map();
    const window = {document:{}, bluemap:{
        events:{addEventListener:(type, callback) => listeners.set(type, callback)},
        mapViewer:{
            controlsManager:{distance:50000},
            markers:{markerSets:new Map([["minefed-regions",regionSet],["mtr",{markers:new Map([["a",other]])}]])},
            redraw() {}
        }
    }};
    vm.runInNewContext(fs.readFileSync(script, "utf8"), {window});
    assert.equal(country.visible, true);
    assert.equal(city.visible, false);
    window.bluemap.mapViewer.controlsManager.distance = 8000;
    listeners.get("bluemapRenderFrame")();
    assert.equal(country.visible, false);
    assert.equal(city.visible, true);
    assert.equal(regionSet.visible, false);
    assert.equal(other.visible, true);
});

test("boundaries keep ancestors drawn while labels move to the deepest level", () => {
    const regions = [
        {id: "city", parentId: "", level: 0},
        {id: "district", parentId: "city", level: 1},
        {id: "dong", parentId: "district", level: 2}
    ];
    assert.deepEqual([...selectBoundaryIds(regions, 1)], ["city", "district"]);
    assert.deepEqual([...selectVisibleIds(regions, 1)], ["district"]);
    assert.deepEqual(parseKey("r.YQ.Yg.1.casing.0"), {id: "YQ", parentId: "Yg", level: 1, part: "casing"});
});

test("area and point lookup respect holes and prefer the deepest region", () => {
    const square = (x0, z0, size) => [{x: x0, z: z0}, {x: x0 + size, z: z0}, {x: x0 + size, z: z0 + size}, {x: x0, z: z0 + size}];
    const ring = {shape: square(0, 0, 20), holes: [square(5, 5, 10)]};
    assert.equal(polygonArea(ring), 300);
    assert.equal(polygonContains(ring, 2, 2), true);
    assert.equal(polygonContains(ring, 10, 10), false);
    const regions = [
        {id: "parent", level: 0, polygons: [{shape: square(0, 0, 100)}]},
        {id: "child", level: 1, polygons: [{shape: square(0, 0, 50)}]}
    ];
    assert.equal(regionAt(regions, 10, 10).id, "child");
    assert.equal(regionAt(regions, 80, 80).id, "parent");
    assert.equal(regionAt(regions, 200, 200), null);
});

function fakeRegionMarkers() {
    const square = [{x: 0, z: 0}, {x: 100, z: 0}, {x: 100, z: 100}, {x: 0, z: 100}];
    const inner = [{x: 0, z: 0}, {x: 40, z: 0}, {x: 40, z: 40}, {x: 0, z: 40}];
    const boundary = (shape) => ({visible: true, data: {label: ""}, _markerData: {shape, holes: [], shapeY: 64}, fill: {}, border: {renderOrder: 0}});
    const label = (name, address, x, z) => ({visible: true, data: {label: name}, position: {x, y: 64, z},
        element: {querySelector: () => ({textContent: address})}});
    return new Map([
        ["r.YQ..0.casing.0", boundary(square)], ["r.YQ..0.shape.0", boundary(square)], ["r.YQ..0.label.0", label("중앙구", "민페드시 중앙구", 50, 50)],
        ["r.Yg.YQ.1.casing.0", boundary(inner)], ["r.Yg.YQ.1.shape.0", boundary(inner)], ["r.Yg.YQ.1.label.0", label("시청동", "민페드시 중앙구 시청동", 20, 20)]
    ]);
}

test("live render draws casings under lines and lets map clicks pass through boundaries", () => {
    const markers = fakeRegionMarkers();
    const listeners = new Map();
    const window = {document: {}, bluemap: {
        events: {addEventListener: (type, callback) => listeners.set(type, callback)},
        mapViewer: {controlsManager: {distance: 2000}, markers: {markerSets: new Map([["minefed-regions", {markers}]])}, redraw() {}}
    }};
    vm.runInNewContext(fs.readFileSync(script, "utf8"), {window});
    assert.equal(markers.get("r.YQ..0.shape.0").visible, true, "parent boundary stays visible");
    assert.equal(markers.get("r.Yg.YQ.1.shape.0").visible, true);
    assert.equal(markers.get("r.YQ..0.label.0").visible, false, "only the deepest label is shown");
    assert.ok(markers.get("r.YQ..0.casing.0").border.renderOrder < markers.get("r.YQ..0.shape.0").border.renderOrder);
    assert.equal(markers.get("r.YQ..0.shape.0").fill.raycast(), undefined);
    window.bluemap.mapViewer.controlsManager.distance = 50000;
    listeners.get("bluemapRenderFrame")();
    assert.equal(markers.get("r.Yg.YQ.1.shape.0").visible, false);
    assert.equal(markers.get("r.YQ..0.label.0").visible, true);
});

test("map UI integration opens region details, names positions and highlights the area", () => {
    const markers = fakeRegionMarkers();
    const calls = {places: [], highlights: 0, cleared: 0};
    const ui = {
        markerHandlers: [], addressProviders: [], chips: [], resolvers: new Map(), search: new Map(),
        openPlace(place) { calls.places.push(place); },
        openList(list) { calls.list = list; },
        highlightShape() { calls.highlights++; },
        clearHighlight() { calls.cleared++; },
        flyTo(position) { calls.flown = position; },
        registerMarkerHandler(handler) { this.markerHandlers.push(handler); },
        registerAddressProvider(provider) { this.addressProviders.push(provider); },
        registerSearchProvider(id, provider) { this.search.set(id, provider); },
        registerChip(chip) { this.chips.push(chip); },
        registerResolver(prefix, resolver) { this.resolvers.set(prefix, resolver); }
    };
    const window = {document: {}, bluemap: {
        ui, events: {addEventListener() {}},
        mapViewer: {controlsManager: {distance: 2000}, markers: {markerSets: new Map([["minefed-regions", {markers}]])}, redraw() {}}
    }};
    vm.runInNewContext(fs.readFileSync(script, "utf8"), {window});
    assert.equal(ui.addressProviders[0](10, 10).label, "민페드시 중앙구 시청동");
    assert.equal(ui.addressProviders[0](80, 80).label, "민페드시 중앙구");
    assert.equal(ui.addressProviders[0](500, 500), null);
    assert.equal(ui.markerHandlers[0](markers.get("r.YQ..0.label.0")), true);
    const place = calls.places.pop();
    assert.equal(place.key, "region:YQ");
    assert.equal(place.title, "중앙구");
    assert.ok(place.rows().some(row => row.text.includes("10,000")));
    assert.equal(ui.markerHandlers[0]({}), false);
    assert.equal(ui.search.get("regions")("시청")[0].title, "시청동");
    assert.equal(ui.chips[0].visible(), true);
    ui.chips[0].open();
    assert.equal(calls.list.items().map(item => item.title).join(","), "중앙구");
    assert.equal(ui.resolvers.get("region")("region:Yg"), true);
    assert.equal(calls.places.pop().subtitle, "중앙구");
    assert.equal(ui.resolvers.get("region")("region:missing"), false);
});
