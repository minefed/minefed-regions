package team.minefed.regions.model;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;

public record Snapshot(long revision, List<Region> regions, List<String> globalAdmins) {
    public static Snapshot empty() { return new Snapshot(0, List.of(), List.of()); }

    public Region find(String id) {
        if (id == null) return null;
        for (Region region : regions) if (id.equals(region.id())) return region;
        return null;
    }

    public List<Region> lineage(Region region) {
        List<Region> result = new ArrayList<>();
        HashSet<String> visited = new HashSet<>();
        while (region != null && visited.add(region.id())) {
            result.add(0, region);
            region = find(region.parentId());
        }
        return result;
    }

    public String address(Region region) {
        return String.join(" ", lineage(region).stream().map(Region::name).toList());
    }

    public int depth(Region region) { return lineage(region).size() - 1; }

    public boolean isGlobal(String uuid) { return globalAdmins.contains(uuid); }

    public boolean canCreateUnder(String uuid, String parentId) {
        if (isGlobal(uuid)) return true;
        return lineage(find(parentId)).stream().anyMatch(r -> r.managers().contains(uuid));
    }

    public boolean canEdit(String uuid, Region target) {
        return target != null && canCreateUnder(uuid, target.parentId());
    }

    /** Descend one branch, keeping addresses coherent even exactly on a shared edge. */
    public Region locate(double x, double z) {
        String parent = null;
        Region found = null;
        for (int level = 0; level <= regions.size(); level++) {
            final String expectedParent = parent;
            Region next = regions.stream()
                    .filter(r -> java.util.Objects.equals(expectedParent, r.parentId()) && r.geometry().covers(x, z))
                    .min(Comparator.comparing(Region::id)).orElse(null);
            if (next == null) return found;
            found = next;
            parent = next.id();
        }
        return found;
    }
}
