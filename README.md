# Minefed Regions

일반 월드의 행정구역을 자유로운 다각형으로 지정하고, 현재 주소를 HUD와 BlueMap에
표시하는 Minecraft **1.20.4 / Fabric Loader 0.18.4 이상 / Java 17** 모드다.
서버와 클라이언트에 같은 JAR를 설치하며 Fabric API가 필요하다.
자체 작성한 모드 코드와 웹 자산은 [MIT](LICENSE)로 공개한다. 통합·추적 브랜치는
`minefed-1.20.4`다.

이 저장소는 모드와 설치 안내를 제공한다. 소스 등록·빌드와 운영 서버 설치,
API 배포, 운영 DB 마이그레이션은 별개다. 이번 개발에서 운영 환경에 자동 적용하지 않는다.
초기 지역 데이터는 비어 있고, 아래 좌표와 주소는 사용법 예시다.

## 주소와 권한

국가 → 주 → 시 → 구 → 동처럼 정해진 단계 수를 강제하지 않는다.
각 지역은 부모 하나를 가지며, 다음 주소를 모두 표현할 수 있다.

- 마인연방공화국 수인시 중구 운회동
- 마인연방공화국 연해주 청진시 강성구 인혁동

지번은 저장하지 않는다. 같은 부모 아래 지역 이름은 중복할 수 없지만, 서로 다른
시의 `중구`처럼 부모가 다르면 같은 이름을 사용할 수 있다.

경계는 X/Z 좌표에 따라 모든 높이에 적용된다. 오목한 경계, 떨어진 섬과 내부 구멍을
지원한다. 국가끼리 또는 형제 지역끼리 내부 면적이 겹칠 수 없고 경계선 접촉은 허용한다.
자식 전체가 부모 안에 있어야 하며, 부모 경계를 줄여 자식을 밖에 남겨둘 수 없다.
하위 지역이 있는 지역은 먼저 자식을 삭제하거나 다른 부모로 옮겨야 삭제할 수 있다.

전체 관리자는 **명시적으로 등록한 Minecraft UUID**로 구별한다. OP는 자동으로
전체 관리자가 되지 않는다. 국가 생성·삭제와 전체 관리자 임명은 전체 관리자 또는
서버 콘솔이 수행한다. 지역 관리자는 자신이 맡은 지역의 모든 하위 단계를 관리하지만
자기 지역의 이름·경계·소속·표시 단계·관리자 명단은 바꿀 수 없다. 따라서 하위 지역이
없는 동의 관리자는 편집할 대상이 없다. 직접 관리자 우선권이나 잠금은 없으며,
건축·파괴·출입 보호 기능도 포함하지 않는다.

## 서버와 API 연결

원본 데이터와 권한·기하 검증, 변경 이력은 `minefed-api`의 PostgreSQL에 저장한다.
기존 런처 gRPC와 별도의 선택적 HTTP 리스너를 사용한다.
API의 마이그레이션, TLS와 실행 절차는
[행정구역 API 문서](https://github.com/minefed/minefed-api/blob/main/docs/REGIONS_API.md)를 따른다.
웹 관리 화면은 아직 포함하지 않는다. 향후 웹 인증 어댑터가 같은 도메인 검증을
재사용할 수 있으며 서버 토큰을 웹 브라우저에 제공하면 안 된다.

먼저 별도로 준비한 API에서 지역 스키마를 적용하고, 다음 설정을 맞춘다.
API 기능은 `REGIONS_HTTP_ADDRESS`를 지정하지 않으면 꺼져 있다.

| 실행 프로세스 | 설정 | 값 또는 용도 |
| --- | --- | --- |
| API | `REGIONS_HTTP_ADDRESS` | 같은 호스트 예: `127.0.0.1:8091` |
| API | `REGIONS_WORLD_ID` | `minefed-overworld` |
| API | `REGIONS_SERVER_TOKEN` | 새로 생성한 충분히 긴 무작위 비밀 값 |
| Minecraft 서버 | `MINEFED_REGIONS_TOKEN` | 위 API 토큰과 동일한 값 |

API 토큰은 공백·쉼표 없이 32~4096자다. 실제 토큰은 서버의 비밀 환경 변수로
공급하고 소스, 클라이언트 설정, 채팅이나 BlueMap에 넣지 않는다.
Minecraft 서버 프로세스가 이 환경 변수를 읽을 수 있어야 한다.

모드를 처음 실행하면 `config/minefed-regions-server.json`을 만든다.

```json
{
  "apiBaseUrl": "http://127.0.0.1:8091",
  "worldId": "minefed-overworld",
  "tokenEnvironmentVariable": "MINEFED_REGIONS_TOKEN",
  "pollSeconds": 5
}
```

`worldId`는 API의 `REGIONS_WORLD_ID`와 정확히 같아야 한다. 모드는 영문·숫자로
시작하는 최대 96자의 영문·숫자·`_`·`-` ID를 받는다. 원격 API는 `https://` 주소를
사용한다. 평문 HTTP는 loopback에만 허용하며 리디렉션을 따라 토큰을 보내지 않는다.
설정과 환경 변수는 서버 시작 시 읽는다. 명령어로 설정을 재적용하는 기능은 없다.

게임에서 `/region status`가 `API 연결됨`인지 확인한 뒤 관리자를 지정한다.
API가 없는 클라이언트 단독 설치로 지역을 관리할 수는 없다.

## 처음 국가와 하위 지역 만들기

### 1. 전체 관리자 등록

서버 콘솔에서 실행한다. 콘솔에서는 앞의 `/`를 생략한다. 인증된 RCON도 서버 콘솔로 취급한다.

```text
region admin grant PlayerName
```

`PlayerName`은 접속 중인 실제 닉네임으로 바꾼다. 오프라인 사용자라면 닉네임 대신
그 서버에서 확인한 대시 포함 Minecraft UUID를 입력한다. 정상 로그인 서버의
`usercache.json`에서 이전 접속자의 이름과 UUID를 확인할 수 있다.
프록시 환경에서는 서버가 전달받는 UUID를 사용하며, 닉네임으로 임의 UUID를 만들지 않는다.
이후 전체 관리자는 게임에서도 `/region admin grant <닉네임|UUID>`를 사용할 수 있다.

### 2. 국가 경계 선택

등록된 관리자가 일반 월드에 접속해 다음 명령을 실행한다.

```text
/region wand
/region select clear
```

지급된 막대기로 경계의 블록을 순서대로 우클릭한다. 웅크린 채 우클릭하면 현재
고리의 마지막 점을 취소한다. 좌표는 클릭한 블록의 X/Z 모서리이며 높이는 무시한다.
세 점 이상을 선택하면 마지막 점과 첫 점을 자동으로 잇는다. 첫 점을 다시 찍을
필요는 없다. 선이 스스로 교차하지 않도록 경계를 따라 순서대로 찍는다.

정확한 숫자로 지정할 수도 있다. 다음은 연습용 사각형이다.

```text
/region select clear
/region select point 0 0
/region select point 1000 0
/region select point 1000 1000
/region select point 0 1000
/region select show
/region create root 마인연방공화국
```

`show`는 현재 고리를 현재 플레이어 높이의 입자로 잠시 표시한다.
API가 저장을 확인했다는 메시지를 받은 뒤 `/region list`로 국가 UUID를 확인한다.
선택은 생성 후 자동으로 비워지지 않으므로 다음 지역을 만들기 전에 `select clear`를 한다.
같은 선택을 의도적으로 재사용하려면 저장 결과를 확인한 뒤 `select rebase`로 기준 버전을 갱신한다.

### 3. 하위 지역 생성과 위임

국가 안에서 수인시 경계를 새로 선택한 다음 다음 명령의 `<국가UUID>`를 실제 값으로
바꿔 실행한다. 꺾쇠는 입력하지 않는다.

```text
/region create <국가UUID> 수인시
/region list
```

같은 방식으로 수인시 안에 중구, 중구 안에 운회동을 만든다.

```text
/region create <수인시UUID> 중구
/region create <중구UUID> 운회동
```

각 `create` 전에 해당 지역 경계를 새로 선택해야 한다. 이름 인자는 명령의 나머지
전체를 읽으므로 `마인 연방공화국`처럼 공백이 있는 이름도 따옴표 없이 입력한다.
상위 지정에는 실제 UUID 또는 국가 생성용 `root`를 쓰며 이름이나 `here`를 쓰지 않는다.

전체 관리자 또는 수인시보다 상위 지역의 관리자는 다음과 같이 시 관리자를 임명한다.

```text
/region manager grant <수인시UUID> PlayerName
```

이 사용자는 수인시 아래의 구·동을 생성하고 수정하며 그 관리자를 임명할 수 있다.
자기 수인시 자체의 변경은 상위 관리자에게 요청한다.

## 경계 편집

선택은 플레이어별 미저장 작업본이다. `create` 또는 `boundary`를 실행해야 API에
반영된다. 접속을 종료하면 선택 작업본은 사라진다.

| 명령 | 동작 |
| --- | --- |
| `/region select clear` | 전체 선택 초기화 |
| `/region select point` | 현재 플레이어가 있는 블록 X/Z를 추가 |
| `/region select point <x> <z>` | 지정 좌표 추가; 소수 좌표 가능 |
| `/region select undo` | 현재 고리의 마지막 점 취소 |
| `/region select part` | 떨어진 섬 등 새 분리 영역의 외곽 시작 |
| `/region select hole` | 현재 영역 내부에서 제외할 구멍 시작 |
| `/region select focus <영역번호> <고리번호>` | 편집할 고리 변경; 번호는 1부터 시작 |
| `/region select load <지역UUID|here>` | 저장된 경계를 선택 작업본으로 불러오기 |
| `/region select vertex <정점번호> <x> <z>` | 현재 고리의 정점 좌표 변경 |
| `/region select remove <정점번호>` | 현재 고리에서 정점 하나 제거 |
| `/region select drop` | 현재 구멍 삭제; 외곽 고리라면 해당 분리 영역 전체 삭제 |
| `/region select show` | 작업본 기준 버전, 현재 고리의 정점 목록과 입자 미리보기 |
| `/region select rebase` | 점들을 유지하고 작업본 기준 버전을 최신 동기화 버전으로 명시적으로 갱신 |
| `/region boundary <지역UUID|here>` | 선택 전체로 해당 지역의 경계를 교체 |

각 영역에서 고리 1은 외곽이고 고리 2부터 구멍이다. 외곽에 세 점 이상을 찍고
`select hole`을 실행한 뒤 내부 구멍의 점들을 찍는다. 섬을 추가하려면 현재 고리를
완성한 후 `select part`를 실행한다. 다른 고리로 돌아갈 때는 `select focus 1 1`처럼
선택한다. 인게임 작업본은 정점 4000개, 분리 영역 32개, 영역당 고리 32개까지 받는다.

기존 경계 수정 예시:

```text
/region select load <지역UUID>
/region select focus 1 1
/region select vertex 2 950 100
/region select show
/region boundary <지역UUID>
```

`here`는 현재 위치에 속하는 가장 깊은 지역이다. 상위 시를 바꾸려던 곳에 동이
지정되어 있다면 `here`는 그 동을 가리킨다. 큰 지역을 수정할 때는 UUID로 지정한다.
저장 시 중첩, 자기 교차, 부모 포함과 자식 보존을 검사하며 실패하면 기존 영토를 유지한다.

경계 작업본은 선택 시작 또는 `load` 당시의 버전을 보관한다. 작업 중 다른 변경이
저장되면 `create`·`boundary`가 오래된 작업본으로 덮어쓰지 않고 충돌을 알린다.
최신 경계부터 다시 편집하려면 `select load`를 쓴다. 자신의 점들을 유지해 적용하려면
먼저 `info`와 `history`로 변경을 검토하고 `select rebase`를 실행한 뒤 저장한다.
`rebase`는 점들을 병합하거나 안전성을 미리 보증하는 명령이 아니며 저장 시 모든
권한·기하 검사를 다시 수행한다. 다른 지역의 변경도 데이터셋 버전을 올릴 수 있다.

## 서버 콘솔에서 GeoJSON 가져오기

플레이어가 접속하지 않아도 실제 서버 콘솔 또는 인증된 RCON에서 파일을 가져올 수 있다.
이 명령은 플레이어 선택 작업본을 사용하지 않는다. 일반·OP 플레이어, 명령 블록과
콘솔의 `execute as <플레이어>` 경로에는 허용하지 않는다.

파일은 **Minecraft 서버 프로세스가 보는 Fabric 설정 디렉터리** 아래
`minefed-regions/imports/`에 둔다. 기본 경로는 `config/minefed-regions/imports/`다.
Podman에서는 컨테이너 내부 기준 경로이므로 해당 경로에 연결된 볼륨 또는 컨테이너의
실제 설정 폴더에 파일이 있어야 한다. 명령에는 경로 없이 `.geojson` 파일명만 입력한다.
절대 경로·경로 구분자·상위 디렉터리 이동과 외부 파일을 가리키는 심볼릭 링크는 거부한다.

| 콘솔 명령 | 동작 |
| --- | --- |
| `region import validate <파일명>` | API 연결 없이 파일 구조를 검사하고 파일명, SHA-256, 영역·고리 수, 폐합점 포함·제외 정점 수 출력 |
| `region import create <root\|상위UUID> <파일명> <이름...>` | 새 UUID로 국가 또는 하위 지역 생성; `root`는 부모 없음 |
| `region import boundary <지역UUID> <파일명>` | 기존 지역의 전체 경계를 파일 도형으로 교체; 없는 UUID는 실패 |

`create`의 이름은 명령의 나머지 전체이므로 공백이 있는 이름도 따옴표 없이 입력한다.
기존 이름·계층 규칙을 그대로 적용하며, 이름이 중복되어도 기존 지역을 덮어쓰지 않는다.
`boundary`는 **모든 섬과 내부 구멍을 포함한 전체 geometry를 교체**한다. 파일에 없는
기존 경계 부분은 남지 않는다. 대상의 UUID·이름·부모·관리자·지도 표시 단계는 유지한다.
두 저장 명령은 설정의 `worldId`를 사용하며 API 연결이 끊긴 동안에는 실행하지 않는다.

지원 형식은 UTF-8 JSON의 루트가 `MultiPolygon`인 `.geojson` 파일이다.
`Feature`와 `FeatureCollection`은 지원하지 않는다. 배열은
`coordinates[분리영역][고리][정점][좌표]`이며 각 영역의 첫 고리는 외곽,
나머지는 제외할 내부 구멍이다.

```json
{
  "type": "MultiPolygon",
  "coordinates": [
    [
      [[0, 0], [1000, 0], [1000, 1000], [0, 1000], [0, 0]]
    ]
  ]
}
```

좌표는 Minecraft **[X, Z]**이며 Y나 위도·경도로 변환하지 않는다. 좌표와 고리의
순서, 섬·구멍을 유지하고 자동 단순화·반올림·폐합 보정을 하지 않는다. 파일에 적힌
`regionId`, `name`, `actorUuid`, `console` 또는 관리자 정보는 변경 요청에 사용하지 않는다.

- 각 좌표는 유한한 숫자 정확히 두 개이고, 각 절댓값은 30,000,000 이하여야 한다.
- 빈 MultiPolygon·영역·고리, `null`, 3차원 좌표, NaN·Infinity는 허용하지 않는다.
- 각 고리는 폐합점을 포함해 최소 4개 좌표이며 첫 좌표와 마지막 좌표가 같아야 한다.
- 전체 정점은 **폐합점 포함 4,096개 이하**다. 인게임 Selection의 정점 4,000개,
  영역 32개·영역당 고리 32개 제한은 별도 편집기 제한이며 파일 가져오기에는 적용하지 않는다.
- 입력 파일은 **1 MiB 이하**이고, 저장할 때 최종 직렬화한 **API 요청 본문 전체가
  512 KiB 이하**여야 한다. 파일 구조 검사를 통과해도 요청 본문 크기 제한에 걸릴 수 있다.

`validate`의 성공 문구는 `파일 구조 검증 통과`다. 파일을 읽고 구조와 좌표 제한만
검사하며 서버·API 데이터를 변경하지 않는다. API에는 dry-run이 없으므로 자기 교차,
부모 포함·자식 보존, 형제 지역 간 중첩과 실제 등록 가능 여부를 검증했다는 뜻이 아니다.
이런 최종 판정은 `create` 또는 `boundary` 저장 시 기존 API가 수행한다.

파일 읽기와 저장은 비동기로 처리한다. **요청 접수는 저장 완료가 아니다.** 최종 저장
성공 메시지의 지역 UUID와 저장 버전을 확인한 뒤 다음 저장 명령을 실행한다. 최종
결과는 서버 로그에도 남으므로 RCON 최초 응답만으로 성공을 판단하지 않는다. 저장 성공
후에는 기존 캐시·스냅샷·BlueMap·클라이언트 갱신 경로로 반영한다.

명령이 접수한 기대 버전과 API의 현재 버전이 다르면 충돌로 실패하며 자동 덮어쓰기나
재시도를 하지 않는다. `region history`와 `region list`로 현재 상태를 검토한 뒤 다음
작업을 결정한다. 네트워크 타임아웃이나 응답 단절은 **저장 결과가 불확실**하다는 뜻이다.
이미 저장됐을 수도 있으므로 이력·목록을 먼저 확인하고, 새 UUID로 생성 명령을 즉시
반복하지 않는다.

다음 예시에서는 두 파일을 imports 폴더에 준비한 뒤 먼저 각각 검증한다.

```text
region import validate minefed-command-geometry.geojson
region import validate free-union-command-geometry.geojson
region import create root minefed-command-geometry.geojson 마인연방공화국
```

**마인연방공화국의 최종 저장 성공과 UUID·버전을 확인한 뒤** 다음 명령을 실행한다.

```text
region import create root free-union-command-geometry.geojson 자유인연합국
```

원본 파일의 예상 통계는 마인연방공화국이 영역 1개·고리 10개·폐합점 제외 3,824개
및 포함 3,834개, 자유인연합국이 영역 2개·고리 4개·폐합점 제외 1,480개 및 포함
1,484개다. 이미 확정된 좌표를 재생성하거나 단순화할 필요는 없다.

## 지역 관리와 되돌리기

| 명령 | 동작 |
| --- | --- |
| `/region status` | API 연결, 버전, 지역 수 |
| `/region list [페이지]` | 주소와 지역 UUID; 페이지당 15개 |
| `/region info <지역UUID|here>` | 전체 주소, 지역 UUID, 부모와 지도 표시 단계 |
| `/region rename <지역UUID|here> <새 이름>` | 이름 변경 |
| `/region move <지역UUID|here> <상위UUID|root>` | 지역과 하위 트리를 다른 부모로 이동 |
| `/region delete <지역UUID|here> confirm` | 자식 없는 지역 삭제 |
| `/region manager grant <지역UUID|here> <닉네임|UUID>` | 해당 지역 관리자 임명 |
| `/region manager revoke <지역UUID|here> <닉네임|UUID>` | 해당 지역 관리자 해임 |
| `/region admin grant <닉네임|UUID>` | 전체 관리자 임명 |
| `/region admin revoke <닉네임|UUID>` | 전체 관리자 해임 |
| `/region history [beforeRevision]` | 관리자가 변경 이력을 20개씩 조회; 작업·이력 UUID·버전·실행자 UUID 또는 콘솔·기록 시각 표시 |
| `/region undo <이력UUID>` | 해당 변경을 현재 권한과 경계 규칙 아래에서 되돌림 |

부모를 옮기려면 기존 부모 계통과 새 부모 계통 양쪽에 권한이 있어야 한다.
국가로 바꾸거나 국가를 수정하는 작업은 전체 관리자에게 맡긴다.
같은 대상에 후속 변경이 있으면 오래된 이력으로 강제 덮어쓰지 않으며, 되돌리기 자체도
새 이력으로 남는다. 동시 수정 충돌이 나면 최신 상태를 확인한 뒤 다시 명령한다.

`history` 인자를 생략하면 최신 기록부터 표시한다. 이전 기록은 응답 마지막의
`이전 이력` 명령을 실행한다. 예를 들어 마지막 버전이 101이면 `/region history 101`로
101보다 작은 버전들을 조회한다. 이 커서는 조회 중 새 변경이 생겨도 이전 페이지가
밀려 중복되거나 빠지지 않는다. 기록 시각은 API의 ISO 8601 형식이며 끝의 `Z`는 UTC다.

요청 후 응답이 끊겼다는 메시지를 받으면 변경이 이미 저장됐을 수도 있다.
`status`, `history`와 대상 `info`를 확인한 뒤 다시 실행한다.
새 명령을 입력하면 새 요청 ID가 생성되며 자동으로 이전 요청을 반복하지 않는다.

## HUD와 BlueMap

주소는 기본적으로 우측 상단에 표시된다. 지정된 가장 깊은 단계까지만 표시하고
국가 밖이면 `미지정 지역`, 네더·엔드에서는 숨긴다. 긴 주소는 줄바꿈하고 화면에
모두 들어가지 않으면 전체 주소 확인 명령을 안내한다.

```text
/regionshud side left
/regionshud side right
/regionshud scale 1.25
/regionshud visible false
/regionshud visible true
/regionshud address
```

배율 범위는 0.5~2이며 개인 설정은 `config/minefed-regions-hud.json`에 저장된다.
클라이언트에는 주소와 동기화 상태만 보내며 관리자 명단이나 서버 토큰은 보내지 않는다.

서버에 BlueMap이 있으면 `행정구역` 마커 레이어와 필요한 웹 자산을 자동 등록한다.
별도의 프런트엔드 서버 없이 2D·3D에서 이름·경계를 표시하고 이름을 누르면 전체 주소를
확인할 수 있다. 지도를 확대할수록 각 계통의 더 깊은 지역을 표시한다.

```text
/region display <지역UUID|here> auto
/region display <지역UUID|here> 2
```

자동 단계는 국가 0, 그 직속 1, 다음 2처럼 트리 깊이를 따른다. 따라서 국가 직속
수인시는 기본적으로 연해주와 같은 단계다. 필요하면 표시 단계를 0~63으로 재정의한다.
이 값은 행정 소속이나 권한을 바꾸지 않는다. BlueMap은 선택 사항이며 없으면 HUD와
인게임 관리는 계속 동작한다. 웹 지도에서 경계를 편집하는 UI는 이번 범위에 포함하지 않는다.

## API 장애와 복구

API와 연결이 끊기면 마지막 정상 데이터로 HUD와 BlueMap 표시를 유지하고 모든
수정 요청을 중단한다. HUD에는 `마지막 동기화 정보`가 표시된다. 최초 동기화도
성공하지 못했다면 지역 정보를 불러오지 못했다는 문구를 표시한다.
서버 월드 폴더의 `data/minefed-regions-<worldId>.json`에 마지막 snapshot을 원자적으로
저장하며 토큰은 저장하지 않는다. API 주소나 worldId가 달라진 캐시는 사용하지 않는다.

API 데이터의 revision이 캐시보다 낮으면 DB의 오래된 백업 복원 또는 잘못된 서버
연결일 수 있어 자동으로 교체하지 않는다. 먼저 API 주소와 worldId를 확인한다.
운영자가 의도적으로 과거 DB로 복구한 경우에는 서버를 안전하게 정지하고 현재 캐시를
백업한 뒤 해당 캐시 파일을 다른 이름으로 옮겨 보존하고 다시 시작한다. 이때 API의
복구 데이터를 새 기준으로 받는다. 정상 장애 복구에는 캐시를 삭제할 필요가 없다.

## 개발과 검증

검증한 빌드 환경은 **JDK 21**이며 `JAVA_HOME`을 해당 JDK로 지정한다.
산출물은 Java 17 바이트코드로 컴파일하므로 Minecraft 게임 런타임은 **Java 17**을 사용한다.
BlueMap 표시 로직의 JavaScript 테스트에는 Node.js가 필요하다.

```powershell
.\gradlew.bat test remapJar
node --test src/test/js/regions-map.test.cjs
```

Linux/macOS에서는 `./gradlew test remapJar`를 사용한다. 결과는
`build/libs/minefed-regions-1.0.0.jar`다. 소스 JAR·개발용 JAR 대신 이 실행 JAR를 사용한다.
Java 테스트는 모델·경계 선택·설정 검증을, Node 테스트는 BlueMap 표시 단계 계산을
검사한다. 실제 HUD 렌더링, BlueMap 웹 표시와 운영 API 연결 확인은 별도의 실행 검증이다.
실제로 수행한 테스트와 서버 실행 검증의 범위·결과는 [검증 기록](docs/VALIDATION.md)에 남긴다.

빌드에는 Minecraft/Fabric API/BlueMap API를 사용하지만 해당 외부 JAR를 모드 안에
묶지 않는다. MIT 저작권·라이선스 고지를 배포 JAR에 포함한다.
빌드 도구인 Gradle Wrapper의 기존 Apache-2.0 저작권·라이선스 고지는 그대로 유지한다.
기여 전 [AGENTS.md](AGENTS.md)를 읽고, 상위 `mods` checkout이 있으면 상위 지침도 따른다.
