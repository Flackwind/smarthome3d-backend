#!/bin/sh
# Small checksum-pinned Maven launcher; no dependency on the frontend repository.
set -eu
if [ -n "${MAVEN_HOME:-}" ] && [ -x "$MAVEN_HOME/bin/mvn" ]; then exec "$MAVEN_HOME/bin/mvn" "$@"; fi
if command -v mvn >/dev/null 2>&1; then exec mvn "$@"; fi
version=3.9.11
expected=bcfe4fe305c962ace56ac7b5fc7a08b87d5abd8b7e89027ab251069faebee516b0ded8961445d6d91ec1985dfe30f8153268843c89aa392733d1a3ec956c9978
cache="${MAVEN_USER_HOME:-$HOME/.m2}/wrapper/dists/apache-maven-$version"
if [ ! -x "$cache/bin/mvn" ]; then
  temporary=$(mktemp -d)
  trap 'rm -rf "$temporary"' EXIT HUP INT TERM
  url="https://repo.maven.apache.org/maven2/org/apache/maven/apache-maven/$version/apache-maven-$version-bin.tar.gz"
  if command -v curl >/dev/null 2>&1; then curl --fail --location --silent --show-error "$url" -o "$temporary/maven.tar.gz"; else wget -q "$url" -O "$temporary/maven.tar.gz"; fi
  if command -v sha512sum >/dev/null 2>&1; then actual=$(sha512sum "$temporary/maven.tar.gz" | cut -d ' ' -f 1); else actual=$(shasum -a 512 "$temporary/maven.tar.gz" | cut -d ' ' -f 1); fi
  if [ "$actual" != "$expected" ]; then echo 'Maven download checksum mismatch' >&2; exit 1; fi
  mkdir -p "$cache"
  tar -xzf "$temporary/maven.tar.gz" -C "$cache" --strip-components=1
fi
exec "$cache/bin/mvn" "$@"
