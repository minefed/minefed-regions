package team.minefed.regions.server;

import com.google.gson.stream.JsonReader;
import com.google.gson.stream.JsonToken;
import team.minefed.regions.model.Geometry;
import team.minefed.regions.model.SnapshotValidator;

import java.io.IOException;
import java.io.InputStream;
import java.io.StringReader;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/** Reads untrusted local geometry without repairing it or retaining command/actor metadata. */
public final class GeoJsonImport {
    public static final int MAX_FILE_BYTES = 1024 * 1024;

    private GeoJsonImport() {}

    public record Result(String filename, String sha256, Geometry geometry, int polygons, int rings,
                         int verticesIncludingClosure, int verticesExcludingClosure) {}

    /** File I/O and parsing belong on the controller's worker, never the server tick thread. */
    public static Result read(Path importsDirectory, String filename) throws IOException {
        validateFilename(filename);
        require(!Files.isSymbolicLink(importsDirectory), "imports 폴더의 심볼릭 링크는 허용하지 않습니다.");
        Path directory = importsDirectory.toRealPath();
        require(Files.isDirectory(directory), "imports 경로가 폴더가 아닙니다.");
        Path file = directory.resolve(filename);
        BasicFileAttributes attributes = Files.readAttributes(file, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
        require(attributes.isRegularFile() && !attributes.isSymbolicLink(), "imports 폴더 안의 일반 파일만 읽을 수 있습니다. 심볼릭 링크는 허용하지 않습니다.");
        require(attributes.size() <= MAX_FILE_BYTES, "GeoJSON 파일은 1 MiB 이하여야 합니다.");
        byte[] bytes;
        // NOFOLLOW_LINKS also protects the final component if it changes after the attributes check.
        try (InputStream input = Files.newInputStream(file, StandardOpenOption.READ, LinkOption.NOFOLLOW_LINKS)) {
            bytes = input.readNBytes(MAX_FILE_BYTES + 1);
        }
        require(bytes.length <= MAX_FILE_BYTES, "GeoJSON 파일은 1 MiB 이하여야 합니다.");
        double[][][][] coordinates = parse(decode(bytes));
        int vertices = SnapshotValidator.validateCoordinates(coordinates);
        int rings = 0;
        for (double[][][] polygon : coordinates) rings += polygon.length;
        return new Result(filename, sha256(bytes), new Geometry("MultiPolygon", coordinates), coordinates.length,
                rings, vertices, vertices - rings);
    }

    private static void validateFilename(String filename) {
        require(filename != null && !filename.isBlank(), "GeoJSON 파일명을 입력하세요.");
        require(!filename.contains("..") && filename.chars().noneMatch(character -> character < 32
                        || "<>:\"/\\|?*".indexOf(character) >= 0),
                "경로 대신 imports 폴더 안의 파일명만 입력하세요.");
        require(filename.toLowerCase(Locale.ROOT).endsWith(".geojson"), ".geojson 파일만 지원합니다.");
        Path path = Path.of(filename);
        require(!path.isAbsolute() && path.getNameCount() == 1, "경로 대신 파일명만 입력하세요.");
    }

    private static String decode(byte[] bytes) {
        try {
            return StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(bytes)).toString();
        } catch (CharacterCodingException exception) {
            throw new IllegalArgumentException("GeoJSON 파일은 올바른 UTF-8이어야 합니다.", exception);
        }
    }

    private static double[][][][] parse(String json) {
        rejectLegacyJsonExtensions(json);
        try (JsonReader reader = new JsonReader(new StringReader(json))) {
            reader.setLenient(false);
            expect(reader, JsonToken.BEGIN_OBJECT, "GeoJSON 루트는 MultiPolygon 객체여야 합니다.");
            reader.beginObject();
            String type = null;
            double[][][][] coordinates = null;
            Set<String> fields = new HashSet<>();
            while (reader.hasNext()) {
                String field = reader.nextName();
                require(fields.add(field), "중복 JSON 필드: " + field);
                switch (field) {
                    case "type" -> {
                        expect(reader, JsonToken.STRING, "GeoJSON type은 MultiPolygon 문자열이어야 합니다.");
                        type = reader.nextString();
                        require(!"Feature".equals(type) && !"FeatureCollection".equals(type),
                                "Feature와 FeatureCollection은 지원하지 않습니다. 루트 MultiPolygon 객체를 사용하세요.");
                        require("MultiPolygon".equals(type), "루트 MultiPolygon 객체만 지원합니다.");
                    }
                    case "coordinates" -> coordinates = readCoordinates(reader);
                    default -> reader.skipValue();
                }
            }
            reader.endObject();
            expect(reader, JsonToken.END_DOCUMENT, "JSON 객체 뒤에 추가 내용이 있습니다.");
            require("MultiPolygon".equals(type), "GeoJSON type은 MultiPolygon이어야 합니다.");
            require(coordinates != null, "GeoJSON coordinates가 없습니다.");
            return coordinates;
        } catch (IOException exception) {
            throw new IllegalArgumentException("올바른 JSON 파일이 아닙니다: " + exception.getMessage(), exception);
        }
    }

    // Gson 2.10's legacy strict reader still accepts mixed-case literals and some invalid string
    // escapes/control characters. Check their spelling before the streaming structural parser.
    private static void rejectLegacyJsonExtensions(String json) {
        boolean quoted = false;
        for (int index = 0; index < json.length(); index++) {
            char character = json.charAt(index);
            if (quoted) {
                require(character >= 0x20, "JSON 문자열에 이스케이프하지 않은 제어 문자가 있습니다.");
                if (character == '"') quoted = false;
                else if (character == '\\') {
                    require(++index < json.length(), "JSON 문자열 이스케이프가 끝나지 않았습니다.");
                    char escape = json.charAt(index);
                    require("\"\\/bfnrtu".indexOf(escape) >= 0, "올바르지 않은 JSON 문자열 이스케이프입니다.");
                    if (escape == 'u') {
                        require(index + 4 < json.length(), "JSON Unicode 이스케이프는 4자리여야 합니다.");
                        for (int offset = 0; offset < 4; offset++) {
                            char digit = json.charAt(++index);
                            require(digit >= '0' && digit <= '9' || digit >= 'a' && digit <= 'f'
                                    || digit >= 'A' && digit <= 'F', "올바르지 않은 JSON Unicode 이스케이프입니다.");
                        }
                    }
                }
            } else if (character == '"') quoted = true;
            else if (character == 't' || character == 'f' || character == 'n') {
                String literal = character == 't' ? "true" : character == 'f' ? "false" : "null";
                require(json.startsWith(literal, index), "JSON 리터럴은 소문자 true, false, null만 허용합니다.");
                index += literal.length() - 1;
            } else {
                require("{}[]:, \t\r\n-+.eE0123456789".indexOf(character) >= 0
                        || index == 0 && character == '\ufeff', "올바르지 않은 JSON 문자입니다.");
            }
        }
        require(!quoted, "JSON 문자열이 끝나지 않았습니다.");
    }

    private static double[][][][] readCoordinates(JsonReader reader) throws IOException {
        expect(reader, JsonToken.BEGIN_ARRAY, "coordinates는 영역 배열이어야 합니다.");
        reader.beginArray();
        List<double[][][]> polygons = new ArrayList<>();
        int vertices = 0;
        while (reader.hasNext()) {
            expect(reader, JsonToken.BEGIN_ARRAY, "영역은 고리 배열이어야 합니다.");
            reader.beginArray();
            List<double[][]> rings = new ArrayList<>();
            while (reader.hasNext()) {
                expect(reader, JsonToken.BEGIN_ARRAY, "고리는 정점 배열이어야 합니다.");
                reader.beginArray();
                List<double[]> points = new ArrayList<>();
                while (reader.hasNext()) {
                    require(++vertices <= SnapshotValidator.MAX_GEOMETRY_VERTICES,
                            "폐합점을 포함한 전체 정점 수는 4,096개 이하여야 합니다.");
                    expect(reader, JsonToken.BEGIN_ARRAY, "정점은 [X, Z] 배열이어야 합니다.");
                    reader.beginArray();
                    double[] point = {readCoordinate(reader), readCoordinate(reader)};
                    require(!reader.hasNext(), "정점은 정확히 두 개의 숫자 [X, Z]여야 합니다.");
                    reader.endArray();
                    points.add(point);
                }
                reader.endArray();
                require(points.size() >= 4, "고리는 폐합점을 포함하여 최소 4개 좌표여야 합니다.");
                rings.add(points.toArray(double[][]::new));
            }
            reader.endArray();
            require(!rings.isEmpty(), "빈 영역은 허용하지 않습니다.");
            polygons.add(rings.toArray(double[][][]::new));
        }
        reader.endArray();
        require(!polygons.isEmpty(), "빈 MultiPolygon은 허용하지 않습니다.");
        return polygons.toArray(double[][][][]::new);
    }

    private static double readCoordinate(JsonReader reader) throws IOException {
        expect(reader, JsonToken.NUMBER, "정점은 정확히 두 개의 유한한 숫자 [X, Z]여야 합니다.");
        double coordinate = Double.parseDouble(reader.nextString());
        require(Double.isFinite(coordinate) && Math.abs(coordinate) <= SnapshotValidator.MAX_COORDINATE,
                "좌표는 절댓값 30,000,000 이하의 유한한 숫자여야 합니다.");
        return coordinate;
    }

    private static void expect(JsonReader reader, JsonToken token, String message) throws IOException {
        require(reader.peek() == token, message);
    }

    private static String sha256(byte[] bytes) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is unavailable", exception);
        }
    }

    private static void require(boolean condition, String message) {
        if (!condition) throw new IllegalArgumentException(message);
    }
}
