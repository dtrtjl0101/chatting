# 모여톡

Spring Boot 4 + Java 21 실시간 채팅. 브라우저 정적 화면은 Spring Boot가 제공하며 `/ws` WebSocket에서 실시간 메시지와 접속자 수를 교환합니다.

## 로컬 개발

```bash
npm run dev
```

`http://localhost:8080`에서 확인합니다. 기존 개발 환경에서 8080을 이미 사용한다면 `SERVER_PORT=8081 npm run dev`로 실행할 수 있습니다.

## OrbStack + Cloudflare Tunnel

OrbStack에서 Docker가 켜져 있는 Mac mini를 상시 실행 서버로 사용합니다. 배포는 Docker 이미지 빌드 후 새 슬롯(blue/green)을 헬스체크하고 Nginx upstream을 reload합니다.

1. Docker Desktop CLI가 OrbStack Docker에 연결되는지 `docker info`로 확인합니다.
2. Cloudflare Zero Trust 대시보드에서 remotely-managed Tunnel을 만들고, Public Hostname을 앱 도메인에 연결합니다. Service URL은 `http://proxy:80`으로 지정합니다.
3. Tunnel connector 토큰을 `deploy/.env`에 `CLOUDFLARE_TUNNEL_TOKEN=...`으로 저장합니다 (`deploy/.env.example` 참고).
4. 최초 배포 후 터널을 시작합니다:

   ```bash
   npm run deploy
   npm run tunnel
   ```

5. 이후 배포는 `npm run deploy`를 실행합니다. tunnel 컨테이너는 계속 실행됩니다.

Nginx는 로컬에서 `http://localhost:8088`로도 접근할 수 있습니다. Cloudflare Tunnel이 외부 HTTPS 진입점을 제공합니다. 포트 포워딩은 필요하지 않습니다.

배포 스크립트는 새 앱 인스턴스가 healthy가 된 뒤 트래픽을 넘깁니다. 일반 HTTP 요청은 다운타임 없이 전환되고, 이전 인스턴스 종료 때 기존 WebSocket 세션은 끊길 수 있으며 브라우저가 자동으로 재연결합니다. 현재 채팅방 접속자/메시지는 메모리에 있어 앱 재배포 때 초기화됩니다. 서버를 여러 대로 확장하거나 메시지 기록을 유지하기 전에 Redis 및 영구 DB를 추가해야 합니다.
