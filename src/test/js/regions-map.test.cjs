const {test} = require("node:test");
const assert = require("node:assert/strict");
const fs = require("node:fs");
const vm = require("node:vm");
const path = require("node:path");
const script = path.join(__dirname, "../../main/resources/assets/minefed-regions/regions-map.js");
const {levelForDistance, parseKey, selectVisibleIds} = require(script);

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
    assert.deepEqual(parseKey("r.YQ..0.shape.2"), {id:"YQ",parentId:"",level:0});
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
