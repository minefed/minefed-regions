package team.minefed.regions.model;

import java.util.List;

public record Region(String id, String parentId, String name, Integer displayLevel,
                     Geometry geometry, List<String> managers) { }
