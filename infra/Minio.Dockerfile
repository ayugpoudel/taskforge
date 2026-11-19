# MinIO community containers are no longer reliably published. Build the pinned security release.
FROM golang:1.24.8-alpine AS build
RUN apk add --no-cache git ca-certificates
RUN git clone --depth 1 --branch RELEASE.2025-10-15T17-29-55Z https://github.com/minio/minio.git /src
WORKDIR /src
RUN CGO_ENABLED=0 go build -trimpath -ldflags="-s -w" -o /minio .
FROM alpine:3.22
RUN apk add --no-cache ca-certificates curl && adduser -D -u 10001 minio && mkdir /data && chown minio /data
COPY --from=build /minio /usr/local/bin/minio
COPY --from=build /src/LICENSE /usr/share/licenses/minio/LICENSE
USER 10001
EXPOSE 9000 9001
ENTRYPOINT ["minio"]
