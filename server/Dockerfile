FROM golang:1.27-alpine AS build
WORKDIR /src
COPY go.mod ./
COPY internal/protocol ./internal/protocol
COPY server ./server
RUN CGO_ENABLED=0 go build -trimpath -ldflags="-s -w" -o /hark-mailbox ./server

FROM alpine:3.21
RUN adduser -D -u 10001 app && mkdir -p /data && chown app:app /data
COPY --from=build /hark-mailbox /usr/local/bin/hark-mailbox
USER app
EXPOSE 8000
ENTRYPOINT ["/usr/local/bin/hark-mailbox"]
