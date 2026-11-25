#!/bin/bash

# Generates the gateway's Go code and OpenAPI definitions from OpenCue's protos into gen/.
#
# Usage: generate_proto.sh [proto source dir]   (default: ../../proto/src)
#
# Needs protoc 3.12 or newer and, on the PATH, the plugins go.mod lists as tools (`go install tool`).

set -euo pipefail

cd "$(dirname "$0")"
proto_dir="${1:-../../proto/src}"

# protoc 3.12-3.14 need this for proto3 `optional` fields; newer versions ignore it.
optional_flag=--experimental_allow_proto3_optional

mkdir -p gen/go gen/openapiv2
protoc -I "${proto_dir}" "${optional_flag}" \
  --go_out ./gen/go/ \
  --go_opt paths=source_relative \
  --go-grpc_out ./gen/go/ \
  --go-grpc_opt paths=source_relative \
  "${proto_dir}"/*.proto
protoc -I "${proto_dir}" "${optional_flag}" \
  --grpc-gateway_out ./gen/go \
  --grpc-gateway_opt paths=source_relative \
  --grpc-gateway_opt generate_unbound_methods=true \
  "${proto_dir}"/*.proto
protoc -I "${proto_dir}" "${optional_flag}" \
  --openapiv2_out ./gen/openapiv2 \
  --openapiv2_opt generate_unbound_methods=true \
  --openapiv2_opt logtostderr=true \
  "${proto_dir}"/*.proto
