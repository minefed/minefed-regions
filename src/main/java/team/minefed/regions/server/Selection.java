package team.minefed.regions.server;

import java.util.ArrayList;
import java.util.List;
import team.minefed.regions.model.Geometry;

/** A player's unsaved polygon draft. Rings are closed only when submitted to the API. */
public final class Selection {
    private final List<List<List<double[]>>> polygons = new ArrayList<>();
    private int part, ring;
    private long baseRevision = -1;

    public Selection() { clear(); }
    public void clear() {
        polygons.clear(); polygons.add(new ArrayList<>()); polygons.get(0).add(new ArrayList<>());
        part = 0; ring = 0; baseRevision = -1;
    }
    public void captureRevision(long revision) { if (baseRevision < 0) baseRevision = revision; }
    public void rebase(long revision) { baseRevision = revision; }
    public long baseRevision() { return baseRevision; }
    public List<double[]> current() { return polygons.get(part).get(ring); }
    public int count() { return polygons.stream().flatMap(List::stream).mapToInt(List::size).sum(); }
    public void point(double x, double z) {
        if (count() >= 4000) throw new IllegalArgumentException("선택 정점은 최대 4000개입니다.");
        if (!Double.isFinite(x) || !Double.isFinite(z) || Math.abs(x) > 30000000 || Math.abs(z) > 30000000)
            throw new IllegalArgumentException("좌표가 월드 범위를 벗어났습니다.");
        current().add(new double[]{x,z});
    }
    public void undo() {
        if (current().isEmpty()) throw new IllegalArgumentException("현재 고리에 지점이 없습니다.");
        current().remove(current().size()-1);
    }
    public void newPart() {
        requireComplete();
        if (polygons.size() >= 32) throw new IllegalArgumentException("분리 영역은 최대 32개입니다.");
        polygons.add(new ArrayList<>()); part = polygons.size()-1; ring = 0;
        polygons.get(part).add(new ArrayList<>());
    }
    public void hole() {
        requireComplete();
        if (polygons.get(part).size() >= 32) throw new IllegalArgumentException("고리는 영역마다 최대 32개입니다.");
        polygons.get(part).add(new ArrayList<>()); ring = polygons.get(part).size()-1;
    }
    public void focus(int part, int ring) {
        if (part < 1 || part > polygons.size() || ring < 1 || ring > polygons.get(part-1).size())
            throw new IllegalArgumentException("영역·고리 번호는 1부터 시작합니다.");
        this.part = part-1; this.ring = ring-1;
    }
    public void removeRing() {
        if (ring == 0) {
            if (polygons.size() == 1) { clear(); return; }
            polygons.remove(part); part = 0; ring = 0;
        } else { polygons.get(part).remove(ring); ring = 0; }
    }
    public void vertex(int index, double x, double z) {
        if (index < 1 || index > current().size()) throw new IllegalArgumentException("유효한 정점 번호가 필요합니다.");
        // Reuse point's coordinate validation without altering the draft on failure.
        if (!Double.isFinite(x) || !Double.isFinite(z) || Math.abs(x) > 30000000 || Math.abs(z) > 30000000)
            throw new IllegalArgumentException("좌표가 월드 범위를 벗어났습니다.");
        current().set(index-1,new double[]{x,z});
    }
    public void removeVertex(int index) {
        if (index < 1 || index > current().size()) throw new IllegalArgumentException("유효한 정점 번호가 필요합니다.");
        current().remove(index-1);
    }
    private void requireComplete() {
        if (current().size() < 3) throw new IllegalArgumentException("현재 고리에 세 지점 이상을 지정하세요.");
    }
    public Geometry geometry() {
        double[][][][] result = new double[polygons.size()][][][];
        for (int p = 0; p < polygons.size(); p++) {
            result[p] = new double[polygons.get(p).size()][][];
            for (int r = 0; r < polygons.get(p).size(); r++) {
                List<double[]> points = polygons.get(p).get(r);
                if (points.size() < 3) throw new IllegalArgumentException("모든 고리에 세 지점 이상이 필요합니다.");
                double[][] closed = new double[points.size()+1][];
                for (int i=0; i<points.size(); i++) closed[i] = points.get(i).clone();
                closed[points.size()] = points.get(0).clone(); result[p][r] = closed;
            }
        }
        return new Geometry("MultiPolygon",result);
    }
    public void load(Geometry shape) {
        polygons.clear();
        baseRevision = -1;
        for (double[][][] polygon : shape.coordinates()) {
            List<List<double[]>> rings = new ArrayList<>();
            for (double[][] closed : polygon) {
                List<double[]> points = new ArrayList<>();
                for (int i=0; i<closed.length-1; i++) points.add(closed[i].clone());
                rings.add(points);
            }
            polygons.add(rings);
        }
        part = 0; ring = 0;
    }
    public String description() {
        StringBuilder result = new StringBuilder("기준 버전 ").append(baseRevision).append(" · 영역 ").append(part+1).append('/').append(polygons.size())
                .append(" · 고리 ").append(ring+1).append('/').append(polygons.get(part).size()).append(" · 정점 ").append(count());
        for (int i=0; i<current().size() && i<24; i++)
            result.append("\n").append(i+1).append(": ").append(current().get(i)[0]).append(", ").append(current().get(i)[1]);
        if (current().size()>24) result.append("\n… /region select vertex <번호> <x> <z> 로 수정");
        return result.toString();
    }
}
